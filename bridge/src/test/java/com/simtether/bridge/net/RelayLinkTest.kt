package com.simtether.bridge.net

import com.simtether.relay.RelayServer
import com.simtether.shared.Identity
import com.simtether.shared.RelayProof
import com.simtether.shared.crypto.SecureSession
import com.simtether.shared.protocol.Protocol
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end remote path on the JVM: real RelayServer + real
 * RelayLink + real BridgeWsServer. The registration proof is the one
 * place where a bridge/relay mismatch silently breaks 100% of remote
 * access — so both halves run their production code here, not a mock.
 */
class RelayLinkTest {

    private val bridgeKey = SecureSession.generateKeyPair()
    private val clientKey = SecureSession.generateKeyPair()
    private val token = ByteArray(16) { it.toByte() }
    private val relaySecret = ByteArray(32) { (it * 3).toByte() }
    private val fp = Identity.fingerprint(bridgeKey.second)

    private var relay: RelayServer? = null
    private var server: BridgeWsServer? = null
    private var link: RelayLink? = null
    private val readyLatch = CountDownLatch(1)
    private val commands = java.util.concurrent.CopyOnWriteArrayList<Protocol.Envelope>()

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Sock(uri: URI) : WebSocketClient(uri) {
        val opened = CountDownLatch(1)
        val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        override fun onOpen(h: ServerHandshake) { opened.countDown() }
        override fun onMessage(text: String) {}
        override fun onMessage(bytes: ByteBuffer) {
            frames.add(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) {}
        override fun onError(ex: Exception) {}
    }

    @After
    fun tearDown() {
        link?.stop()
        server?.stop(1000)
        relay?.stop(1000)
    }

    @Test
    fun `registration proof then a full IK session through the splice`() {
        val relayPort = freePort()
        relay = RelayServer(relayPort, "secret")
            .apply { connectionLostTimeout = 60 }
        relay!!.start()

        server = BridgeWsServer(
            port = freePort(),           // LAN listener unused here
            staticKeyPair = bridgeKey,
            tokenValid = { it.contentEquals(token) },
            clientKeyAccepted = { true },  // TOFU — not under test here
            onClientReady = { readyLatch.countDown() },
            onClientDisconnected = {},
            onCommand = { commands.add(it) },
        ).also { it.start() }

        link = RelayLink(
            server!!, "127.0.0.1:$relayPort", fp, "secret",
            staticPriv = bridgeKey.first,
            staticPub = bridgeKey.second,
            relaySecret = relaySecret,
        ).also { it.start() }

        // Registration is async (challenge → proof → adopt): a
        // ticketed /connect only opens once the room is live.
        val ticket = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(RelayProof.ticket(relaySecret, fp))
        val client = AtomicReference<Sock>()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val c = Sock(URI("ws://127.0.0.1:$relayPort/connect/$fp?token=secret&t=$ticket"))
            runCatching { c.connectBlocking(1, TimeUnit.SECONDS) }
            if (c.opened.count == 0L) { client.set(c); break }
            Thread.sleep(100)
        }
        assertNotNull("relay never registered the room", client.get())

        // IK through the splice — the same bytes as a LAN session.
        val hs = SecureSession.clientHandshake(bridgeKey.second, token, clientKey.first)
        client.get().send(hs.outgoing)
        val reply = client.get().frames.poll(5, TimeUnit.SECONDS)
        assertNotNull("no msg2 through splice", reply)
        val session = hs.complete(reply)
        assertTrue("onClientReady never fired over relay",
            readyLatch.await(5, TimeUnit.SECONDS))

        // And a real command makes it through the encrypted session.
        val env = Protocol.Envelope("e1", "ack", 1, """{"forId":"x"}""")
        client.get().send(session.encrypt(Protocol.encode(env).toByteArray()))
        val t0 = System.currentTimeMillis()
        while (commands.isEmpty() && System.currentTimeMillis() - t0 < 5000)
            Thread.sleep(20)
        assertEquals("ack", commands.firstOrNull()?.type)
    }
}
