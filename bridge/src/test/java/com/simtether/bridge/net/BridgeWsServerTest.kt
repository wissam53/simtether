package com.simtether.bridge.net

import com.simtether.shared.TokenRotator
import com.simtether.shared.crypto.SecureSession
import com.simtether.shared.protocol.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless transport tests — the server state machine is where the
 * session-hijack and pre-auth-flood bugs lived, so exercise it for
 * real over loopback sockets.
 */
class BridgeWsServerTest {

    private val bridgeKey = SecureSession.generateKeyPair()
    private val clientKey = SecureSession.generateKeyPair()
    private val token = ByteArray(16) { it.toByte() }

    private var server: BridgeWsServer? = null
    private val readyLatch = CountDownLatch(1)
    private val commands = java.util.concurrent.CopyOnWriteArrayList<Protocol.Envelope>()
    private val media = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

    // TOFU pin, mirroring BridgeService.acceptClientKey.
    private val pinnedClient = AtomicReference<ByteArray?>(null)

    private fun startServer(
        tokenValid: (ByteArray) -> Boolean = { it.contentEquals(token) },
    ): Int {
        val port = ServerSocket(0).use { it.localPort }
        server = BridgeWsServer(
            port = port,
            staticKeyPair = bridgeKey,
            tokenValid = tokenValid,
            clientKeyAccepted = { pub ->
                pinnedClient.compareAndSet(null, pub)
                pinnedClient.get().contentEquals(pub)
            },
            onClientReady = { readyLatch.countDown() },
            onClientDisconnected = {},
            onCommand = { commands.add(it) },
            onMedia = { media.add(it) },
        )
        server!!.start()
        return port
    }

    @After
    fun tearDown() {
        server?.stop(1000)
        server = null
    }

    /** Minimal client socket; captures closes + received bytes. */
    private class TestClient(uri: URI) : WebSocketClient(uri) {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCode = AtomicReference(-1)
        val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        override fun onOpen(h: ServerHandshake) { opened.countDown() }
        override fun onMessage(text: String) {}
        override fun onMessage(bytes: ByteBuffer) {
            frames.add(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            closeCode.set(code)
            closed.countDown()
        }
        override fun onError(ex: Exception) {}
    }

    private fun connect(port: Int): TestClient {
        // The server may still be binding — retry with a fresh client
        // each time; a refused connect leaves the socket unusable.
        // NOTE: refused connects fire onClose too — only `opened`
        // proves the WS handshake actually completed.
        for (i in 1..60) {
            val c = TestClient(URI("ws://127.0.0.1:$port/"))
            runCatching { c.connectBlocking(500, TimeUnit.MILLISECONDS) }
            if (c.opened.count == 0L) return c
            Thread.sleep(50)
        }
        throw AssertionError("client never connected")
    }

    /** Drive a full IK handshake; returns the live client session. */
    private fun authenticate(c: TestClient, tok: ByteArray = token,
                             priv: ByteArray = clientKey.first): SecureSession {
        val hs = SecureSession.clientHandshake(bridgeKey.second, tok, priv)
        c.send(hs.outgoing)
        val reply = c.frames.poll(5, TimeUnit.SECONDS)
        assertNotNull("no handshake reply", reply)
        return hs.complete(reply)
    }

    @Test
    fun `valid handshake establishes session`() {
        val port = startServer()
        val c = connect(port)
        authenticate(c)
        assertTrue("onClientReady never fired",
            readyLatch.await(5, TimeUnit.SECONDS))
        assertTrue(server!!.isReady())
    }

    @Test
    fun `session carries encrypted envelopes both ways`() {
        val port = startServer()
        val c = connect(port)
        val session = authenticate(c)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        // client → bridge command
        val env = Protocol.Envelope("id-1", "ack", 1,
            """{"forId":"x"}""")
        c.send(session.encrypt(Protocol.encode(env).toByteArray()))
        assertTrue("command never arrived", waitFor { commands.isNotEmpty() })
        assertEquals("ack", commands.first().type)

        // bridge → client event
        server!!.send(Protocol.Envelope("id-2", "hb", 2, ""))
        val frame = c.frames.poll(5, TimeUnit.SECONDS)
        assertNotNull(frame)
        assertEquals("hb",
            Protocol.decode(session.decrypt(frame).decodeToString()).type)
    }

    /**
     * Call audio rides tagged binary frames inside the session — the
     * tag keeps raw PCM out of the JSON envelope parser so a media
     * frame can never be misread as a command, and a JSON frame can
     * never be misread as media.
     */
    @Test
    fun `tagged media frames bypass the envelope parser both ways`() {
        val port = startServer()
        val c = connect(port)
        val session = authenticate(c)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        // client → bridge: tagged frame lands on onMedia, not onCommand.
        val pcm = ByteArray(640) { it.toByte() }
        c.send(session.encrypt(byteArrayOf(Protocol.MEDIA_TAG) + pcm))
        assertTrue("media never arrived", waitFor { media.isNotEmpty() })
        assertTrue(media.first().contentEquals(pcm))
        assertTrue("media frame reached the envelope parser",
            commands.isEmpty())

        // bridge → client: sendMedia encrypts tag + payload.
        server!!.sendMedia(pcm)
        val frame = c.frames.poll(5, TimeUnit.SECONDS)
        assertNotNull(frame)
        val plain = session.decrypt(frame)
        assertEquals(Protocol.MEDIA_TAG, plain[0])
        assertTrue(plain.copyOfRange(1, plain.size).contentEquals(pcm))
    }

