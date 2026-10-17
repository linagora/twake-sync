/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.os.DeadObjectException
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.resource.local.LocalAddressBook
import at.bitfire.synctools.storage.LocalStorageException
import at.bitfire.synctools.test.assertThrows
import at.bitfire.synctools.vcard.GroupMethod
import io.ktor.http.Url
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.logging.Logger

class AddressBookSyncerTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @RelaxedMockK
    lateinit var accountManager: AccountManager

    @RelaxedMockK
    lateinit var logger: Logger

    private val addressBook: LocalAddressBook = mockk(relaxed = true)
    private val contactsSyncManager: ContactsSyncManager = mockk(relaxed = true)
    private val contactsSyncManagerFactory: ContactsSyncManager.Factory = mockk()
    private val localAddressBookFactory: LocalAddressBook.Factory = mockk()
    private val provider: ContentProviderClient = mockk(relaxed = true)
    private val settings = SyncSettingsFixtures.default()
    private val syncResult = SyncResult()
    private val userData = mutableMapOf<String, String?>()
    private val syncNotificationManager: SyncNotificationManager = mockk(relaxed = true)
    private val syncNotificationManagerFactory: SyncNotificationManager.Factory = mockk()

    private val collection = Collection(
        id = 1,
        type = Collection.TYPE_ADDRESSBOOK,
        url = Url("https://example.com/addressbook/")
    )

    private lateinit var syncer: AddressBookSyncer

    @Before
    fun setUp() {
        syncer = createSyncer(settings)

        // user data of the address book account, in memory
        every { accountManager.getUserData(any(), any()) } answers { userData[secondArg()] }
        every { accountManager.setUserData(any(), any(), any()) } answers { userData[secondArg()] = thirdArg() }
        // group method hasn't changed, so that handleGroupMethodChange() doesn't do anything
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = settings.groupMethod.name

        every { syncNotificationManagerFactory.create(any()) } returns syncNotificationManager

        every {
            contactsSyncManagerFactory.contactsSyncManager(
                accountId = any(),
                httpClient = any(),
                syncResult = any(),
                provider = any(),
                localAddressBook = any(),
                collectionInfo = any(),
                remoteCollection = any(),
                resync = any(),
                syncFrameworkUpload = any(),
                settings = any()
            )
        } returns contactsSyncManager

        every { addressBook.addressBookAccount } returns Account("Test Address Book", "test")
    }

    private fun createSyncer(settings: SyncSettings) =
        AddressBookSyncer(
            accountId = mockk(relaxed = true),
            resync = null,
            syncFrameworkUpload = false,
            syncResult = syncResult,
            settings = settings,
            accountManager = { accountManager },
            addressBookStore = mockk(relaxed = true),
            contactsSyncManagerFactory = contactsSyncManagerFactory,
            localAddressBookFactory = localAddressBookFactory,
            ioDispatcher = Dispatchers.Unconfined
        ).apply {
            httpClientBuilder = mockk(relaxed = true)
            logger = this@AddressBookSyncerTest.logger
            context = mockk(relaxed = true)
            syncNotificationManagerFactory = this@AddressBookSyncerTest.syncNotificationManagerFactory
        }


    @Test
    fun testSyncCollection_rethrowsCancellation() = runTest {
        coEvery { contactsSyncManager.performSync() } throws CancellationException()

        /* Cancellation must not be swallowed here: only the Syncer/BaseSyncWorker may decide what to do
        about it (see issue #2663). */
        assertThrows<CancellationException> {
            syncer.syncCollection(provider, addressBook, collection)
        }
        assertFalse(syncResult.hasError)
    }

    @Test
    fun testSyncCollection_rethrowsDeadObjectException() = runTest {
        coEvery { contactsSyncManager.performSync() } throws
                LocalStorageException("Couldn't access local storage", DeadObjectException())

        // Same for the DeadObjectException, which the Syncer treats as soft error
        assertThrows<LocalStorageException> {
            syncer.syncCollection(provider, addressBook, collection)
        }
    }


    // switching the group method from GROUP_VCARDS to CATEGORIES

    private val categoriesSettings = settings.copy(groupMethod = GroupMethod.CATEGORIES)

    /** address book with group method GROUP_VCARDS, used to upload pending changes before switching */
    private val vCardsAddressBook: LocalAddressBook = mockk(relaxed = true)
    private val vCardsSyncManager: ContactsSyncManager = mockk(relaxed = true)
    private val vCardsSyncResult = slot<SyncResult>()

    private fun prepareSwitchToCategories(pendingLocalChanges: Int, remainingLocalChanges: Int = 0, readOnly: Boolean = false) {
        syncer = createSyncer(categoriesSettings)
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = GroupMethod.GROUP_VCARDS.name
        every { localAddressBookFactory.create(any(), any(), any(), GroupMethod.GROUP_VCARDS) } returns vCardsAddressBook
        every { vCardsAddressBook.readOnly } returns readOnly
        every { addressBook.readOnly } returns readOnly
        every { vCardsAddressBook.countDirty() } returnsMany listOf(pendingLocalChanges, remainingLocalChanges)
        every {
            contactsSyncManagerFactory.contactsSyncManager(
                accountId = any(),
                httpClient = any(),
                syncResult = capture(vCardsSyncResult),
                provider = any(),
                localAddressBook = vCardsAddressBook,
                collectionInfo = any(),
                remoteCollection = any(),
                resync = any(),
                syncFrameworkUpload = any(),
                settings = match { it.groupMethod == GroupMethod.GROUP_VCARDS }
            )
        } returns vCardsSyncManager
    }

    private fun verifyWiped() {
        verify { provider.delete(match { it.path!!.contains("raw_contacts") }, null, null) }
        verify { provider.delete(match { it.path!!.contains("groups") }, null, null) }
    }

    private fun verifySwitchPostponed() {
        verify(exactly = 0) { provider.delete(any(), any(), any()) }
        coVerify(exactly = 0) { addressBook.setGroupVCardsConversionPending(any()) }
        assertEquals(GroupMethod.GROUP_VCARDS.name, userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD])
        coVerify(exactly = 0) { contactsSyncManager.performSync() }
    }

    @Test
    fun testSwitchToCategories_withoutPendingChanges_wipesAndStartsConversion() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 0)

        syncer.syncCollection(provider, addressBook, collection)

        coVerify(exactly = 0) { vCardsSyncManager.performSync() }
        // the address book is downloaded again (old behavior), never uploaded from local data
        verifyWiped()
        verify { addressBook.syncState = null }
        coVerify { addressBook.setGroupVCardsConversionPending(true) }
        assertEquals(GroupMethod.CATEGORIES.name, userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD])
        coVerify { contactsSyncManager.performSync() }
    }

    @Test
    fun testSwitchToCategories_readOnly_convertsLocallyWithoutUpload() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 3, readOnly = true)

        syncer.syncCollection(provider, addressBook, collection)

        // nothing to upload from a read-only address book
        coVerify(exactly = 0) { vCardsSyncManager.performSync() }
        verifyWiped()
        // memberships are applied locally (LocalAddressBook doesn't mark them dirty when read-only)
        coVerify { addressBook.setGroupVCardsConversionPending(true) }
        coVerify { contactsSyncManager.performSync() }
    }

    private fun verifyPostponedNotification(times: Int, pendingChanges: Int = 2) {
        coVerify(exactly = times) {
            syncNotificationManager.notifyGroupMethodSwitchPostponed(collection.id, any(), pendingChanges)
        }
    }

    @Test
    fun testSwitchToCategories_uploadsPendingChangesWithGroupVCardsFirst() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 2, remainingLocalChanges = 0)

        syncer.syncCollection(provider, addressBook, collection)

        coVerifyOrder {
            vCardsSyncManager.performSync()
            provider.delete(any(), null, null)
            contactsSyncManager.performSync()
        }
        assertFalse(syncResult.hasError)
        verifyPostponedNotification(times = 0)
        verify { syncNotificationManager.dismissGroupMethodSwitchPostponed(collection.id) }
    }

    @Test
    fun testSwitchToCategories_errorAfterAllChangesUploaded_proceeds() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 2, remainingLocalChanges = 0)
        coEvery { vCardsSyncManager.performSync() } answers { vCardsSyncResult.captured.hardError = true }

        syncer.syncCollection(provider, addressBook, collection)

        verifyWiped()
        coVerify { contactsSyncManager.performSync() }
        verifyPostponedNotification(times = 0)
    }

    @Test
    fun testSwitchToCategories_softError_postponedWithoutNotification() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 2)
        every { vCardsAddressBook.countDirty() } returns 2
        coEvery { vCardsSyncManager.performSync() } answers { vCardsSyncResult.captured.softError = true }

        repeat(4) {
            syncer.syncCollection(provider, addressBook, collection)
        }

        // local data is kept, the switch is retried with the next sync
        verifySwitchPostponed()
        coVerify(exactly = 4) { vCardsSyncManager.performSync() }
        assertTrue(syncResult.softError)
        assertFalse(syncResult.hardError)
        verifyPostponedNotification(times = 0)
    }

    @Test
    fun testSwitchToCategories_hardError_neverDiscardsAndNotifies() = runTest {
        // e.g. 401 Unauthorized after a password change, or a contact the server always rejects
        prepareSwitchToCategories(pendingLocalChanges = 2)
        every { vCardsAddressBook.countDirty() } returns 2
        coEvery { vCardsSyncManager.performSync() } answers { vCardsSyncResult.captured.hardError = true }

        repeat(5) {
            syncer.syncCollection(provider, addressBook, collection)
        }

        // nothing discarded: never wiped, still the old method, the old-method sync goes on every time
        verifySwitchPostponed()
        coVerify(exactly = 5) { vCardsSyncManager.performSync() }
        assertTrue(syncResult.hardError)
        verifyPostponedNotification(times = 5)
        verify(exactly = 0) { syncNotificationManager.dismissGroupMethodSwitchPostponed(any()) }
    }

    @Test
    fun testSwitchToCategories_changesRemainWithoutError_postponedAndNotifies() = runTest {
        // e.g. 403 need-privileges, which SyncManager ignores
        prepareSwitchToCategories(pendingLocalChanges = 2, remainingLocalChanges = 1)

        syncer.syncCollection(provider, addressBook, collection)

        verifySwitchPostponed()
        assertTrue(syncResult.softError)
        verifyPostponedNotification(times = 1, pendingChanges = 1)
    }

    @Test
    fun testSwitchToCategories_successAfterEarlierFailures_completesAndDismissesNotification() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 2)
        // two failed syncs (pending before / after), then one where everything is uploaded
        every { vCardsAddressBook.countDirty() } returnsMany listOf(2, 2, 2, 2, 2, 0)
        var failures = 2
        coEvery { vCardsSyncManager.performSync() } answers {
            if (failures-- > 0)
                vCardsSyncResult.captured.hardError = true
        }

        repeat(3) {
            syncer.syncCollection(provider, addressBook, collection)
        }

        verifyPostponedNotification(times = 2)
        verifyWiped()
        coVerify { addressBook.setGroupVCardsConversionPending(true) }
        assertEquals(GroupMethod.CATEGORIES.name, userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD])
        coVerifyOrder {
            syncNotificationManager.notifyGroupMethodSwitchPostponed(collection.id, any(), 2)
            syncNotificationManager.dismissGroupMethodSwitchPostponed(collection.id)
            contactsSyncManager.performSync()
        }
    }

    @Test
    fun testSwitchBackWhilePostponed_cancelsSwitchAndDismissesNotification() = runTest {
        prepareSwitchToCategories(pendingLocalChanges = 2)
        every { vCardsAddressBook.countDirty() } returns 2
        coEvery { vCardsSyncManager.performSync() } answers { vCardsSyncResult.captured.hardError = true }
        syncer.syncCollection(provider, addressBook, collection)
        verifyPostponedNotification(times = 1)

        // user switches back to GROUP_VCARDS: the method used for the address book hasn't changed
        syncer = createSyncer(settings)
        syncer.syncCollection(provider, addressBook, collection)

        verify { syncNotificationManager.dismissGroupMethodSwitchPostponed(collection.id) }
        // nothing deleted, normal sync with group vCards
        verify(exactly = 0) { provider.delete(any(), any(), any()) }
        assertEquals(GroupMethod.GROUP_VCARDS.name, userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD])
        coVerify(exactly = 0) { addressBook.setGroupVCardsConversionPending(true) }
        coVerify(exactly = 1) { contactsSyncManager.performSync() }
    }

    @Test
    fun testConversionPending_resetsSyncStateAndUploadsChangedMembers() = runTest {
        syncer = createSyncer(categoriesSettings)
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = GroupMethod.CATEGORIES.name
        // pending before the sync, finished by CategoriesStrategy.postProcess() which marks changed members dirty
        coEvery { addressBook.isGroupVCardsConversionPending() } returnsMany listOf(true, true, false)
        every { addressBook.countDirty() } returns 1

        syncer.syncCollection(provider, addressBook, collection)

        verify { addressBook.syncState = null }
        verify(exactly = 0) { provider.delete(any(), any(), any()) }
        // second pass uploads the merged CATEGORIES
        coVerify(exactly = 2) { contactsSyncManager.performSync() }
    }

    @Test
    fun testConversionPending_notFinished_noSecondPass() = runTest {
        syncer = createSyncer(categoriesSettings)
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = GroupMethod.CATEGORIES.name
        coEvery { addressBook.isGroupVCardsConversionPending() } returns true
        every { addressBook.countDirty() } returns 1

        syncer.syncCollection(provider, addressBook, collection)

        coVerify(exactly = 1) { contactsSyncManager.performSync() }
    }

    @Test
    fun testCategories_unchanged_noResyncOfAllContacts() = runTest {
        syncer = createSyncer(categoriesSettings)
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = GroupMethod.CATEGORIES.name

        syncer.syncCollection(provider, addressBook, collection)

        verify(exactly = 0) { addressBook.syncState = any() }
        coVerify(exactly = 0) { addressBook.forgetETags() }
        verify(exactly = 0) { provider.delete(any(), any(), any()) }
        coVerify(exactly = 1) { contactsSyncManager.performSync() }
    }

    @Test
    fun testSwitchToGroupVCards_unchanged() = runTest {
        // settings.groupMethod = GROUP_VCARDS
        userData[AddressBookSyncer.PREVIOUS_GROUP_METHOD] = GroupMethod.CATEGORIES.name

        syncer.syncCollection(provider, addressBook, collection)

        verifyWiped()
        verify(exactly = 0) { localAddressBookFactory.create(any(), any(), any(), any()) }
        coVerify(exactly = 0) { addressBook.setGroupVCardsConversionPending(true) }
        coVerify(exactly = 1) { contactsSyncManager.performSync() }
    }

}
