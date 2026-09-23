package com.simtether.relay

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Socket-level tests against a real relay: token gating, the
 * key-ownership proof on /register, ticketed /connect, the byte
 * splice, and peer-gone signalling.
 *
 * The proof is replayed here with JDK crypto — intentionally NOT via
 * the app's RelayProof helper, so a client-side derivation bug can't
 * be masked by both sides sharing the same wrong code.
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
        val binaries = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
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

    /** A throwaway bridge identity — X25519 pair + room fingerprint. */
    private class BridgeId(
        val priv: PrivateKey,
        val pub: ByteArray,
        val fp: String,
        val relaySecret: ByteArray,
    ) {
        /** Ticket the paired client would derive — HMAC(secret,
         *  "st-ticket"||fp), same contract as shared/RelayProof. */
        fun ticket(): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(relaySecret, "HmacSHA256"))
            doFinal("st-ticket".toByteArray() + fp.toByteArray())
        }
    }

    private fun newBridgeId(): BridgeId {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val u = (kp.public as java.security.interfaces.XECPublicKey).u
        val be = u.toByteArray()
            .let { if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val pub = ByteArray(32)
        System.arraycopy(be, 0, pub, 32 - be.size, be.size)
        val le = pub.reversedArray()   // RFC 7748: wire u is little-endian
        // Same room-id derivation as production: b64url of the raw
        // pubkey (Identity.roomId) — NOT the old 8-hex fingerprint.
        val fp = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(le)
        return BridgeId(kp.private, le, fp,
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
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

    /**
     * Full registration: connect, read the ephPub||nonce challenge,
     * answer with staticPub||ticket||mac — mirrors RelayProof.respond.
     */
    private fun register(id: BridgeId): Sock {
        val s = connect("/register/${id.fp}?token=secret")
        assertTrue(s.opened.await(3, TimeUnit.SECONDS))
        val challenge = s.binaries.poll(3, TimeUnit.SECONDS)
        checkNotNull(challenge) { "no challenge frame" }
        assertEquals(64, challenge.size)
        val ephPub = challenge.copyOfRange(0, 32)
        val nonce = challenge.copyOfRange(32, 64)

        val shared = javax.crypto.KeyAgreement.getInstance("X25519").run {
            init(id.priv)
            doPhase(KeyFactory.getInstance("X25519").generatePublic(
                XECPublicKeySpec(NamedParameterSpec.X25519,
                    BigInteger(1, ephPub.reversedArray()))), true)
            generateSecret()
        }
        val macKey = MessageDigest.getInstance("SHA-256")
            .digest("st-relay-reg-v1".toByteArray() + shared)
        val ticket = id.ticket()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256"))
            doFinal("register".toByteArray() + id.fp.toByteArray() +
                ephPub + nonce + id.pub + ticket)
        }
        s.send(id.pub + ticket + mac)
        return s
    }

    private fun connectClient(id: BridgeId, ticket: ByteArray? = id.ticket()): Sock {
        val t = ticket?.let {
            "&t=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        } ?: ""
        return connect("/connect/${id.fp}?token=secret$t")
    }

    /** A /connect that opens is the observable "registered" signal. */
    private fun awaitRegistered(id: BridgeId): Sock {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val c = connectClient(id)
            if (c.opened.await(0, TimeUnit.MILLISECONDS) || c.isOpen) return c
            assertTrue("registration never completed",
                System.currentTimeMillis() < deadline)
            Thread.sleep(60)
        }
    }

    @Test
    fun `wrong token is rejected at handshake`() =
        assertRejected("/register/fp1?token=wrong")

    @Test
    fun `missing token is rejected at handshake`() =
        assertRejected("/register/fp1")

    @Test
    fun `register then connect splices bytes both ways`() {
        val id = newBridgeId()
        val bridge = register(id)
        val client = awaitRegistered(id)

        client.send(ByteBuffer.wrap("ping".toByteArray()))
        waitFor { bridge.binaries.any { it.decodeToString() == "ping" } }

        bridge.send(ByteBuffer.wrap("pong".toByteArray()))
        waitFor { client.binaries.any { it.decodeToString() == "pong" } }
    }

    @Test
    fun `re-registration with proof evicts the old bridge and its client`() {
        val id = newBridgeId()
        val b1 = register(id)
        val c1 = awaitRegistered(id)

        val b2 = register(id)
        waitFor { b1.closed.count == 0L }   // old bridge evicted on new proof
        assertEquals("replaced", b1.closeReason)
        // The spliced client dies with the evicted bridge — a squatter
        // can't inherit a live client session.
        waitFor { c1.closed.count == 0L }

        // New registration still serves connects.
        awaitRegistered(id)
        assertTrue(b2.isOpen)
    }

    @Test
    fun `client detach signals the bridge but keeps the room`() {
        val id = newBridgeId()
        val bridge = register(id)
        val client = awaitRegistered(id)

        client.closeBlocking()
        waitFor { bridge.texts.contains("st-peer-gone") }
        assertTrue(bridge.isOpen)  // registration survives for the next client
    }

    @Test
    fun `connect to an unregistered room is rejected at handshake`() =
        assertRejected("/connect/ghost?token=secret")

    // ---- New proof-path coverage -------------------------------------

    @Test
    fun `unanswered challenge never claims the room`() {
        val id = newBridgeId()
        val s = connect("/register/${id.fp}?token=secret")
        assertTrue(s.opened.await(3, TimeUnit.SECONDS))
        // Socket opens and gets a challenge, but without the proof the
        // room stays empty — /connect still sees "no bridge".
        assertTrue(s.binaries.poll(3, TimeUnit.SECONDS) != null)
        assertRejected("/connect/${id.fp}?token=secret&t=x")
    }

    @Test
    fun `wrong-key proof is rejected`() {
        val id = newBridgeId()
        val thief = newBridgeId()   // different key — can't prove id.fp
        val s = connect("/register/${id.fp}?token=secret")
        assertTrue(s.opened.await(3, TimeUnit.SECONDS))
        val challenge = s.binaries.poll(3, TimeUnit.SECONDS)!!
        // Answer with the thief's pubkey — fingerprint won't match.
        s.send(thief.pub + thief.ticket() + ByteArray(32))
        assertTrue(s.closed.await(3, TimeUnit.SECONDS))
        assertEquals(4003, s.closeCode)
        assertRejected("/connect/${id.fp}?token=secret")
    }

    @Test
    fun `connect without ticket is rejected on a ticketed room`() {
        val id = newBridgeId()
        register(id)
        // Confirm the room is live with a ticketed connect, then free
        // the slot so the rejection below is the ticket check — not
        // the occupied-slot check.
        awaitRegistered(id).closeBlocking()
        Thread.sleep(200)
        assertRejected("/connect/${id.fp}?token=secret")
    }

    @Test
    fun `open client slot is not evictable`() {
        val id = newBridgeId()
        register(id)
        val c1 = awaitRegistered(id)
        assertTrue(c1.isOpen)
        // Second client is refused while the first holds the slot.
        assertRejected("/connect/${id.fp}?token=secret&t=" +
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.ticket()))
        assertTrue(c1.isOpen)
    }

    // ---- Media mode / byte caps --------------------------------------

    @Test
    fun `st-media control raises the cap and is never forwarded`() {
        val id = newBridgeId()
        val bridge = register(id)
        val client = awaitRegistered(id)

        bridge.send("st-media on")
        // Control frame is consumed by the relay, not spliced.
        Thread.sleep(300)
        assertTrue(client.texts.none { it == "st-media on" })

        // ~1.3MB through the splice — past the signaling cap, well
        // under the media cap. Send in chunks like a codec would.
        val chunk = ByteArray(16 * 1024)
        repeat(84) { client.send(ByteBuffer.wrap(chunk)) }
        Thread.sleep(500)
        assertTrue("media-mode socket closed", client.isOpen)
        assertTrue(bridge.binaries.sumOf { it.size } > 1_200_000)

        bridge.send("st-media off")
    }

    @Test
    fun `signaling cap closes a socket flooding without media`() {
        val id = newBridgeId()
        register(id)
        val client = awaitRegistered(id)

        val chunk = ByteArray(16 * 1024)
        repeat(10) { client.send(ByteBuffer.wrap(chunk)) } // 160KB > 128KB cap
        waitFor { client.closed.count == 0L }
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `client socket cannot enable media mode`() {
        val id = newBridgeId()
        register(id)
        val client = awaitRegistered(id)

        client.send("st-media on")  // ignored — only the register socket owns policy
        Thread.sleep(300)
        val chunk = ByteArray(16 * 1024)
        repeat(10) { client.send(ByteBuffer.wrap(chunk)) }
        waitFor { client.closed.count == 0L }
        assertEquals(1008, client.closeCode)
    }
}
