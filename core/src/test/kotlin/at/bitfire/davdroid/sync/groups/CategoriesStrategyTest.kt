/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.groups

import at.bitfire.davdroid.resource.local.LocalAddressBook
import at.bitfire.davdroid.resource.local.LocalGroup
import at.bitfire.synctools.mapping.contacts.Contact
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.mockk
import io.mockk.verify
import junit.framework.AssertionFailedError
import at.bitfire.synctools.storage.LocalStorageException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class CategoriesStrategyTest {

    @get:Rule
    val mockKRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var addressBook: LocalAddressBook

    private val strategy by lazy { CategoriesStrategy(addressBook) }

    @Before
    fun setUp() {
        /* CategoriesStrategy is called by orchestrator depending on whether the collection is read-only;
        but it doesn't check itself. */
        every { addressBook.readOnly } throws AssertionFailedError()
    }


    // resolveLocalGroupChanges

    @Test
    fun `resolveLocalGroupChanges() with deleted group marks members dirty and removes group`() = runTest {
        val group = mockk<LocalGroup>(relaxed = true)
        every { addressBook.findDeletedGroups() } returns flowOf(group)
        every { addressBook.findDirtyGroups() } returns flowOf()

        strategy.resolveLocalGroupChanges()

        verify { group.markMembersDirty() }
        verify { group.androidGroup.delete() }
    }

    @Test
    fun `resolveLocalGroupChanges() with dirty group marks members dirty and clears dirty flag`() = runTest {
        val group = mockk<LocalGroup>(relaxed = true)
        every { addressBook.findDeletedGroups() } returns flowOf()
        every { addressBook.findDirtyGroups() } returns flowOf(group)

        strategy.resolveLocalGroupChanges()

        verify { group.markMembersDirty() }
        coVerify { group.clearDirty(Optional.empty(), null) }
    }


    // consumeReceivedContact

    @Test
    fun `consumeReceivedContact() takes over group vCard and remembers its members`() {
        val group = Contact(displayName = "QA Group", group = true, members = mutableSetOf("carol-uid"))
        assertTrue(strategy.consumeReceivedContact(group))
        assertEquals(listOf(ReceivedGroupVCard("QA Group", setOf("carol-uid"))), strategy.receivedGroupVCards)
    }

    @Test
    fun `consumeReceivedContact() uses N of group vCard without FN`() {
        val group = Contact(familyName = "QA Group", group = true, members = mutableSetOf("carol-uid"))
        assertTrue(strategy.consumeReceivedContact(group))
        assertEquals(listOf(ReceivedGroupVCard("QA Group", setOf("carol-uid"))), strategy.receivedGroupVCards)
    }

    @Test
    fun `consumeReceivedContact() takes over group vCard without name`() {
        val group = Contact(group = true, members = mutableSetOf("carol-uid"))
        assertTrue(strategy.consumeReceivedContact(group))
        assertTrue(strategy.receivedGroupVCards.isEmpty())
    }

    @Test
    fun `consumeReceivedContact() doesn't take over individual contact`() {
        val contact = Contact(displayName = "Carol Seed")
        contact.categories += "QA Group"
        assertFalse(strategy.consumeReceivedContact(contact))
        assertTrue(strategy.receivedGroupVCards.isEmpty())
    }


    // verifyContactBeforeSaving

    @Test
    fun `verifyContactBeforeSaving() keeps categories of individual contact`() {
        val contact = Contact(displayName = "Carol Seed")
        contact.categories += "QA Group"
        strategy.verifyContactBeforeSaving(contact)
        assertEquals(listOf("QA Group"), contact.categories)
    }

    @Test
    fun `verifyContactBeforeSaving() strips members from individual contact`() {
        val contact = Contact(displayName = "Carol Seed", members = mutableSetOf("other-uid"))
        strategy.verifyContactBeforeSaving(contact)
        assertFalse(contact.group)
        assertTrue(contact.members.isEmpty())
    }


    // postProcess

    @Test
    fun `postProcess() without pending conversion only removes empty groups`() = runTest {
        coEvery { addressBook.isGroupVCardsConversionPending() } returns false
        strategy.consumeReceivedContact(Contact(displayName = "QA Group", group = true, members = mutableSetOf("carol-uid")))

        strategy.postProcess()

        coVerify(exactly = 0) { addressBook.applyGroupVCardsAsCategories(any()) }
        coVerify(exactly = 0) { addressBook.setGroupVCardsConversionPending(any()) }
        coVerify { addressBook.ab.deleteGroupsWithoutMembers() }
    }

    @Test
    fun `postProcess() with pending conversion applies received group vCards and finishes conversion`() = runTest {
        coEvery { addressBook.isGroupVCardsConversionPending() } returns true
        strategy.consumeReceivedContact(Contact(displayName = "QA Group", group = true, members = mutableSetOf("carol-uid")))
        strategy.consumeReceivedContact(Contact(displayName = "Empty", group = true))

        strategy.postProcess()

        coVerifyOrder {
            addressBook.applyGroupVCardsAsCategories(listOf(
                ReceivedGroupVCard("QA Group", setOf("carol-uid")),
                ReceivedGroupVCard("Empty", emptySet())
            ))
            addressBook.setGroupVCardsConversionPending(false)
            addressBook.ab.deleteGroupsWithoutMembers()
        }
    }

    @Test
    fun `postProcess() keeps conversion pending when applying fails`() = runTest {
        coEvery { addressBook.isGroupVCardsConversionPending() } returns true
        coEvery { addressBook.applyGroupVCardsAsCategories(any()) } throws LocalStorageException("test")

        assertThrows(LocalStorageException::class.java) {
            runBlocking { strategy.postProcess() }
        }
        coVerify(exactly = 0) { addressBook.setGroupVCardsConversionPending(any()) }
    }

}
