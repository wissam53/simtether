package com.simtether.bridge.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentBoxWatcherTest {

    private val eq: (String, String) -> Boolean = { a, b -> a == b }
    private val now = 1_000_000L

    @Test
    fun `fresh row with same body and address matches`() {
        assertTrue(sentRowMatches("+15551234567", "hi", now,
            "+15551234567", "hi", now, eq))
    }

    @Test
    fun `rows just before the request match inside clock slop`() {
        // The provider stamps its own date — a fast submit can land a
        // row timestamped just before our expect() call.
        assertTrue(sentRowMatches("+1555", "hi", now,
            "+1555", "hi", now - SentBoxWatcher.DATE_SLOP_MS, eq))
        assertFalse(sentRowMatches("+1555", "hi", now,
            "+1555", "hi", now - SentBoxWatcher.DATE_SLOP_MS - 1, eq))
    }

    @Test
    fun `different body does not match`() {
        assertFalse(sentRowMatches("+1555", "hi", now,
            "+1555", "bye", now, eq))
    }

    @Test
    fun `different address does not match`() {
        assertFalse(sentRowMatches("+1555", "hi", now,
            "+1666", "hi", now, eq))
    }

    @Test
    fun `address equality goes through the injected comparator`() {
        // Production passes PhoneNumberUtils.compare so formatted and
        // raw forms of the same number confirm; a plain == would leave
        // legit sends unconfirmed.
        val digitsOnly: (String, String) -> Boolean = { a, b ->
            a.filter { it.isDigit() } == b.filter { it.isDigit() }
        }
        assertTrue(sentRowMatches("+15551234567", "hi", now,
            "1 (555) 123-4567", "hi", now, digitsOnly))
        assertFalse(sentRowMatches("+15551234567", "hi", now,
            "1 (555) 123-4567", "hi", now, eq))
    }
}
