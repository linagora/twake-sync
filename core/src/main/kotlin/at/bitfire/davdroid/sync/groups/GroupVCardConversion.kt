/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.groups

import at.bitfire.synctools.mapping.contacts.Contact
import java.util.Locale

/**
 * A group vCard (`KIND:group` / `X-ADDRESSBOOKSERVER-KIND:group`) received while the group method is
 * [at.bitfire.synctools.vcard.GroupMethod.CATEGORIES].
 *
 * @param title       title of the group (`FN`)
 * @param memberUids  UIDs of the members (`MEMBER` / `X-ADDRESSBOOKSERVER-MEMBER`)
 */
data class ReceivedGroupVCard(
    val title: String,
    val memberUids: Set<String>
)

/**
 * Pure logic of the conversion of group vCards to per-contact categories, see
 * [at.bitfire.davdroid.resource.local.LocalAddressBook.applyGroupVCardsAsCategories].
 *
 * Category names are compared case-insensitively (like most CardDAV clients do), so that
 * a group vCard "Friends" and an existing category "friends" don't end up as two categories.
 */
object GroupVCardConversion {

    /**
     * Key to compare category names / group titles case-insensitively.
     */
    fun titleKey(title: String): String = title.trim().lowercase(Locale.ROOT)

    /**
     * Title of a received group vCard: its formatted name (`FN`) or, if there is none, its structured
     * name (`N`).
     *
     * @return title or `null` if the group vCard has no name at all
     */
    fun groupTitle(group: Contact): String? =
        group.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOf(group.prefix, group.givenName, group.middleName, group.familyName, group.suffix)
                .mapNotNull { it?.trim()?.takeIf { part -> part.isNotEmpty() } }
                .joinToString(" ")
                .takeIf { it.isNotEmpty() }

    /**
     * Computes which categories have to be added to which contacts so that every member of a
     * received group vCard has the group title as category. The current categories of a contact
     * (as just downloaded from the server) are kept: categories are only added, never removed.
     *
     * - Groups whose titles only differ in case are merged (the first spelling is used).
     * - Groups without a title are ignored.
     * - Groups without members are ignored, because an empty group can't be represented as
     *   category of a contact.
     * - Members which are not in the address book ([currentCategories] returns `null`) are ignored.
     * - Members which already have the category (case-insensitively) are left alone, so that they're
     *   not uploaded again.
     *
     * @param groups             received group vCards
     * @param currentCategories  returns the current categories of the contact with the given UID,
     *                           or `null` when there's no such contact (called at most once per UID)
     *
     * @return map of contact UID → categories to add (only contacts which need a change)
     */
    fun categoriesToAdd(
        groups: Collection<ReceivedGroupVCard>,
        currentCategories: (memberUid: String) -> Collection<String>?
    ): Map<String, Set<String>> {
        // title key → (first spelling, member UIDs)
        val membersByTitle = linkedMapOf<String, Pair<String, MutableSet<String>>>()
        for (group in groups) {
            val title = group.title.trim()
            if (title.isNotEmpty())
                membersByTitle.getOrPut(titleKey(title)) { title to linkedSetOf() }.second += group.memberUids
        }

        // current category keys per UID; null = contact not in address book (also cached)
        val categoriesCache = mutableMapOf<String, Set<String>?>()
        val result = linkedMapOf<String, MutableSet<String>>()
        for ((key, titleAndMembers) in membersByTitle) {
            val (title, memberUids) = titleAndMembers
            for (uid in memberUids) {
                val categoryKeys =
                    if (categoriesCache.containsKey(uid))
                        categoriesCache[uid]
                    else
                        currentCategories(uid)?.map { titleKey(it) }?.toSet().also { categoriesCache[uid] = it }
                if (categoryKeys != null && key !in categoryKeys)
                    result.getOrPut(uid) { linkedSetOf() } += title
            }
        }
        return result
    }

}
