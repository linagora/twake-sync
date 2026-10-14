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
     * Group vCards (`KIND:group` / `X-ADDRESSBOOKSERVER-KIND:group`) are ignored, because in CATEGORIES
     * mode group memberships are taken from the `CATEGORIES` of the member contacts. Saving them as
     * regular contacts would create fake contacts named after the group.
     */
    override fun verifyContactBeforeSaving(contact: Contact): Boolean {
        if (contact.group) {
            logger.info("Ignoring group vCard because group method is CATEGORIES: ${contact.displayName}")
            return false
        }
        if (contact.members.isNotEmpty()) {
            logger.warning("Received contact with group members although group method is CATEGORIES. Ignoring members")
            contact.members.clear()
        }
        return true
    }

    override suspend fun postProcess() {
        logger.info("Removing empty groups")
        addressBook.ab.deleteGroupsWithoutMembers()
    }

}