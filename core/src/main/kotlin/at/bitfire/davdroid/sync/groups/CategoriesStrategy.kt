/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.groups

import at.bitfire.davdroid.resource.local.LocalAddressBook
import at.bitfire.synctools.mapping.contacts.Contact
import java.util.Optional
import java.util.logging.Logger

class CategoriesStrategy(val addressBook: LocalAddressBook): ContactGroupStrategy {

    private val logger: Logger
        get() = Logger.getGlobal()

    /**
     * Group vCards received during this sync. Only used when the address book is being converted from
     * [at.bitfire.synctools.vcard.GroupMethod.GROUP_VCARDS] (see [LocalAddressBook.isGroupVCardsConversionPending]).
     */
    internal val receivedGroupVCards = mutableListOf<ReceivedGroupVCard>()

    override suspend fun resolveLocalGroupChanges() {
        // groups with DELETED=1: set all members to dirty, then remove group
        addressBook.findDeletedGroups().collect { group ->
            logger.fine("Finally removing group $group")
            group.markMembersDirty()
            group.androidGroup.delete()
        }

        // groups with DIRTY=1: mark all members as dirty, then clean DIRTY flag of group
        addressBook.findDirtyGroups().collect { group ->
            logger.fine("Marking members of modified group $group as dirty")
            group.markMembersDirty()
            group.clearDirty(Optional.empty(), null)
        }
    }

    /**
     * Group vCards (`KIND:group` / `X-ADDRESSBOOKSERVER-KIND:group`) are not saved, because in CATEGORIES
     * mode group memberships are taken from the `CATEGORIES` of the member contacts. Saving them as
     * regular contacts would create fake contacts named after the group. They're remembered so that
     * their members can be applied as categories in [postProcess] when the address book is being
     * converted from group vCards to categories.
     *
     * Known limitation: because nothing is stored locally for a group vCard, it's downloaded again
     * whenever the server returns a full listing (PROPFIND/REPORT sync algorithm, initial
     * collection-sync, resync). Group vCards are small and few, and processing them again only
     * means looking for a local entry with the same file name, so this is accepted to keep the
     * local storage free of group vCard state in CATEGORIES mode.
     */
    override fun consumeReceivedContact(contact: Contact): Boolean {
        if (!contact.group)
            return false

        val title = GroupVCardConversion.groupTitle(contact)
        if (title == null)
            logger.warning("Not saving group vCard without name (FN/N) because group method is CATEGORIES; uid=${contact.uid}")
        else {
            logger.info("Not saving group vCard because group method is CATEGORIES: $title")
            receivedGroupVCards += ReceivedGroupVCard(title, contact.members.toSet())
        }
        return true
    }

    override fun verifyContactBeforeSaving(contact: Contact) {
        if (contact.group || contact.members.isNotEmpty()) {
            logger.warning("Received contact with group members although group method is CATEGORIES. Ignoring members")
            contact.group = false
            contact.members.clear()
        }
    }

    override suspend fun postProcess() {
        if (addressBook.isGroupVCardsConversionPending()) {
            /* All vCards of the address book have been downloaded (with their CATEGORIES) since the group
            method has been switched from GROUP_VCARDS: now add the members of the group vCards as categories. */
            logger.info("Converting ${receivedGroupVCards.size} group vCard(s) to categories")
            addressBook.applyGroupVCardsAsCategories(receivedGroupVCards)
            addressBook.setGroupVCardsConversionPending(false)
        }

        logger.info("Removing empty groups")
        addressBook.ab.deleteGroupsWithoutMembers()
    }

}
