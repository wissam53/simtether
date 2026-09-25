package com.simtether.bridge

import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControllerTest {

    @Test
    fun `plain and formatted numbers are safe`() {
        assertTrue(CallController.isSafeNumber("5551234"))
        assertTrue(CallController.isSafeNumber("+15551234567"))
        // Formatting is stripped — contacts carry these characters.
        assertTrue(CallController.isSafeNumber("(555) 123-4567"))
        assertTrue(CallController.isSafeNumber("+1 (555) 123.4567"))
        assertTrue(CallController.isSafeNumber("555 1234"))
    }

    @Test
    fun `MMI and service-code shapes are never safe numbers`() {
        // The point of the whitelist: *21*num# enables SIM-level call
        // forwarding — invisible to the client and surviving re-pair.
        assertFalse(CallController.isSafeNumber("*123#"))
        assertFalse(CallController.isSafeNumber("*21*5551234#"))
        assertFalse(CallController.isSafeNumber("*#06#"))
        // ';' and ',' are dial pause/wait, not formatting — never stripped.
        assertFalse(CallController.isSafeNumber("555;12"))
        assertFalse(CallController.isSafeNumber("555,12"))
    }

    @Test
    fun `size bounds hold`() {
        assertFalse(CallController.isSafeNumber(""))
        assertFalse(CallController.isSafeNumber("+"))
        assertFalse(CallController.isSafeNumber("1"))
        assertTrue(CallController.isSafeNumber("12"))
        assertTrue(CallController.isSafeNumber("+" + "1".repeat(20)))
        assertFalse(CallController.isSafeNumber("1".repeat(21)))
        assertFalse(CallController.isSafeNumber("++1555"))
        assertFalse(CallController.isSafeNumber("555+1"))
    }

    @Test
    fun `no string passes both the safe-number and service-code gates`() {
        // Disjointness is the security invariant: a wire string that
        // slips one gate must die at the other — a dial-path number
        // must never also parse as a carrier code.
        val cases = listOf(
            "5551234", "+15551234567", "(555) 123-4567",
            "*123#", "*#06#", "*21*5551234#", "**61*00441234567*20#",
            "*#*#4636#*#*", "555;12", "*21*+15551234567#", "12#34",
            "", "+", "1",
        )
        for (s in cases) {
            assertFalse("both gates accepted: $s",
                CallController.isSafeNumber(s) && Protocol.isServiceCode(s))
        }
    }
}
