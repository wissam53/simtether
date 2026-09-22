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
        val errors = CopyOnWriteArrayList<Exception>()

        override fun onOpen(h: ServerHandshake) { opened.countDown() }
        override fun onMessage(msg: String) { texts.add(msg) }
        override fun onMessage(bytes: ByteBuffer) {
            binaries.add(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            closeCode = code; closeReason = reason; closed.countDown()
        }
        override fun onError(ex: Exception) { errors.add(ex) }
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
        // The server binds on its own thread — retry only on
        // connection-refused; a handshake refusal is a real result.
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val s = Sock(URI("ws://127.0.0.1:$port$path"))
            s.connectBlocking()
            val reached = s.errors.none { it is java.net.ConnectException }
            if (reached || System.currentTimeMillis() > deadline) return s
            Thread.sleep(50)
        }
    }

    private fun assertRejected(path: String) {
        val s = connect(path)
        // Rejected at the HTTP layer: the socket never opens, and the
        // close reason carries the real HTTP status instead of a raced
        // NEVER_CONNECTED. java_websocket reports every rejection as
        // 404 regardless of the thrown code.
        assertTrue(s.closed.await(3, TimeUnit.SECONDS))
        assertTrue("reason='${s.closeReason}'",
            s.closeReason.contains("404"))
        assertTrue(s.opened.await(200, TimeUnit.MILLISECONDS).not())
    }

    private fun waitFor(ms: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met within ${ms}ms", cond())
    }

    @Test
    fun `wrong token is rejected at handshake`() =
        assertRejected("/register/fp1?token=wrong")

    @Test
    fun `missing token is rejected at handshake`() =
        assertRejected("/register/fp1")

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
    fun `connect to an unregistered room is rejected at handshake`() =
        assertRejected("/connect/ghost?token=secret")
}
