package com.simtether.relay

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * SimTether splice relay — the rendezvous for remote access.
 *
 * Both phones dial OUT to here (no port-forwarding, survives CGNAT):
 *
 *   bridge → ws://host:port/register/{fingerprint}
 *   client → ws://host:port/connect/{fingerprint}
 *
 * and the two sockets are spliced byte-for-byte. The Noise IK session
 * runs end-to-end THROUGH the splice — this server sees ciphertext
 * only. Keep it that way: no inspection, no frame parsing, no logs
 * beyond connection lifecycle.
 *
 * Env:
 *   PORT          listen port (default 44711)
 *   ACCESS_TOKENS required — comma-separated accepted tokens, each
 *                 presented as ?token=. A SET so rollover doesn't
 *                 strand installed clients: ship token B in the app,
 *                 run ACCESS_TOKENS=A,B until old clients age out,
 *                 then drop A. ACCESS_TOKEN (singular) still works as
 *                 a one-token shorthand.
 *                 The token is an abuse gate, not a security boundary:
 *                 registration completes only after the socket proves
 *                 possession of the bridge static key matching the
 *                 room fingerprint (ephemeral DH challenge + HMAC),
 *                 and /connect must present the room's ticket
 *                 (derived from a QR-carried secret only the bridge
 *                 and its paired client hold). A token leak degrades
 *                 to bandwidth abuse, not cross-tenant eviction —
 *                 hence the per-IP caps below. Put the relay behind
 *                 TLS termination (wss://) so neither token nor
 *                 ticket is on the wire in the clear.
 *
 * Rooms are named by the bridge's full static pubkey (b64url) — a
 * 32-bit fingerprint would be collision-mineable, letting a token
 * holder register a colliding key and evict a victim's room.
 *
 * Client detach is signalled to the bridge as a text frame
 * "st-peer-gone" — the bridge clears the dead session but keeps its
 * registration socket (and the room) alive for the next client.
 */
class RelayServer(
    port: Int,
    private val accessTokens: Set<String>,
    /**
     * Honor Fly-Client-IP / X-Forwarded-For for per-IP accounting.
     * Behind a trusted edge (the hosted Fly deployment) every socket's
     * remoteSocketAddress IS the proxy — without this, all users share
     * a handful of edge IPs and the per-IP caps throttle legit traffic
     * (or never engage). Off by default: on a direct-facing self-host
     * the headers are attacker-controlled, so the socket address wins.
     */
    private val trustProxyHeaders: Boolean =
        System.getenv("TRUST_PROXY_HEADERS") == "1",
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    constructor(port: Int, accessToken: String) : this(
        port, setOf(accessToken), System.getenv("TRUST_PROXY_HEADERS") == "1")

    private class Room {
        @Volatile var bridge: WebSocket? = null
        @Volatile var client: WebSocket? = null
        @Volatile var ticket: ByteArray? = null
        /** Epoch ms until which the media byte cap applies; 0 = off. */
        @Volatile var mediaUntil = 0L
    }

    /** In-flight key-ownership proof for a /register socket. */
    private class PendingReg(
        val fp: String,
        val ephPriv: java.security.PrivateKey,
        val ephPub: ByteArray,
        val nonce: ByteArray,
        val reaper: java.util.concurrent.ScheduledFuture<*>,
    )

    private val rooms = ConcurrentHashMap<String, Room>()
    private val roles = ConcurrentHashMap<WebSocket, Pair<String, String>>() // conn → (fp, role)
    private val pendingRegs = ConcurrentHashMap<WebSocket, PendingReg>()
    /** Live sockets per source IP — a leaked token can't open a flood. */
    private val connsPerIp = ConcurrentHashMap<String, Int>()
    /** /register handshake timestamps per source IP, sliding window. */
    private val regPerIp = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /**
     * Resolved client IP per conn — behind a trusted edge the socket
     * address is the proxy's, so the handshake captures the real IP
     * from forwarded headers (headers only exist at handshake time).
     */
    private val clientIps = ConcurrentHashMap<WebSocket, String>()
    /**
     * Per-socket forwarded-byte meter: (windowStartMs → bytes). A
     * leaked token can mint its own rooms (own keypair → own fp → own
     * ticket), which makes the relay a free anonymous byte pipe —
     * the damage is throughput, not connection count. Legit splice
     * traffic is SMS/call signaling: KB-scale bursts. Capping each
     * socket's sustained rate kills the relay's value as a proxy
     * while real use never notices.
     */
    private val byteMeters = ConcurrentHashMap<WebSocket, LongArray>() // [windowStart, bytes]
    private val totalConns = java.util.concurrent.atomic.AtomicInteger(0)
    private val reaperExec = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor { r ->
            Thread(r, "relay-reaper").also { it.isDaemon = true }
        }

    /**
     * Rejections happen here — at the HTTP layer — not with a close
     * frame in onOpen. Closing inside onOpen races the client finishing
     * its handshake (it sees NEVER_CONNECTED, not our code), and an
     * HTTP failure gives the reconnect loop a diagnosable error instead
     * of an indistinguishable drop. Note: java_websocket answers every
     * handshake rejection with HTTP 404 regardless of the exception
     * code — the codes below document intent, the client just sees a
     * failed handshake.
     */
    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket, draft: Draft, request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val desc = request.resourceDescriptor ?: ""
        val query = desc.substringAfter('?', "")
        // Header first — query strings land in TLS-terminator access
        // logs, headers don't. Query fallback keeps pre-header builds
        // working during rollover.
        // TODO(rollover): drop the query fallback once pre-header
        // builds age out — until then a crafted URL still leaks the
        // token to edge logs.
        val tok = request.getFieldValue("x-st-token")
            ?.takeIf { it.isNotBlank() }
            ?: queryParam(query, "token") ?: ""
        // Constant-time — a timing oracle on the token comparison
        // would let a scanner recover it byte-by-byte.
        val tokBytes = tok.toByteArray(Charsets.UTF_8)
        if (accessTokens.none {
                java.security.MessageDigest.isEqual(tokBytes, it.toByteArray(Charsets.UTF_8)) }) {
            System.err.println("rejected ${conn.remoteSocketAddress}: bad token")
            throw InvalidDataException(401, "bad token")
        }
        // Per-IP caps — the access token ships inside every APK, so a
        // leak is a bandwidth bill, not a breach. Limit how much of
        // one a single source can run up. (Legit use = 1 register +
        // 1 connect per phone pair, plus reconnect churn.)
        val ip = handshakeIpOf(conn, request)
        if (totalConns.get() >= MAX_TOTAL_CONNS ||
            (connsPerIp[ip] ?: 0) >= MAX_CONNS_PER_IP) {
            System.err.println("rejected ${conn.remoteSocketAddress}: conn cap")
            throw InvalidDataException(429, "conn cap")
        }
        if (desc.substringBefore('?').trim('/').substringBefore('/') == "register") {
            val now = System.currentTimeMillis()
            val window = regPerIp.getOrPut(ip) { ArrayDeque() }
            synchronized(window) {
                while (window.isNotEmpty() && now - window.first() > REG_WINDOW_MS)
                    window.removeFirst()
                if (window.isEmpty()) regPerIp.remove(ip, window) // no unbounded per-IP growth
                if (window.size >= MAX_REG_PER_WINDOW) {
                    System.err.println("rejected ${conn.remoteSocketAddress}: reg rate")
                    throw InvalidDataException(429, "reg rate")
                }
                window.addLast(now)
            }
        }
        val seg = desc.substringBefore('?').trim('/').split('/')
        if (seg.size != 2 || seg[1].isBlank() || seg[1].length > 128
            || (seg[0] != "register" && seg[0] != "connect")) {
            // Strip the query — desc carries the (valid) token.
            System.err.println("rejected ${conn.remoteSocketAddress}: bad path " +
                desc.substringBefore('?'))
            throw InvalidDataException(400, "bad path")
        }
        if (seg[0] == "register" && pendingRegs.size >= MAX_PENDING_REG) {
            throw InvalidDataException(503, "busy")
        }
        if (seg[0] == "connect") {
            val room = rooms[seg[1]]
            val b = room?.bridge
            if (b == null || !b.isOpen) throw InvalidDataException(404, "no bridge")
            // A live client slot is not evictable — previously any
            // token holder could /connect a victim's room and kick the
            // real client. Stale ghosts are reaped by the ping timeout.
            if (room.client?.isOpen == true) throw InvalidDataException(409, "room occupied")
            // Rooms registered with a ticket (all current bridges)
            // require the matching ticket — derived from a secret only
            // the bridge and its paired client hold.
            room.ticket?.let { expected ->
                val t = (request.getFieldValue("x-st-ticket")
                    ?.takeIf { it.isNotBlank() } ?: queryParam(query, "t"))
                    ?.let { runCatching { b64urlDecode(it) }.getOrNull() }
                if (t == null || !java.security.MessageDigest.isEqual(t, expected))
                    throw InvalidDataException(403, "bad ticket")
            }
        }
        // Handshake passed — remember the resolved IP so onOpen's
        // connsPerIp increment and onClose's decrement key the same
        // value the caps above just checked. Recorded only here at
        // the end: rejected conns never open, and their map entries
        // would leak (a scanner hammering bad tokens must not grow
        // this map).
        clientIps[conn] = ip
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    /** Sliding-window byte meter — true when this frame overflows the cap. */
    private fun meterExceeded(conn: WebSocket, frameBytes: Int): Boolean {
        val now = System.currentTimeMillis()
        // Rooms in media mode get the audio-rate cap: the bridge (the
        // authenticated socket) turned it on for a live call.
        val cap = roles[conn]?.let { (fp, _) -> rooms[fp] }
            ?.takeIf { it.mediaUntil > now }
            ?.let { MEDIA_BYTES_PER_WINDOW }
            ?: MAX_BYTES_PER_WINDOW
        val m = byteMeters.getOrPut(conn) { longArrayOf(now, 0) }
        synchronized(m) {
            if (now - m[0] > BYTE_WINDOW_MS) { m[0] = now; m[1] = 0 }
            m[1] += frameBytes
            return m[1] > cap
        }
    }

    private fun ipOf(conn: WebSocket): String =
        clientIps[conn] ?: conn.remoteSocketAddress?.address?.hostAddress ?: "?"

    /** Test hook — the per-IP counters are the observable proof that
     *  forwarded-IP resolution keyed the right bucket. */
    internal fun connsPerIpCount(ip: String): Int = connsPerIp[ip] ?: 0

    /**
     * The IP the caps key on. Behind a trusted edge (the hosted Fly
     * deployment) every socket's remoteSocketAddress IS the proxy —
     * all users would share a handful of edge IPs and the per-IP caps
     * would throttle legit traffic. When trustProxyHeaders is on, the
     * edge-injected client-IP header wins; on a direct-facing self-
     * host the headers are attacker-controlled so the socket address
     * stays authoritative. X-Forwarded-For is the fallback — first
     * hop only, the rest of the chain is proxy bookkeeping.
     */
    private fun handshakeIpOf(conn: WebSocket, request: ClientHandshake): String {
        if (trustProxyHeaders) {
            val real = request.getFieldValue("Fly-Client-IP")
                .ifBlank { request.getFieldValue("X-Forwarded-For") }
                .substringBefore(',').trim()
            if (real.isNotEmpty()) return real
        }
        return conn.remoteSocketAddress?.address?.hostAddress ?: "?"
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull {
            it.substringBefore('=').takeIf { k -> k == name }
                ?.let { _ -> it.substringAfter('=') }
        }

    private fun b64urlDecode(s: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(s)

    override fun onOpen(conn: WebSocket, hs: ClientHandshake) {
        // Path shape + token already validated during the handshake.
        totalConns.incrementAndGet()
        connsPerIp.merge(ipOf(conn), 1, Int::plus)
        val seg = (conn.resourceDescriptor ?: "")
            .substringBefore('?').trim('/').split('/')
        val (role, fp) = seg
        when (role) {
            "register" -> {
                // Don't adopt yet — issue a key-ownership challenge
                // first. The socket must prove it holds the static
                // private key whose SHA-256 fingerprint is the room
                // name before it can claim (or evict) the room.
                val kpg = java.security.KeyPairGenerator.getInstance("X25519")
                val kp = kpg.generateKeyPair()
                val ephPub = uToBytes(
                    (kp.public as java.security.interfaces.XECPublicKey).u)
                val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val reaper = reaperExec.schedule(
                    {
                        pendingRegs.remove(conn)
                        conn.close(4000, "proof timeout")
                    }, PROOF_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                pendingRegs[conn] = PendingReg(fp, kp.private, ephPub, nonce, reaper)
                conn.send(ephPub + nonce)
            }
            "connect" -> {
                val room = rooms[fp]
                val b = room?.bridge
                if (b == null || !b.isOpen) {
                    // Bridge vanished between handshake and onOpen.
                    conn.close(4004, "no bridge")
                    return
                }
                room.client?.close(1000, "replaced")
                room.client = conn
                conn.setAttachment(b)
                b.setAttachment(conn)
                roles[conn] = fp to role
                println("room $fp: client spliced")
            }
            else -> conn.close(1008, "bad path")
        }
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        val pending = pendingRegs.remove(conn)
        if (pending != null) {
            pending.reaper.cancel(false)
            verifyRegistration(conn, pending, bytes)
            return
        }
        val peer = conn.getAttachment<WebSocket>() ?: return
        if (meterExceeded(conn, bytes.remaining())) {
            println("rate exceeded: ${conn.remoteSocketAddress} — closed")
            conn.close(1008, "rate")
            return
        }
        peer.send(bytes)
    }

    /**
     * Verify a register proof: staticPub(32) || ticket(32) || mac(32).
     * mac = HMAC-SHA256(key=SHA256("st-relay-reg-v1"||DH), msg=
     * "register"||fp||ephPub||nonce||staticPub||ticket). Only the
     * holder of the static private key matching the room fingerprint
     * can produce the DH shared secret — a token holder who isn't the
     * real bridge can no longer claim the room.
     */
    private fun verifyRegistration(conn: WebSocket, reg: PendingReg, msg: ByteBuffer) {
        val bytes = ByteArray(msg.remaining()).also { msg.get(it) }
        fun reject(why: String) {
            println("room ${reg.fp}: proof rejected ($why)")
            conn.close(4003, why)
        }
        if (bytes.size != PROOF_LEN) return reject("bad proof")
        val staticPub = bytes.copyOfRange(0, 32)
        val ticket = bytes.copyOfRange(32, 64)
        val mac = bytes.copyOfRange(64, 96)
        if (roomId(staticPub) != reg.fp) return reject("room mismatch")
        val shared = runCatching {
            javax.crypto.KeyAgreement.getInstance("X25519").run {
                init(reg.ephPriv)
                doPhase(x25519PublicKey(staticPub), true)
                generateSecret()
            }
        }.getOrNull() ?: return reject("bad pubkey")
        val macKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest("st-relay-reg-v1".toByteArray() + shared)
        val expected = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(macKey, "HmacSHA256"))
            doFinal("register".toByteArray() + reg.fp.toByteArray() +
                reg.ephPub + reg.nonce + staticPub + ticket)
        }
        if (!java.security.MessageDigest.isEqual(expected, mac)) return reject("bad mac")
        // Verified — this socket owns the room.
        val room = rooms.getOrPut(reg.fp) { Room() }
        room.bridge?.takeIf { it != conn }?.close(1000, "replaced")
        room.client?.close(1000, "bridge replaced")
        room.client = null
        room.bridge = conn
        room.ticket = ticket
        roles[conn] = reg.fp to "register"
        println("room ${reg.fp}: bridge registered (proof ok)")
    }

    /** Same derivation as Identity.roomId on the app side: b64url of
     *  the raw pubkey — the full key, not a 32-bit fingerprint. */
    private fun roomId(staticPub: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(staticPub)

    private fun x25519PublicKey(raw: ByteArray): java.security.PublicKey {
        // RFC 7748 u-coordinate is little-endian; BigInteger wants BE.
        val u = java.math.BigInteger(1, raw.reversedArray())
        return java.security.KeyFactory.getInstance("X25519").generatePublic(
            java.security.spec.XECPublicKeySpec(
                java.security.spec.NamedParameterSpec.X25519, u))
    }

    /** XECPublicKey.u is a big-endian BigInteger; the wire wants 32B LE. */
    private fun uToBytes(u: java.math.BigInteger): ByteArray {
        val be = u.toByteArray()
            .let { if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val padded = ByteArray(32)
        System.arraycopy(be, 0, padded, 32 - be.size, be.size)
        return padded.reversedArray()
    }

    // Text frames are control, not payload — the app protocol is
    // binary-only, so nothing user-originated ever reaches this path.
    // The "st-" prefix is a reserved relay-control namespace and is
    // never forwarded; everything else splices through, metered.
    override fun onMessage(conn: WebSocket, text: String) {
        if (text.startsWith("st-")) { handleControl(conn, text); return }
        val peer = conn.getAttachment<WebSocket>() ?: return
        if (meterExceeded(conn, text.toByteArray(Charsets.UTF_8).size)) {
            println("rate exceeded: ${conn.remoteSocketAddress} — closed")
            conn.close(1008, "rate")
            return
        }
        peer.send(text)
    }

    /**
     * Bridge-only relay commands (the register socket is the proven
     * room owner; the client socket can't touch room policy):
     *   st-media on   raise the byte cap for a live call
     *   st-media off  return to the signaling cap
     * Media mode auto-expires — a crashed bridge can't leave its room
     * uncapped forever; a long call re-sends "st-media on" to renew.
     */
    private fun handleControl(conn: WebSocket, text: String) {
        val (fp, role) = roles[conn] ?: return
        if (role != "register") return
        when (text) {
            "st-media on" -> {
                rooms[fp]?.mediaUntil =
                    System.currentTimeMillis() + MEDIA_SESSION_MS
                println("room $fp: media on")
            }
            "st-media off" -> {
                rooms[fp]?.mediaUntil = 0L
                println("room $fp: media off")
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        pendingRegs.remove(conn)?.reaper?.cancel(false)
        byteMeters.remove(conn)
        totalConns.decrementAndGet()
        connsPerIp.computeIfPresent(ipOf(conn)) { _, n -> if (n > 1) n - 1 else null }
        clientIps.remove(conn)
        val peer = conn.getAttachment<WebSocket>()
        conn.setAttachment<WebSocket?>(null)
        val r = roles.remove(conn)
        if (r == null) {
            // Unspliced or unregistered socket — nothing to clean up.
            peer?.setAttachment<WebSocket?>(null)
            return
        }
        val (fp, role) = r
        if (role == "register") {
            val room = rooms[fp]
            if (room?.bridge == conn) {
                rooms.remove(fp, room)
                peer?.setAttachment<WebSocket?>(null)
                peer?.close(1000, "bridge gone")
                println("room $fp: bridge gone — closed")
            }
        } else {
            // Client left: detach but keep the room — the bridge's
            // registration socket stays live for the next client.
            peer?.setAttachment<WebSocket?>(null)
            peer?.send("st-peer-gone")
            rooms[fp]?.let { if (it.client == conn) it.client = null }
            println("room $fp: client detached")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        System.err.println("relay error: ${ex.message}")
    }

    override fun onStart() {
        println("simtether relay listening on $address")
    }

    companion object {
        private const val PROOF_LEN = 96         // staticPub||ticket||mac
        private const val PROOF_TIMEOUT_S = 10L  // idle challenge sockets reaped
        private const val MAX_PENDING_REG = 32   // challenge flood cap
        // Per-IP caps are CGNAT-tolerant: mobile carriers concentrate
        // hundreds of subscribers behind one egress IP, so IP is a
        // weak proxy for "abuser" — they only stop single-source
        // floods. The byte meter above is the real cost bound.
        private const val MAX_CONNS_PER_IP = 64
        private const val MAX_TOTAL_CONNS = 2048  // ~1k pairs on a 256MB machine
        private const val REG_WINDOW_MS = 60_000L
        private const val MAX_REG_PER_WINDOW = 20 // reg floods burn memory, not just bw
        private const val BYTE_WINDOW_MS = 60_000L
        // ~2KB/s sustained per socket — hundreds of SMS/minute of
        // headroom, useless as a proxy pipe.
        private const val MAX_BYTES_PER_WINDOW = 128 * 1024L
        // ~34KB/s sustained per socket while a call is live — Opus
        // wideband (~32kbps) plus WS/jitter overhead. Media mode is
        // bridge-authorized and auto-expires.
        private const val MEDIA_BYTES_PER_WINDOW = 2L * 1024 * 1024
        private const val MEDIA_SESSION_MS = 2 * 60 * 60 * 1000L // 2h max call

        @JvmStatic
        fun main(args: Array<String>) {
            val port = System.getenv("PORT")?.toIntOrNull() ?: 44711
            val tokens = (System.getenv("ACCESS_TOKENS") ?: System.getenv("ACCESS_TOKEN"))
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    System.err.println(
                        "ACCESS_TOKENS is required — refusing to run an open relay")
                    kotlin.system.exitProcess(1)
                }
            RelayServer(port, tokens.toSet()).apply {
                // WS ping/liveness — reaps half-dead sockets so rooms
                // don't stay registered to ghosts.
                connectionLostTimeout = 60
            }.start()
        }
    }
}
