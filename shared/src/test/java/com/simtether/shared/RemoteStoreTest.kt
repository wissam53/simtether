package com.simtether.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteStoreTest {

    @Test
    fun `blank input is unusable`() {
        assertNull(RemoteStore.normalizeRelay(null))
        assertNull(RemoteStore.normalizeRelay(""))
        assertNull(RemoteStore.normalizeRelay("   "))
    }

    @Test
    fun `bare host-port upgrades to wss for public hosts`() {
        // No explicit scheme means the user didn't ask for cleartext —
        // safe to upgrade.
        assertEquals("wss://relay.example.com:44711",
            RemoteStore.normalizeRelay("relay.example.com:44711"))
    }

    @Test
    fun `explicit ws to a public host is refused`() {
        // Cleartext ws on the open internet puts the access token and
        // room ticket on the wire — refuse rather than silently honor.
        assertNull(RemoteStore.normalizeRelay("ws://relay.example.com:44711"))
        assertNull(RemoteStore.normalizeRelay("ws://relay.example.com"))
    }

    @Test
    fun `explicit ws is allowed to private hosts`() {
        assertEquals("ws://192.168.1.5:44711",
            RemoteStore.normalizeRelay("ws://192.168.1.5:44711"))
        assertEquals("ws://localhost:44711",
            RemoteStore.normalizeRelay("ws://localhost:44711"))
        assertEquals("ws://10.0.0.1",
            RemoteStore.normalizeRelay("ws://10.0.0.1"))
        assertEquals("ws://mybridge.lan:44711",
            RemoteStore.normalizeRelay("ws://mybridge.lan:44711"))
        assertEquals("ws://printer.local:44711",
            RemoteStore.normalizeRelay("ws://printer.local:44711"))
    }

    @Test
    fun `rfc1918 boundary on the 172 range`() {
        assertEquals("ws://172.31.0.1:44711",
            RemoteStore.normalizeRelay("ws://172.31.0.1:44711"))
        assertNull(RemoteStore.normalizeRelay("ws://172.32.0.1:44711"))
    }

    @Test
    fun `dot-port typo is rescued`() {
        // "host.443" — a '.' typed where ':' was meant — never parses
        // as host+port; rewrite it as the intended port rather than
        // dialing the literal string.
        assertEquals("wss://host:443", RemoteStore.normalizeRelay("host.443"))
        // An out-of-range digit tail is refused, not reinterpreted.
        assertNull(RemoteStore.normalizeRelay("host.99999"))
        // Explicit cleartext to the rescued public host is still refused.
        assertNull(RemoteStore.normalizeRelay("ws://host.443"))
    }

    @Test
    fun `ipv6 is bracketed on output and privacy-checked unbracketed`() {
        // ULA fd00::/7 is private — cleartext stays cleartext.
        assertEquals("ws://[fd00::1]:44711",
            RemoteStore.normalizeRelay("[fd00::1]:44711"))
        assertEquals("wss://[fd00::1]:44711",
            RemoteStore.normalizeRelay("wss://[fd00::1]:44711"))
        // Public v6: bare input upgrades, explicit ws refuses.
        assertEquals("wss://[2606:4700::1]:443",
            RemoteStore.normalizeRelay("[2606:4700::1]:443"))
        assertNull(RemoteStore.normalizeRelay("ws://[2606:4700::1]:443"))
    }

    @Test
    fun `ipv4 literal without port keeps its last octet`() {
        // The dot-port rescue must not fire on IPs — "10.0.0.1" with no
        // port is a host, not "10.0.0" on port 1.
        assertEquals("ws://10.0.0.1",
            RemoteStore.normalizeRelay("ws://10.0.0.1"))
        assertEquals("wss://8.8.8.8",
            RemoteStore.normalizeRelay("8.8.8.8"))
        assertEquals("wss://8.8.8.8:8443",
            RemoteStore.normalizeRelay("8.8.8.8:8443"))
        // A malformed IPv4 literal is refused, not mangled.
        assertNull(RemoteStore.normalizeRelay("10.0.0.256"))
    }

    @Test
    fun `foreign schemes are rejected`() {
        assertNull(RemoteStore.normalizeRelay("http://relay.example.com"))
        assertNull(RemoteStore.normalizeRelay("https://relay.example.com"))
    }

    @Test
    fun `missing or oversized port is rejected`() {
        assertNull(RemoteStore.normalizeRelay("wss://host:99999"))
        assertNull(RemoteStore.normalizeRelay("ws://:44711"))
    }

    @Test
    fun `userinfo is dropped from the canonical form`() {
        assertEquals("wss://host:443",
            RemoteStore.normalizeRelay("wss://user:pass@host:443"))
    }
}
