/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.groups

import at.bitfire.synctools.mapping.contacts.Contact

interface ContactGroupStrategy {

    /**
     * Local-only group housekeeping (e.g. dissolving groups, propagating group-level changes to
     * member contacts) that must happen regardless of whether local changes will be uploaded.
     */
    suspend fun resolveLocalGroupChanges() {}

    /**
     * Prepares group state for upload. Only meaningful when local changes are actually pushed
     * to the server.
     */
    suspend fun beforeUploadDirty() {}

    /**
     * Gives the strategy the chance to take over a received vCard instead of having it saved to the
     * local address book (for instance, a group vCard when groups are represented as categories).
     *
     * Called before [verifyContactBeforeSaving]. When it returns `true`, the vCard is not saved, and a
     * local entry which may have been created from it earlier is removed.
     *
     * @return whether the strategy has taken over the vCard (`false`: save it as usual)
     */
    fun consumeReceivedContact(contact: Contact): Boolean = false

    fun verifyContactBeforeSaving(contact: Contact)
    suspend fun postProcess()

}
