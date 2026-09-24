package com.simtether.bridge.net

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * Outbound link to a splice relay — the remote-access half of the
 * bridge. Dials OUT (no port-forward, survives CGNAT) and registers
 * the room named by our key fingerprint. When a remote client joins
 * the room, the relay splices its socket onto ours and inbound frames
 * feed the same session pipeline as a LAN client.
 *
 * The registration socket idles (no frames) until a client actually
 * splices in — BridgeWsServer adopts it lazily on the first frame.
 */
class RelayLink(
    private val server: BridgeWsServer,
    private val addr: String,          // "host:port" or "wss://host:port"
    private val roomId: String,        // full-pubkey room name (Identity.roomId)
    private val token: String?,
    // Registration is a key-ownership proof now: the relay challenges
    // with an ephemeral DH key and we answer with a MAC only the
    // holder of this static private key can produce. Without it, any
    // relay-token holder could claim (and evict) our room.
    private val staticPriv: ByteArray,
    private val staticPub: ByteArray,
    private val relaySecret: ByteArray,
) {
    @Volatile private var stopped = false
    @Volatile private var socket: WebSocketClient? = null
    // The first binary frame on a fresh socket is the relay's proof
    // challenge — everything after is spliced client traffic.
    @Volatile private var proofDone = false
    /**
     * Desired media-mode state, re-applied after every re-registration:
     * the relay's room dies with its register socket, so a reconnect
     * must re-send "st-media on" or a live call drops to the 2KB/s
     * signaling cap mid-call.
     */
    @Volatile private var mediaWanted = false

    /**
     * Raise/lower the relay's per-socket byte cap for a live call —
     * Opus wideband needs ~34KB/s sustained; signaling mode is 2KB/s.
     * Safe to call before registration completes; the flag is
     * re-sent once the new socket proves ownership.
     */
    fun setMediaMode(on: Boolean) {
        mediaWanted = on
        val s = socket
        if (s != null && s.isOpen && proofDone)
            runCatching { s.send(if (on) "st-media on" else "st-media off") }
    }
    private var backoffMs = 2_000L
    private val thread = Thread({ loop() }, "relay-link").also { it.isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        thread.interrupt()
    }

    private fun loop() {
        while (!stopped) {
            // wss:// when the relay sits behind TLS termination. The
            // token rides an upgrade HEADER, not the URL — query strings
            // land in TLS-terminator access logs (Fly.io edge), headers
            // don't.
            val scheme = if (addr.startsWith("wss://")) "wss" else "ws"
            val headers = token?.let { mapOf("x-st-token" to it) } ?: emptyMap()
            // connectBlocking() returns when the handshake OPENS — hold
            // the loop on this latch until the socket actually closes,
            // or the next iteration re-registers and the relay replaces
            // its own still-open predecessor every backoff cycle.
            val closed = java.util.concurrent.CountDownLatch(1)
            val c = object : WebSocketClient(
                URI("$scheme://${addr.substringAfter("://")}/register/$roomId"),
                org.java_websocket.drafts.Draft_6455(),
                headers) {
                override fun onOpen(h: ServerHandshake) {
                    Log.d(TAG, "registered room on relay $addr")
                    backoffMs = 2_000L
                }

                override fun onMessage(message: String) {
                    // Relay control frame — the spliced client left.
                    // Clear the session but keep this registration.
                    if (message == "st-peer-gone") server.handleRemoteClose(this)
                }

                override fun onMessage(bytes: ByteBuffer) {
                    if (!proofDone) {
                        // Registration challenge: ephPub||nonce → reply
                        // with staticPub||ticket||mac proving the key.
                        val c = ByteArray(bytes.remaining()).also { bytes.get(it) }
                        val proof = com.simtether.shared.RelayProof.respond(
                            staticPriv, staticPub, c,
                            com.simtether.shared.RelayProof.ticket(
                                relaySecret, roomId))
                        if (proof == null) {
                            Log.w(TAG, "malformed relay challenge (${c.size}B)")
                            close()
                            return
                        }
                        proofDone = true
                        send(proof)
                        // Re-assert media mode on the fresh room —
                        // the relay reset it when the old register
                        // socket died.
                        if (mediaWanted) send("st-media on")
                        return
                    }
                    server.handleRemoteFrame(this, bytes)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    // A refused handshake arrives here (not onError) —
                    // a config error, not a blip. Say so, or a bad
                    // ACCESS_TOKEN looks like a silent reconnect loop.
                    if (reason.startsWith("Invalid status code"))
                        Log.w(TAG, "relay rejected handshake (check ACCESS_TOKEN): $reason")
                    else
                        Log.d(TAG, "relay socket closed code=$code reason=$reason")
                    server.handleRemoteClose(this)
                    closed.countDown()
                }

                override fun onError(ex: Exception) {
                    Log.w(TAG, "relay socket error: ${ex.message}")
                }
            }
            // Ping the relay when idle — keeps NAT conntrack entries
            // warm and reaps half-dead sockets.
            c.connectionLostTimeout = 60
            proofDone = false
            socket = c
            // connectBlocking returns false (or throws) on a refused
            // dial — onClose may never fire in that case, so awaiting
            // the latch unconditionally would hang the retry loop.
            val opened = runCatching { c.connectBlocking() }.getOrDefault(false)
            if (stopped) break
            if (opened) {
                try {
                    // Park until the registration socket dies — a healthy
                    // link sits here indefinitely, a failed dial has
                    // already counted the latch down via onClose.
                    closed.await()
                } catch (_: InterruptedException) {
                    // stop() interrupted the wait — exit, don't crash.
                    break
                }
            } else {
                Log.d(TAG, "relay dial failed — backing off")
            }
            socket = null
            if (stopped) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                // stop() interrupts the backoff — exit, don't crash.
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        socket = null
    }

    private companion object {
        const val TAG = "SimTether.Relay"
    }
}