    @Test
    fun `bad token is rejected and never adopted`() {
        val port = startServer()
        val c = connect(port)
        val hs = SecureSession.clientHandshake(
            bridgeKey.second, ByteArray(16) { 9 }, clientKey.first)
        c.send(hs.outgoing)
        assertTrue("socket not closed", c.closed.await(5, TimeUnit.SECONDS))
        assertEquals(4003, c.closeCode.get())
        assertFalse(server!!.isReady())
    }

    @Test
    fun `garbage frame rejected without touching live session`() {
        val port = startServer()
        val real = connect(port)
        authenticate(real)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        // Attacker socket: bare connect + garbage.
        val attacker = connect(port)
        attacker.send(byteArrayOf(1, 2, 3, 4))
        assertTrue("attacker not closed",
            attacker.closed.await(5, TimeUnit.SECONDS))
        assertTrue("live session was displaced", server!!.isReady())
    }

    @Test
    fun `corrupt frame on a live session tears the link down`() {
        // Regression for the AEAD-swallow bug: a failed decrypt used to
        // be a silent no-op — the desynced session stayed "connected"
        // while dropping every frame, and heartbeats masked it from
        // the watchdog. Per the Noise spec a failed tag terminates
        // the session: the socket must close so both sides re-handshake.
        val port = startServer()
        val c = connect(port)
        authenticate(c)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        c.send(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        assertTrue("socket survived a decrypt failure",
            c.closed.await(5, TimeUnit.SECONDS))
        assertTrue("session still live after decrypt failure",
            waitFor { !server!!.isReady() })
    }

    @Test
    fun `second authenticated client replaces the first`() {
        val port = startServer()
        val first = connect(port)
        authenticate(first)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        val second = connect(port)
        authenticate(second)
        // Reconnect semantics: the old socket gets closed by adoption.
        assertTrue("first socket not replaced",
            first.closed.await(5, TimeUnit.SECONDS))
        assertTrue(server!!.isReady())
    }

    @Test
    fun `wrong client key with valid token is rejected`() {
        val port = startServer()
        val c = connect(port)
        authenticate(c)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        // Token stolen, but presented under a different static key.
        val thief = connect(port)
        val hs = SecureSession.clientHandshake(
            bridgeKey.second, token, SecureSession.generateKeyPair().first)
        thief.send(hs.outgoing)
        assertTrue("thief not closed", thief.closed.await(5, TimeUnit.SECONDS))
        assertEquals(4004, thief.closeCode.get())
        assertTrue(server!!.isReady())
    }

    @Test
    fun `pre-auth flood is capped`() {
        val port = startServer()
        val sockets = (1..8).map { connect(port) }   // fill pending slots
        val overflow = connect(port)                  // 9th → rejected
        assertTrue("overflow not closed",
            overflow.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1013, overflow.closeCode.get())
        sockets.forEach { it.close() }
    }

    @Test
    fun `token rotation window accepts current and pending`() {
        // Real rotator: rotate() makes `next` pending while `current`
        // still authenticates until the client acks.
        val current = ByteArray(16) { 1 }
        val rotator = TokenRotator(current)
        val pending = rotator.rotate()
        val port = startServer(tokenValid = { rotator.accept(it) })

        val c1 = connect(port)
        authenticate(c1, tok = current)          // old token still valid
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

        val c2 = connect(port)
        authenticate(c2, tok = pending!!)        // pending authenticates too
        assertTrue(server!!.isReady())
        assertTrue(c1.closed.await(5, TimeUnit.SECONDS))
    }

    /**
     * Relay-spliced inbound: a fake registration socket (dynamic proxy)
     * feeds IK msg1 through handleRemoteFrame — the same gate as LAN.
     */
    @Test
    fun `remote frame authenticates like a LAN socket`() {
        startServer()   // remote path needs no real inbound socket
        val sent = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val closeReason = AtomicReference<String?>(null)
        val fakeRelaySocket = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(WebSocket::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "send" -> when (val a = args?.firstOrNull()) {
                    is ByteArray -> sent.add(a)
                    is ByteBuffer -> sent.add(
                        ByteArray(a.remaining()).also { a.get(it) })
                    is String -> {}
                    else -> {}
                }
                "isOpen" -> true
                "close" -> {}
                "closeConnection" -> {}
                "getRemoteSocketAddress" -> java.net.InetSocketAddress("10.0.0.9", 9999)
                // Object methods — the server does `conn == client`
                // (equals) and hash lookups; returning null NPEs.
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake-relay-socket"
                else -> null
            }
        } as WebSocket

        val hs = SecureSession.clientHandshake(bridgeKey.second, token, clientKey.first)
        server!!.handleRemoteFrame(fakeRelaySocket, ByteBuffer.wrap(hs.outgoing))
        val reply = sent.poll(5, TimeUnit.SECONDS)
        assertNotNull("no msg2 on remote socket", reply)
        hs.complete(reply)
        assertTrue("remote session not adopted", server!!.isReady())
    }

    @Test
    fun `remote auth failure does not close the registration socket`() {
        startServer()
        val closed = AtomicReference(false)
        val fake = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(WebSocket::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "isOpen" -> true
                "close", "closeConnection" -> { closed.set(true); null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake-relay-socket"
                else -> null
            }
        } as WebSocket

        // Garbage on the registration link must NOT kill it — the
        // socket is also the room's lifeline.
        server!!.handleRemoteFrame(fake, ByteBuffer.wrap(byteArrayOf(0, 1, 2)))
        assertFalse("registration socket was closed", closed.get())
        assertFalse(server!!.isReady())
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return false
    }
}
