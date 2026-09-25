package com.simtether.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetSafetyTest {

    @Test
    fun `loopback and rfc1918 are private`() {
        assertTrue(NetSafety.isPrivateHost("localhost"))
        assertTrue(NetSafety.isPrivateHost("::1"))
        assertTrue(NetSafety.isPrivateHost("127.0.0.1"))
        assertTrue(NetSafety.isPrivateHost("10.0.0.1"))
        assertTrue(NetSafety.isPrivateHost("192.168.43.1"))   // hotspot gateway
        assertTrue(NetSafety.isPrivateHost("169.254.1.1"))    // link-local
        assertTrue(NetSafety.isPrivateHost("172.16.0.1"))
        assertTrue(NetSafety.isPrivateHost("172.31.255.254"))
    }

    @Test
    fun `172 range boundary is inclusive only to 31`() {
        assertFalse(NetSafety.isPrivateHost("172.32.0.1"))
        assertFalse(NetSafety.isPrivateHost("172.15.0.1"))
        // Non-numeric second label can't smuggle past the range check.
        assertFalse(NetSafety.isPrivateHost("172.x.example.com"))
    }

    @Test
    fun `mdns-style names are private`() {
        assertTrue(NetSafety.isPrivateHost("bridge.local"))
        assertTrue(NetSafety.isPrivateHost("relay.lan"))
        assertTrue(NetSafety.isPrivateHost("nas.internal"))
    }

    @Test
    fun `ipv6 ula and link-local are private`() {
        assertTrue(NetSafety.isPrivateHost("fd00::1"))
        assertTrue(NetSafety.isPrivateHost("fc12:3456::1"))
        assertTrue(NetSafety.isPrivateHost("fe80::1"))
        assertTrue(NetSafety.isPrivateHost("febf::1"))
        // Global v6 is not.
        assertFalse(NetSafety.isPrivateHost("2606:4700::1"))
        assertFalse(NetSafety.isPrivateHost("fec0::1"))  // site-local, deprecated
    }

    @Test
    fun `public hosts and bare names are not private`() {
        assertFalse(NetSafety.isPrivateHost("relay.example.com"))
        assertFalse(NetSafety.isPrivateHost("8.8.8.8"))
        assertFalse(NetSafety.isPrivateHost("example.localhost.evil.com"))
        // Prefix lookalikes on public octets.
        assertFalse(NetSafety.isPrivateHost("10.example.com"))
        assertFalse(NetSafety.isPrivateHost("192.168.example.com"))
    }
}
