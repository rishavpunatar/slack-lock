package com.slacklock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkAccountTest {

    @Test
    fun normaliseAcceptsAddressesAndDomains() {
        assertEquals("jane.doe@work.example.org", WorkAccount.normalise("  Jane.Doe@Work.Example.org "))
        assertEquals("@work.example.org", WorkAccount.normalise("@work.example.org"))
        assertEquals("@work.example.org", WorkAccount.normalise("Work.Example.org"))
    }

    @Test
    fun normaliseRejectsJunk() {
        assertNull(WorkAccount.normalise(""))
        assertNull(WorkAccount.normalise("jane"))
        assertNull(WorkAccount.normalise("jane@"))
        assertNull(WorkAccount.normalise("@"))
    }

    @Test
    fun exactAddressMatchesOnlyThatAccount() {
        val configured = "jane@work.example.org"
        assertTrue(WorkAccount.matches(configured, "Jane@work.example.org"))
        assertFalse(WorkAccount.matches(configured, "jane@gmail.com"))
        assertFalse(WorkAccount.matches(configured, "other@work.example.org"))
    }

    @Test
    fun domainMatchesEveryAccountOnIt() {
        val configured = "@work.example.org"
        assertTrue(WorkAccount.matches(configured, "jane@work.example.org"))
        assertTrue(WorkAccount.matches(configured, "team@work.example.org"))
        assertFalse(WorkAccount.matches(configured, "jane@gmail.com"))
        assertFalse(WorkAccount.matches(configured, "jane@notwork.example.org"))
    }

    @Test
    fun readsAccountFromGmailAccountButtonById() {
        assertEquals(
            "jane@work.example.org",
            WorkAccount.signedInAccountFromNode(
                "com.google.android.gm:id/selected_account_disc_gmail",
                "Jane Doe Jane@Work.Example.org"
            )
        )
    }

    @Test
    fun readsAccountFromSignedInDescriptionWithoutKnownId() {
        assertEquals(
            "jane@gmail.com",
            WorkAccount.signedInAccountFromNode(
                null,
                "Signed in as Jane Doe\njane@gmail.com\nAccount and settings."
            )
        )
    }

    @Test
    fun ignoresAddressesElsewhereOnScreen() {
        // An inbox row in a personal account that merely mentions the work address.
        assertNull(
            WorkAccount.signedInAccountFromNode(
                "com.google.android.gm:id/viewified_conversation_item_view",
                "Mum, Lunch, forward this to jane@work.example.org"
            )
        )
    }
}
