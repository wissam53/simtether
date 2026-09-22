package com.simtether.relay

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Socket-level tests against a real relay: auth gating, room
 * registration/eviction, the byte splice, and peer-gone signalling.
 */
class RelayServerTest {

    private lateinit var server: RelayServer
    private var port = 0

    private class Sock(uri: URI) : WebSocketClient(uri) {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var closeCode = -1
        @Volatile var closeReason = ""
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteArray>()

        override fun onOpen(h: ServerHandshake) { opened.countDown() }
        override fun onMessage(msg: String) { texts.add(msg) }
        override fun onMessage(bytes: ByteBuffer) {
            binaries.add(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            closeCode = code; closeReason = reason; closed.countDown()
        }
        override fun onError(ex: Exception) {}
    }

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = RelayServer(port, "secret").apply { connectionLostTimeout = 60 }
        server.start()
    }

    @After
    fun stop() {
        server.stop(1000)
    }

    private fun connect(path: String): Sock {
        // The server binds on its own thread — retry until it's up.
        val deadline = System.currentTimeMillis() + 5_000
        var lastErr: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val s = Sock(URI("ws://127.0.0.1:$port$path"))
            try {
                s.connectBlocking()
                return s
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(50)
            }
        }
        throw lastErr ?: IllegalStateException("server never came up")
    }

    private fun waitFor(ms: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met within ${ms}ms", cond())
    }

    @Test
    fun `wrong token is rejected`() {
        val s = connect("/register/fp1?token=wrong")
        assertTrue(s.closed.await(3, TimeUnit.SECONDS))
        assertEquals(4001, s.closeCode)
    }

    @Test
    fun `missing token is rejected`() {
        val s = connect("/register/fp1")
        assertTrue(s.closed.await(3, TimeUnit.SECONDS))
        assertEquals(4001, s.closeCode)
    }

    @Test
    fun `register then connect splices bytes both ways`() {
        val bridge = connect("/register/room1?token=secret")
        assertTrue(bridge.opened.await(3, TimeUnit.SECONDS))
        val client = connect("/connect/room1?token=secret")
        assertTrue(client.opened.await(3, TimeUnit.SECONDS))

        client.send(ByteBuffer.wrap("ping".toByteArray()))
        waitFor { bridge.binaries.any { it.decodeToString() == "ping" } }

        bridge.send(ByteBuffer.wrap("pong".toByteArray()))
        waitFor { client.binaries.any { it.decodeToString() == "pong" } }
    }

    @Test
    fun `re-registration evicts the old bridge and its client`() {
        val b1 = connect("/register/room2?token=secret")
        assertTrue(b1.opened.await(3, TimeUnit.SECONDS))
        val c1 = connect("/connect/room2?token=secret")
        assertTrue(c1.opened.await(3, TimeUnit.SECONDS))

        val b2 = connect("/register/room2?token=secret")
        assertTrue(b2.opened.await(3, TimeUnit.SECONDS))

        assertTrue(b1.closed.await(3, TimeUnit.SECONDS))
        assertEquals("replaced", b1.closeReason)
        // The spliced client dies with the evicted bridge — a squatter
        // can't inherit a live client session.
        assertTrue(c1.closed.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `client detach signals the bridge but keeps the room`() {
        val bridge = connect("/register/room3?token=secret")
        assertTrue(bridge.opened.await(3, TimeUnit.SECONDS))
        val client = connect("/connect/room3?token=secret")
        assertTrue(client.opened.await(3, TimeUnit.SECONDS))

        client.closeBlocking()
        waitFor { bridge.texts.contains("st-peer-gone") }
        assertTrue(bridge.isOpen)  // registration survives for the next client
    }

    @Test
    fun `connect to an unregistered room is refused`() {
        val s = connect("/connect/ghost?token=secret")
        assertTrue(s.closed.await(3, TimeUnit.SECONDS))
        assertEquals(4004, s.closeCode)
    }
}
