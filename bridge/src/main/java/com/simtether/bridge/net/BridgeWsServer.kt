package com.simtether.bridge.net

import android.util.Log
import com.simtether.shared.crypto.SecureSession
import com.simtether.shared.protocol.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * WS server on the bridge (hotspot host). First frame from a client is
 * Noise IK message 1 (token encrypted inside) → the bridge replies with
 * msg2 and both sides split into ChaCha20-Poly1305 transport ciphers.
 */
class BridgeWsServer(
    port: Int,
    private val staticKeyPair: Pair<ByteArray, ByteArray>,
    // Validator rather than a raw token — token rotation means two
    // values can be valid during the pending-window.
    private val tokenValid: (ByteArray) -> Boolean,
    // Mutual auth: the client's static key arrives inside encrypted
    // msg1. The implementation pins it on first pair (TOFU) and
    // rejects later mismatches — a stolen token alone stops being a
    // sufficient credential.
    private val clientKeyAccepted: (ByteArray) -> Boolean,
    private val onClientReady: () -> Unit,
    private val onClientDisconnected: () -> Unit = {},
    private val onCommand: (Protocol.Envelope) -> Unit,
    // Media frames (client mic → GSM uplink injection). Only called
    // for decrypted bytes tagged MEDIA_TAG — untagged plaintext is
    // still the JSON envelope path.
    private val onMedia: (ByteArray) -> Unit = {},
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    // Wildcard bind is required to survive hotspot↔WiFi topology
    // changes without a rebind loop — but the socket must never serve
    // a non-LAN peer (cellular iface included). Gate at onOpen: only
    // private/link-local/loopback remotes proceed past this point;
    // the pairing token gates the session itself.
    private fun isLanPeer(addr: java.net.InetAddress): Boolean =
        addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            // Java only maps fec0::/10 to site-local; fc00::/7 ULA too.
            (addr.address.size == 16 && (addr.address[0].toInt() and 0xFE) == 0xFC)

    init {
        // A killed instance's socket can linger in TIME_WAIT and hold
        // the port — without reuse the restarted service can't bind.
        setReuseAddr(true)
    }

    @Volatile private var client: WebSocket? = null
    @Volatile private var session: SecureSession? = null
    // Guards the (client, session) pair so a frame never matches one
    // socket with the other session's cipher — see onMessage.
    private val lock = Any()

    // Sockets past the LAN gate but not yet authenticated. Capped so a
    // LAN peer looping connects can't pile up worker threads + X25519
    // work; each entry also carries the AUTH_TIMEOUT_S reaper below.
    private val pendingAuth = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()

    // Failed-auth counter per remote (relay-spliced) link — we can't
    // close the attacker's socket (it lives on the relay), so the cap
    // drops OUR registration socket after enough garbage instead.
    private val authFails = java.util.concurrent.ConcurrentHashMap<WebSocket, Int>()

    /**
     * App-level heartbeat: a pocketed/dozing client can stall its WS
     * pings, and a half-dead TCP socket can blackhole silently for many
     * minutes before retransmit timeouts fire. A small envelope every
     * HB_SECS lets the client detect a zombie link fast — silence for
     * ~2 minutes means dead.
     */
    private val hbExecutor = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor { r -> Thread(r, "ws-hb").also { it.isDaemon = true } }

    override fun onStart() {
        Log.d(TAG, "server started on $address")
        hbExecutor.scheduleAtFixedRate({
            val s = session ?: return@scheduleAtFixedRate
            runCatching {
                send(Protocol.Envelope(UUID.randomUUID().toString(), "hb", 0, ""))
            }
        }, HB_SECS, HB_SECS, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun stop(timeout: Int) {
        hbExecutor.shutdownNow()
        super.stop(timeout)
    }

    val staticPubKey: ByteArray get() = staticKeyPair.second

    /** Registration proof needs the private half — RelayLink only. */
    val staticPrivKey: ByteArray get() = staticKeyPair.first

    /** Client socket open AND encrypted session established. */
    fun isReady() = synchronized(lock) { client?.isOpen == true && session != null }

    /**
     * (client, session) must be read as one atomic pair — adoption
     * assigns them under [lock] back-to-back, so a send that interleaves
     * between the two volatile writes would encrypt under the OLD
     * session and send on the NEW socket: guaranteed peer-side AEAD
     * failure that tears down a healthy session. Same race class as
     * the receive path fixed below.
     */
    private fun pair(): Pair<WebSocket, SecureSession>? =
        synchronized(lock) {
            val c = client; val s = session
            if (c != null && s != null) c to s else null
        }

    fun send(env: Protocol.Envelope) {
        val (c, s) = pair()
            ?: run { Log.w(TAG, "send dropped: no session (${env.type})"); return }
        Log.d(TAG, "send ${env.type} seq=${env.seq}")
        c.send(s.encrypt(Protocol.encode(env).toByteArray()))
    }

    /**
     * Downlink call audio → client. Tagged MEDIA_TAG so the peer keeps
     * it out of the JSON path. Only called after the client's hello
     * advertised the audio cap — an older client would fail the JSON
     * decode and tear the session down per the Noise AEAD rule.
     */
    fun sendMedia(pcm: ByteArray) {
        val (c, s) = pair() ?: return
        c.send(s.encrypt(byteArrayOf(Protocol.MEDIA_TAG) + pcm))
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val ip = conn.remoteSocketAddress?.address
        if (ip == null || !isLanPeer(ip)) {
            Log.w(TAG, "rejected non-LAN client: ${conn.remoteSocketAddress}")
            conn.close(4001, "lan only")
            return
        }
        pendingAuth.add(conn)
        if (pendingAuth.size > MAX_PREAUTH) {
            pendingAuth.remove(conn)
            Log.w(TAG, "pre-auth slots full — rejected ${conn.remoteSocketAddress}")
            conn.close(1013, "busy")
            return
        }
        Log.d(TAG, "client socket open: ${conn.remoteSocketAddress}")
        // The socket is NOT adopted here — an unauthenticated peer must
        // never displace the live session (a bare connect used to kick
        // the real client). Adoption happens on successful IK+token
        // auth in onMessage. Idle pre-auth sockets are reaped below.
        hbExecutor.schedule(
            {
                pendingAuth.remove(conn)
                if (conn != client) conn.close(4000, "auth timeout")
            },
            AUTH_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun onMessage(conn: WebSocket, message: ByteBuffer, remote: Boolean) {
        val bytes = ByteArray(message.remaining()).also { message.get(it) }
        // Read (client, session) as one atomic pair: adoption assigns
        // them back-to-back, and a frame landing between the two
        // volatile writes would pair the NEW socket with the OLD
        // cipher — guaranteed decrypt failure that tears down a
        // healthy session. Seen once on-device during reconnect churn.
        var s: SecureSession? = null
        val authenticated = synchronized(lock) {
            if (conn == client) {
                s = session
                s != null
            } else false
        }
        if (authenticated && s != null) {
            val plain = runCatching { s.decrypt(bytes) }.getOrElse {
                Log.e(TAG, "decrypt failed — resetting session", it)
                conn.close(1008, "decrypt")
                return
            }
            if (plain.isNotEmpty() && plain[0] == Protocol.MEDIA_TAG) {
                onMedia(plain.copyOfRange(1, plain.size))
                return
            }
            val env = runCatching {
                Protocol.decode(plain.decodeToString())
            }.getOrElse {
                // A failed AEAD tag means the nonce streams desynced —
                // per the Noise spec the session is unrecoverable and
                // must be terminated. Close the link: onClose clears
                // client/session and the peer re-handshakes. For the
                // relay path this also drops the registration socket —
                // RelayLink re-dials and the room comes back clean.
                // Log the class only — kotlinx decode errors embed a
                // raw excerpt of the payload (possible PII).
                Log.e(TAG, "decrypt/decode failed (${it.javaClass.simpleName}) — resetting session")
                conn.close(1008, "decrypt")
                return
            }
            Log.d(TAG, "recv ${env.type} seq=${env.seq}")
            onCommand(env)
        } else {
            // Unauthenticated socket — only Noise IK msg1 is legal here.
            // On the relay path a failure must NOT close the socket —
            // it IS the registration link, and dropping it would let a
            // spliced stranger kill our relay connection. But an
            // uncapped failure counter lets that stranger burn X25519
            // ops forever, so after MAX_REMOTE_AUTH_FAILS we drop the
            // registration socket anyway — RelayLink re-dials and the
            // room returns clean while the attacker has to re-/connect.
            fun fail(code: Int, why: String) {
                if (remote) {
                    val n = authFails.merge(conn, 1) { a, b -> a + b } ?: 1
                    Log.w(TAG, "remote peer failed auth: $why ($n)")
                    if (n >= MAX_REMOTE_AUTH_FAILS)
                        conn.close(4000, "auth flood")
                } else conn.close(code, why)
            }
            if (bytes.size > 2048) { fail(1009, "oversize"); return }
            val hs = runCatching {
                SecureSession.bridgeHandshake(staticKeyPair.first, bytes)
            }.getOrElse {
                fail(1002, "bad handshake")
                return
            }
            if (!tokenValid(hs.peerPayload)) {
                Log.w(TAG, "rejected client: bad pairing token")
                fail(4003, "bad token")
                return
            }
            if (hs.peerStaticPub.isEmpty() || !clientKeyAccepted(hs.peerStaticPub)) {
                // Token valid but the presenting key isn't the pinned
                // client — QR replay from another device.
                Log.w(TAG, "rejected client: unpinned key")
                fail(4004, "unpaired client")
                return
            }
            val (reply, sess) = hs.complete()
            conn.send(reply)
            // Verified — adopt now, replacing the previous client.
            pendingAuth.remove(conn)
            authFails.remove(conn)   // good auth — reset the flood counter
            synchronized(lock) {
                client?.takeIf { it != conn }?.close(1000, "replaced")
                client = conn
                session = sess
            }
            Log.d(TAG, "session established")
            onClientReady()
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) =
        onMessage(conn, message, remote = false)

    /**
     * Relay-spliced inbound (remote access): frames arrive on the
     * bridge's OUTBOUND registration socket. Adopt it as `client`
     * lazily on the first frame — the registration socket idles until
     * a remote client actually joins, so adopting on connect would
     * wipe a live LAN session for nothing. From here the bytes are
     * identical to a LAN client's: IK msg1 → session → envelopes,
     * gated by the same pairing token.
     */
    fun handleRemoteFrame(conn: WebSocket, bytes: ByteBuffer) {
        // Same auth gate as LAN — the spliced peer must complete IK +
        // pairing token before it displaces any live session.
        onMessage(conn, bytes, remote = true)
    }

    /** The relay reported the spliced client left ("st-peer-gone") or
     *  our own registration socket died — tear the session down. The
     *  registration socket itself is managed by RelayLink. */
    fun handleRemoteClose(conn: WebSocket) {
        authFails.remove(conn)
        // Clear the pair under lock — a mid-flight null outside it can
        // wipe a session adopted between the two writes.
        val ours = synchronized(lock) {
            if (conn == client) {
                client = null
                session = null
                true
            } else false
        }
        if (ours) onClientDisconnected()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // unused — binary frames only
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "client closed code=$code reason=$reason remote=$remote")
        pendingAuth.remove(conn)
        authFails.remove(conn)
        val ours = synchronized(lock) {
            if (conn == client) {
                client = null
                session = null
                true
            } else false
        }
        if (ours) onClientDisconnected()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "ws error", ex)
    }

    private companion object {
        const val TAG = "SimTether.Server"
        const val HB_SECS = 30L
        // Pre-auth sockets get one handshake window — after this an
        // unauthenticated socket is closed so it can't pile up.
        const val AUTH_TIMEOUT_S = 8L
        // Max simultaneous sockets waiting to authenticate — a connect
        // flood gets fast-closed instead of growing unbounded.
        const val MAX_PREAUTH = 8
        // Failed IK attempts tolerated on one relay registration link
        // before we drop the socket — a spliced stranger gets this many
        // X25519 ops per /connect, not an unbounded supply.
        const val MAX_REMOTE_AUTH_FAILS = 5
    }
}
