/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.groups

import at.bitfire.synctools.mapping.contacts.Contact
import at.bitfire.synctools.mapping.contacts.ContactReader
import at.bitfire.synctools.mapping.contacts.ContactWriter
import at.bitfire.synctools.vcard.VCardParser
import ezvcard.VCardVersion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class GroupVCardConversionTest {

    private val contacts = mapOf(
        "carol" to listOf("Work"),      // CATEGORIES:Work on the server
        "dave" to listOf("QA Group"),   // already has the category
        "erin" to emptyList()
    )

    private val lookups = mutableListOf<String>()

    private fun categoriesToAdd(vararg groups: ReceivedGroupVCard) =
        GroupVCardConversion.categoriesToAdd(groups.toList()) { uid ->
            lookups += uid
            contacts[uid]
        }


    // categoriesToAdd

    @Test
    fun `member gets group title as additional category`() {
        val result = categoriesToAdd(ReceivedGroupVCard("QA Group", setOf("carol")))
        assertEquals(mapOf("carol" to setOf("QA Group")), result)
    }

    @Test
    fun `member which already has the category is not changed`() {
        val result = categoriesToAdd(ReceivedGroupVCard("QA Group", setOf("dave")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `unknown member is ignored`() {
        val result = categoriesToAdd(ReceivedGroupVCard("QA Group", setOf("unknown", "erin")))
        assertEquals(mapOf("erin" to setOf("QA Group")), result)
    }

    @Test
    fun `unknown member is looked up only once`() {
        categoriesToAdd(
            ReceivedGroupVCard("QA Group", setOf("unknown")),
            ReceivedGroupVCard("Friends", setOf("unknown", "carol")),
            ReceivedGroupVCard("Family", setOf("unknown", "carol"))
        )
        assertEquals(listOf("unknown", "carol"), lookups)
    }

    @Test
    fun `group without members is ignored`() {
        val result = categoriesToAdd(ReceivedGroupVCard("Empty", emptySet()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `group without title is ignored`() {
        val result = categoriesToAdd(ReceivedGroupVCard("  ", setOf("carol")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `title is trimmed`() {
        val result = categoriesToAdd(ReceivedGroupVCard(" QA Group ", setOf("carol", "dave")))
        assertEquals(mapOf("carol" to setOf("QA Group")), result)
    }

    @Test
    fun `groups with the same title are merged`() {
        val result = categoriesToAdd(
            ReceivedGroupVCard("Friends", setOf("carol")),
            ReceivedGroupVCard("Friends", setOf("erin"))
        )
        assertEquals(mapOf("carol" to setOf("Friends"), "erin" to setOf("Friends")), result)
    }

    @Test
    fun `groups whose titles differ in case are merged with the first spelling`() {
        val result = categoriesToAdd(
            ReceivedGroupVCard("Friends", setOf("carol")),
            ReceivedGroupVCard("friends", setOf("carol", "erin"))
        )
        assertEquals(mapOf("carol" to setOf("Friends"), "erin" to setOf("Friends")), result)
    }

    @Test
    fun `member of several groups gets all titles`() {
        val result = categoriesToAdd(
            ReceivedGroupVCard("QA Group", setOf("carol")),
            ReceivedGroupVCard("Friends", setOf("carol", "dave"))
        )
        assertEquals(
            mapOf(
                "carol" to setOf("QA Group", "Friends"),
                "dave" to setOf("Friends")
            ),
            result
        )
    }

    @Test
    fun `categories are compared case-insensitively`() {
        val result = categoriesToAdd(ReceivedGroupVCard("WORK", setOf("carol")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `member of two groups with case-different existing categories`() {
        // carol has "Work"; groups "work" (same category) and "qa group" (dave has "QA Group")
        val result = categoriesToAdd(
            ReceivedGroupVCard("work", setOf("carol")),
            ReceivedGroupVCard("qa group", setOf("carol", "dave"))
        )
        assertEquals(mapOf("carol" to setOf("qa group")), result)
    }


    // groupTitle

    @Test
    fun `groupTitle() uses FN`() {
        assertEquals("QA Group", GroupVCardConversion.groupTitle(Contact(displayName = " QA Group ", familyName = "Other")))
    }

    @Test
    fun `groupTitle() falls back to N`() {
        assertEquals("QA Group", GroupVCardConversion.groupTitle(Contact(givenName = "QA", familyName = "Group")))
        assertEquals("QA Group", GroupVCardConversion.groupTitle(Contact(displayName = " ", familyName = "QA Group")))
    }

    @Test
    fun `groupTitle() without name`() {
        assertNull(GroupVCardConversion.groupTitle(Contact(group = true)))
    }


    // escaping of category names

    @Test
    fun `titles with commas and semicolons survive the CATEGORIES round trip`() = runTest {
        val titles = listOf("Smith, Jones; Co", "a\\b", "Work")
        for (version in listOf(VCardVersion.V3_0, VCardVersion.V4_0)) {
            val contact = Contact(uid = "carol", displayName = "Carol Seed")
            contact.categories += titles

            val writer = StringWriter()
            ContactWriter(contact, version, "test").writeVCard(writer)
            val parsed = ContactReader.fromVCard(VCardParser().parse(StringReader(writer.toString()))!!)

            assertEquals("vCard $version:\n$writer", titles, parsed.categories)
        }
    }

}
