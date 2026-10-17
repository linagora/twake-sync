/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.os.RemoteException
import android.provider.ContactsContract
import at.bitfire.davdroid.accounts.AccountId
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.resource.local.LocalAddressBook
import at.bitfire.davdroid.resource.local.LocalAddressBookStore
import at.bitfire.davdroid.resource.local.LocalContact
import at.bitfire.davdroid.resource.local.LocalGroup
import at.bitfire.davdroid.resource.remote.CardDavCollection
import at.bitfire.synctools.storage.LocalStorageException
import at.bitfire.synctools.storage.contacts.AddressContract.asSyncAdapter
import at.bitfire.synctools.util.setAndVerifyUserData
import at.bitfire.synctools.vcard.GroupMethod
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.logging.Level
import javax.inject.Provider

/**
 * Sync logic for address books
 */
class AddressBookSyncer @AssistedInject constructor(
    @Assisted accountId: AccountId,
    @Assisted resync: ResyncType?,
    @Assisted val syncFrameworkUpload: Boolean,
    @Assisted syncResult: SyncResult,
    @Assisted settings: SyncSettings,
    private val accountManager: Provider<AccountManager>,
    addressBookStore: LocalAddressBookStore,
    private val contactsSyncManagerFactory: ContactsSyncManager.Factory,
    private val localAddressBookFactory: LocalAddressBook.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : Syncer<LocalAddressBookStore, LocalAddressBook>(accountId, resync, syncResult, settings) {

    @AssistedFactory
    interface Factory {
        fun create(
            accountId: AccountId,
            resyncType: ResyncType?,
            syncFrameworkUpload: Boolean,
            syncResult: SyncResult,
            settings: SyncSettings
        ): AddressBookSyncer
    }

    override val dataStore = addressBookStore

    override val serviceType: String
        get() = Service.TYPE_CARDDAV


    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getByServiceAndSync(serviceId)

    override suspend fun syncCollection(
        provider: ContentProviderClient,
        localCollection: LocalAddressBook,
        remoteCollectionInfo: Collection
    ) {
        logger.log(Level.INFO, "Synchronizing address book: {0}", arrayOf(localCollection.addressBookAccount.name))
        syncAddressBook(
            addressBook = localCollection,
            provider = provider,
            collection = remoteCollectionInfo
        )
    }

    /**
     * Synchronizes an address book.
     *
     * Sync errors of the address book contents are handled (logged, notified, recorded in [syncResult])
     * by [SyncManager] itself. Everything it re-throws – as well as exceptions of
     * [handleGroupMethodChange] – is passed on to [invoke], which classifies it.
     *
     * @param addressBook       local address book
     * @param provider          content provider to access android contacts
     * @param collection        the database collection associated with this address book
     */
    private suspend fun syncAddressBook(
        addressBook: LocalAddressBook,
        provider: ContentProviderClient,
        collection: Collection
    ) {
        if (!handleGroupMethodChange(addressBook, provider, collection)) {
            logger.warning("Group method change couldn't be prepared, not synchronizing ${addressBook.addressBookAccount.name} now")
            return
        }

        val conversionPending = addressBook.isGroupVCardsConversionPending()

        performContactsSync(addressBook, provider, collection, syncResult, settings, resync)

        if (conversionPending && !addressBook.isGroupVCardsConversionPending() &&
            withContext(ioDispatcher) { addressBook.countDirty() } > 0
        ) {
            /* The group vCards have just been converted to categories and the changed members have been marked
            as dirty: upload them now (with their merged CATEGORIES) instead of waiting for the next sync. */
            logger.info("Uploading contacts changed by the conversion of group vCards to categories")
            performContactsSync(addressBook, provider, collection, syncResult, settings, resync = null)
        }

        logger.info("Contacts sync complete")
    }

    private suspend fun performContactsSync(
        addressBook: LocalAddressBook,
        provider: ContentProviderClient,
        collection: Collection,
        syncResult: SyncResult,
        settings: SyncSettings,
        resync: ResyncType?
    ) {
        val syncManager = contactsSyncManagerFactory.contactsSyncManager(
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            provider = provider,
            localAddressBook = addressBook,
            collectionInfo = collection,
            remoteCollection = CardDavCollection(httpClient, collection.url),
            resync = resync,
            syncFrameworkUpload = syncFrameworkUpload,
            settings = settings
        )
        syncManager.performSync()
    }

    /**
     * Handles a change of the contact group method of the account (compared to the method that has been used
     * for the last sync of the address book).
     *
     * - [GroupMethod.GROUP_VCARDS] → [GroupMethod.CATEGORIES]: local changes are uploaded with the previous
     *   method first (see [uploadLocalChangesWithGroupVCards]). Then the address book is emptied and downloaded
     *   again in [GroupMethod.CATEGORIES] mode; group vCards are not saved as contacts, but their members are
     *   applied as categories on top of the `CATEGORIES` just downloaded (see
     *   [at.bitfire.davdroid.sync.groups.CategoriesStrategy]). So no member is ever uploaded from stale local
     *   data. The group vCards are left on the server for other clients.
     * - other changes: the address book is emptied and downloaded again.
     *
     * Local changes are never discarded because of the switch: as long as they can't be uploaded with the
     * previous method, the switch is postponed and the address book keeps being synchronized with the previous
     * method (see [uploadLocalChangesWithGroupVCards]).
     *
     * Contacts which earlier versions created from group vCards in CATEGORIES mode ("fake contacts") are removed
     * when their group vCard is received again (see [ContactsSyncManager]); the address book is deliberately
     * not downloaded again for that.
     *
     * @return whether the address book can be synchronized now (`false`: local changes couldn't be uploaded
     * with the previous group method yet; nothing has been changed and the switch will be retried with the next sync)
     */
    private suspend fun handleGroupMethodChange(
        addressBook: LocalAddressBook,
        provider: ContentProviderClient,
        collection: Collection
    ): Boolean {
        val groupMethod = settings.groupMethod
        val previousGroupMethod = withContext(ioDispatcher) {
            accountManager.get().getUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD)
        }

        if (previousGroupMethod == GroupMethod.GROUP_VCARDS.name && groupMethod == GroupMethod.CATEGORIES) {
            logger.info("Group method changed from $previousGroupMethod to $groupMethod, converting group vCards to categories")
            if (!uploadLocalChangesWithGroupVCards(addressBook, provider, collection))
                return false
            syncNotificationManager.dismissGroupMethodSwitchPostponed(collection.id)

            withContext(ioDispatcher) {
                deleteLocalContactsAndGroups(addressBook, provider)
            }
            /* The members of the group vCards are applied as categories after all vCards have been downloaded
            (only locally for read-only address books, see LocalAddressBook.applyGroupVCardsAsCategories). */
            addressBook.setGroupVCardsConversionPending(true)
            withContext(ioDispatcher) {
                accountManager.get().setAndVerifyUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD, groupMethod.name)
            }
            return true
        }

        // no switch (anymore): a switch that was waiting for local changes has been completed or cancelled
        syncNotificationManager.dismissGroupMethodSwitchPostponed(collection.id)

        if (addressBook.isGroupVCardsConversionPending()) {
            if (groupMethod == GroupMethod.CATEGORIES) {
                // conversion has been interrupted: list all vCards again (group vCards aren't stored locally, so they're downloaded again)
                logger.info("Conversion of group vCards to categories is still pending, listing all vCards again")
                withContext(ioDispatcher) {
                    addressBook.syncState = null
                }
            } else
                addressBook.setGroupVCardsConversionPending(false)
        }

        withContext(ioDispatcher) {
            handleGroupMethodChangeBlocking(addressBook, provider, previousGroupMethod)
        }
        return true
    }

    /**
     * Uploads pending local changes of the address book with [GroupMethod.GROUP_VCARDS] (the group method
     * they have been made with), so that they're not lost when the address book is emptied.
     *
     * This is a complete sync with [GroupMethod.GROUP_VCARDS], so the address book keeps being synchronized with
     * the previous method while the switch is postponed.
     *
     * When local changes remain afterwards, for whatever reason, the switch is postponed (`false`) and retried
     * with the next sync, without limit; local changes are never discarded. After a hard error (e.g. a contact
     * the server rejects, authentication) or when changes remain without error (e.g. an ignored `403 Forbidden`
     * with `need-privileges`), the user is notified with a notification of its own (not the collection's
     * sync error notification, which is dismissed by every sync). Soft errors (network, server temporarily
     * unavailable) don't notify.
     *
     * Read-only address books have nothing to upload (local changes are reverted anyway).
     *
     * @return whether the address book may be emptied now
     */
    private suspend fun uploadLocalChangesWithGroupVCards(
        addressBook: LocalAddressBook,
        provider: ContentProviderClient,
        collection: Collection
    ): Boolean {
        val vCardsAddressBook = localAddressBookFactory.create(accountId, addressBook.addressBookAccount, provider, GroupMethod.GROUP_VCARDS)
        val (readOnly, pending) = withContext(ioDispatcher) {
            vCardsAddressBook.readOnly to vCardsAddressBook.countDirty() + vCardsAddressBook.countDeleted()
        }
        if (readOnly || pending == 0)
            return true

        logger.info("Uploading $pending pending local change(s) with group method ${GroupMethod.GROUP_VCARDS} before switching")
        val uploadResult = SyncResult()
        performContactsSync(
            addressBook = vCardsAddressBook,
            provider = provider,
            collection = collection,
            syncResult = uploadResult,
            settings = settings.copy(groupMethod = GroupMethod.GROUP_VCARDS),
            resync = null
        )

        val remaining = withContext(ioDispatcher) {
            vCardsAddressBook.countDirty() + vCardsAddressBook.countDeleted()
        }
        if (remaining == 0)
            // all changes uploaded (errors after the upload, e.g. while downloading, don't matter: everything is downloaded again)
            return true

        logger.warning("$remaining local change(s) couldn't be uploaded, postponing the switch of the group method: " +
                describePendingChanges(vCardsAddressBook))

        // propagate the result so that the sync is retried (soft) or the error is reported (hard)
        if (uploadResult.hardError)
            syncResult.hardError = true
        else
            syncResult.softError = true
        syncResult.delayUntil = maxOf(syncResult.delayUntil, uploadResult.delayUntil)

        if (uploadResult.hardError || !uploadResult.softError)
            // hard error or changes not uploaded although there was no error: the user may have to do something
            syncNotificationManager.notifyGroupMethodSwitchPostponed(collection.id, collection.title(), remaining)
        return false
    }

    /**
     * Describes the local changes that are still pending for the log: returns their file names (logged as
     * warning by the caller). Display names are personal data, so they're only logged at [Level.FINE].
     */
    private suspend fun describePendingChanges(addressBook: LocalAddressBook): String {
        val fileNames = mutableListOf<String>()
        try {
            addressBook.findDirty().take(MAX_LOGGED_PENDING_CHANGES).collect { local ->
                val fileName = local.fileName ?: "(new, id=${local.id})"
                fileNames += fileName

                if (logger.isLoggable(Level.FINE)) {
                    val name = withContext(ioDispatcher) {
                        try {
                            when (local) {
                                is LocalContact -> local.androidContact.getContact().displayName
                                is LocalGroup -> local.androidGroup.getContact().displayName
                                else -> null
                            }
                        } catch (e: FileNotFoundException) {
                            logger.log(Level.FINE, "Pending local change $fileName not found anymore", e)
                            null
                        }
                    }
                    logger.fine("Pending local change: $fileName \"${name ?: ""}\"")
                }
            }
        } catch (e: LocalStorageException) {
            logger.log(Level.WARNING, "Couldn't list pending local changes", e)
        } catch (e: RemoteException) {
            logger.log(Level.WARNING, "Couldn't list pending local changes", e)
        }
        return fileNames.joinToString(", ")
    }

    private fun handleGroupMethodChangeBlocking(
        addressBook: LocalAddressBook,
        provider: ContentProviderClient,
        previousGroupMethod: String?
    ) {
        val groupMethod = settings.groupMethod.name

        val accountManager = accountManager.get()
        previousGroupMethod?.let {
            if (previousGroupMethod != groupMethod) {
                logger.info("Group method changed, deleting all local contacts/groups")
                deleteLocalContactsAndGroups(addressBook, provider)
            }
        }
        accountManager.setAndVerifyUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD, groupMethod)
    }

    /**
     * Deletes all local contacts and groups so that they will be downloaded again.
     */
    private fun deleteLocalContactsAndGroups(addressBook: LocalAddressBook, provider: ContentProviderClient) {
        provider.delete(
            ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount),
            null,
            null
        )
        provider.delete(
            ContactsContract.Groups.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount),
            null,
            null
        )

        // reset sync state
        addressBook.syncState = null
    }


    companion object {

        const val PREVIOUS_GROUP_METHOD = "previous_group_method"

        /** maximum number of pending local changes listed in the log, see [describePendingChanges] */
        private const val MAX_LOGGED_PENDING_CHANGES = 50

    }
}
