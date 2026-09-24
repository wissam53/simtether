package com.simtether.client.net

import com.simtether.shared.crypto.SecureSession
import com.simtether.shared.protocol.Protocol
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Reconnecting WS client → bridge server. On connect it sends Noise
 * IK message 1 (with the pairing token as encrypted payload); the
 * session goes live once the bridge's msg2 reply is verified, then
 * all traffic is encrypted envelopes.
 */
/** A located bridge: address + the Network that can actually reach it.
 *  socketFactory pins traffic to that network — without it Android
 *  sends the connection out the *default* network (usually cellular)
 *  and the LAN address is unreachable. */
class ResolvedTarget(
    val host: String,
    val port: Int,
    val socketFactory: javax.net.SocketFactory?,
    // Relay targets connect to /connect/{fp} on the rendezvous —
    // LAN targets hit the bridge's root path.
    val path: String = "/",
    val viaRelay: Boolean = false,
    // "ws" on the LAN; "wss" when the relay sits behind TLS termination.
    val scheme: String = "ws",
    // Upgrade headers — relay token/ticket ride here, not the URL:
    // query strings land in TLS-terminator access logs, headers don't.
    val headers: Map<String, String> = emptyMap(),
)

class BridgeWsClient(
    private val targetProvider: () -> ResolvedTarget?,
    private val bridgeStaticPub: ByteArray,
    // Provider, not a snapshot — the bridge rotates the token every
    // session, so each connect attempt must read the latest stored one.
    private val pairingTokenProvider: () -> ByteArray,
    // The client's persistent identity key — IK sends its public half
    // in encrypted msg1 and the bridge pins it. A stolen token without
    // this key fails the pin check.
    private val clientStaticPriv: ByteArray,
    private val onEvent: (Protocol.Envelope) -> Unit,
    private val onState: (Boolean) -> Unit = {},
    // Reports which transport the live session rides (LAN vs relay) —
    // lets the UI label the link honestly.
    private val onTransport: (Boolean) -> Unit = {},
    // Fired when the bridge rejects our token (4003) — the pairing was
    // revoked/rotated; reconnecting is futile until the user re-pairs.
    private val onRevoked: () -> Unit = {},
    // Bridge downlink audio — decrypted frames tagged MEDIA_TAG land
    // here instead of the envelope path.
    private val onMedia: (ByteArray) -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        // No WS-level ping: the protocol heartbeats app-level (bridge
        // hb every 30s + 120s zombie watchdog below). A 15s OkHttp ping
        // fired on a janky bridge — pongs stall behind scheduler/GC
        // stalls and the watchdog tears down a healthy session every
        // ~15s, churning mDNS + Noise + token rotation. OkHttp still
        // answers inbound pings automatically.
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val seq = AtomicLong(0)
    private val TAG = "SimTether.Client"
    private val MAX_OUTBOX = 100
    // Mutated on OkHttp dispatcher threads, read by sendCommand()
    // callers on the UI thread — volatile or stale reads drop commands.
    @Volatile private var session: SecureSession? = null
    @Volatile private var pendingHandshake: SecureSession.ClientHandshake? = null
    @Volatile private var ws: WebSocket? = null
    // A resolve/connect attempt is in flight — connect() must be
    // idempotent: several callers (service start, net callback, kick)
    // used to each spawn a socket, and the bridge took the LAST one.
    @Volatile private var connecting = false
    @Volatile private var closed = false
    private var backoffMs = 1_000L
    private var lastState: Boolean? = null
    // Consecutive Noise msg2 failures — a streak means the stored
    // bridge identity is stale, not that the network is flaky.
    private var handshakeFails = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingReconnect: Runnable? = null
    // Last inbound frame — heartbeat silence means the socket is a
    // zombie even if TCP hasn't errored yet.
    @Volatile private var lastInbound = 0L
    private var watchdogStarted = false

    /**
     * The bridge heartbeats every 30s — silence past this on an
     * established session means zombie. Also gates sendCommand: a
     * stale socket's send() buffers bytes into a dead pipe instead of
     * erroring, so callers must queue/reject rather than lose them.
     */
    private fun socketStale(): Boolean {
        val t = lastInbound
        return session != null && t > 0 &&
            android.os.SystemClock.elapsedRealtime() - t > ZOMBIE_MS
    }

    // Offline outbox — only command types that opt in (sms.send) queue
    // here; call/dial/control commands must never replay stale.
    private val outbox = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    @Volatile private var currentViaRelay = false

    private fun emitState(up: Boolean) {
        if (lastState == up) return
        lastState = up
        onState(up)
        onTransport(if (up) currentViaRelay else false)
    }

    /**
     * Kills zombie sockets: a half-dead TCP connection can blackhole
     * writes for ~15min before the retransmit timeout errors out.
     * The bridge heartbeats every 30s — if an established session sees
     * no inbound frame for 2min, the socket is dead regardless of what
     * TCP thinks. cancel() forces onFailure → normal reconnect path.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val check = object : Runnable {
            override fun run() {
                if (closed) return
                if (session != null && lastInbound > 0 &&
                    android.os.SystemClock.elapsedRealtime() - lastInbound > ZOMBIE_MS) {
                    Log.w(TAG, "heartbeat silence — killing zombie socket")
                    ws?.cancel()
                }
                handler.postDelayed(this, 30_000)
            }
        }
        handler.postDelayed(check, 30_000)
    }

    fun connect() {
        startWatchdog()
        // Idempotent — a live socket or an in-flight attempt means
        // another connect() has nothing to do.
        if (ws != null || connecting) return
        connecting = true
        // Resolution (cached-IP probe, then mDNS) can block — run off
        // the main thread.
        Thread({
            if (closed) { connecting = false; return@Thread }
            val target = targetProvider()
            if (target == null) {
                connecting = false
                Log.d(TAG, "no reachable bridge yet, retrying")
                scheduleReconnect()
                return@Thread
            }
            openSocket(target)
        }, "st-resolve").start()
    }

    private fun openSocket(target: ResolvedTarget) {
        // Path carries ?token= on relay targets — never log the query.
        Log.d(TAG, "connecting to ${target.host}:${target.port}" +
            target.path.substringBefore('?') +
            if (target.viaRelay) " (relay)" else "")
        currentViaRelay = target.viaRelay
        val request = Request.Builder()
            .url("${target.scheme}://${target.host}:${target.port}${target.path}")
            .apply { target.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val client = target.socketFactory?.let {
            http.newBuilder().socketFactory(it).build()
        } ?: http
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stale(webSocket)) return
                val hs = SecureSession.clientHandshake(
                    bridgeStaticPub, pairingTokenProvider(), clientStaticPriv)
                pendingHandshake = hs
                // First frame: IK msg1 — e/es/s/ss + pairing token, all
                // AEAD-bound. No session until the bridge's msg2 lands.
                webSocket.send(hs.outgoing.toByteString())
                backoffMs = 1_000L
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (stale(webSocket)) return
                val s = session ?: run {
                    val hs = pendingHandshake
                        ?: run { Log.w(TAG, "frame before handshake"); return }
                    session = runCatching { hs.complete(bytes.toByteArray()) }
                        .getOrElse {
                            Log.e(TAG, "handshake failed", it)
                            // NOT 4003 — a self-issued 4003 lands in
                            // onClosed and reads as "pairing revoked",
                            // wedging the client forever on a transient
                            // failure. 4000 = abnormal, just reconnect.
                            webSocket.close(4000, "handshake")
                            handshakeFails++
                            if (handshakeFails >= 5) {
                                // Repeated msg2 failures = the stored
                                // bridge key is stale (re-paired bridge)
                                // — surface the banner but keep retrying
                                // at backoff; a re-pair rebuilds us.
                                onRevoked()
                            }
                            return
                        }
                    handshakeFails = 0
                    pendingHandshake = null
                    lastInbound = android.os.SystemClock.elapsedRealtime()
                    // Announce optional features before any app traffic —
                    // the bridge must know we understand MEDIA_TAG frames
                    // before it may send them.
                    sendCommand("client.hello", Protocol.json.encodeToString(
                        Protocol.ClientHello.serializer(),
                        Protocol.ClientHello(listOf(Protocol.CAP_AUDIO))))
                    emitState(true)
                    // WS frames are ordered — the session is live, flush
                    // anything queued while we were offline.
                    while (true) {
                        val (t, p) = outbox.poll() ?: break
                        Log.d(TAG, "flushing queued $t")
                        sendCommand(t, p)
                    }
                    return
                }
                val plain = runCatching { s.decrypt(bytes.toByteArray()) }.getOrElse {
                    Log.e(TAG, "decrypt failed — resetting session", it)
                    session = null
                    webSocket.cancel()
                    return
                }
                if (plain.isNotEmpty() && plain[0] == Protocol.MEDIA_TAG) {
                    lastInbound = android.os.SystemClock.elapsedRealtime()
                    onMedia(plain.copyOfRange(1, plain.size))
                    return
                }
                val env = runCatching {
                    Protocol.decode(plain.decodeToString())
                }.getOrElse {
                    // A failed AEAD tag means the nonce streams desynced —
                    // per the Noise spec the session is unrecoverable and
                    // must be terminated. Tear down and reconnect instead
                    // of sitting half-dead forever (the watchdog can't
                    // see it — heartbeats were stamping liveness even
                    // while failing to decrypt).
                    Log.e(TAG, "decrypt/decode failed — resetting session", it)
                    session = null
                    webSocket.cancel()
                    return
                }
                lastInbound = android.os.SystemClock.elapsedRealtime()
                if (env.type == "hb") return  // heartbeat — liveness only
                Log.d(TAG, "recv ${env.type} seq=${env.seq}")
                onEvent(env)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale(webSocket)) return
                Log.w(TAG, "ws failure: ${t.message} (code=${response?.code})")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stale(webSocket)) return
                Log.d(TAG, "ws closed code=$code reason=$reason")
                if (code == 4003 || code == 4004) {
                    // 4003: pairing token rejected — the bridge rotated
                    // its identity. 4004: token valid but our client
                    // key isn't the pinned one (credential cloned to
                    // another device, or bridge re-paired). Either way
                    // reconnecting is futile until the user re-pairs.
                    closed = true
                    emitState(false)
                    onRevoked()
                    return
                }
                scheduleReconnect()
            }
        })
        // Assigned at creation, not in onOpen — a callback from a
        // replaced socket must find ws already pointing at the new one
        // (the stale() guard), and senders must not hit the dead
        // socket in the gap.
        ws = socket
        // The attempt is over once a socket exists — ws!=null guards
        // connect() from here on, so connecting can release.
        connecting = false
        // Handshake deadline: OkHttp disables read timeouts once the
        // upgrade completes, so a bridge that accepts TCP but never
        // answers Noise msg2 (half-killed process, dead accept loop)
        // leaves no callback path — no onOpen failure, no read error,
        // and the watchdog only watches established sessions. With
        // connecting=true and ws!=null every reconnect returns early:
        // a permanent wedge. If no session is live 15s after opening,
        // kill the socket; onFailure drives the normal reconnect path.
        handler.postDelayed({
            if (!closed && session == null && ws === socket) {
                Log.w(TAG, "handshake timeout — resetting socket")
                socket.cancel()
            }
        }, 15_000)
    }

    /** True when [webSocket] is no longer our live socket — every
     *  callback checks this so a dying socket's late events can't
     *  touch the new connection's state. */
    private fun stale(webSocket: WebSocket) = closed || webSocket !== ws

    /**
     * False when the command could not be sent or queued — callers on
     * the call path surface that as a rejection instead of leaving the
     * UI stuck in "dialing".
     */
    fun sendCommand(type: String, payload: String, queueIfOffline: Boolean = false): Boolean {
        // Snapshot the pair — session and ws are set/cleared together
        // in scheduleReconnect, but a mid-reconnect read must never
        // mix the new socket with the old cipher.
        val s = session
        val sock = ws
        // socketStale: a zombie socket accepts send() and silently
        // drops the bytes — callers must treat it as offline so
        // queueable commands wait for the reconnect instead of
        // vanishing (seen: network switch left the relay socket dead
        // while the UI still read "connected").
        val stale = socketStale()
        if (stale) {
            // Don't wait out the watchdog — cancel now so onFailure
            // drives the reconnect immediately.
            Log.w(TAG, "send on stale socket — killing it")
            runCatching { sock?.cancel() }
        }
        if (s == null || sock == null || stale) {
            if (queueIfOffline) {
                while (outbox.size >= MAX_OUTBOX) outbox.poll() // drop oldest
                outbox.add(type to payload)
                Log.d(TAG, "queued $type (offline), depth=${outbox.size}")
                return true
            }
            Log.d(TAG, "dropped $type — no live session")
            return false
        }
        val env = Protocol.Envelope(UUID.randomUUID().toString(), type, seq.incrementAndGet(), payload)
        sock.send(s.encrypt(Protocol.encode(env).toByteArray()).toByteString())
        return true
    }

    /** Mic frame → bridge uplink injection. Drops silently when the
     *  session is down — audio is ephemeral, never queued. */
    fun sendMedia(pcm: ByteArray) {
        val s = session ?: return
        val sock = ws ?: return
        sock.send(s.encrypt(byteArrayOf(Protocol.MEDIA_TAG) + pcm).toByteString())
    }

    private fun scheduleReconnect() {
        if (closed) return
        emitState(false)
        session?.destroy()
        session = null
        pendingHandshake = null
        connecting = false
        ws = null
        val delay = backoffMs + java.util.concurrent.ThreadLocalRandom.current()
            .nextLong(0, backoffMs / 2 + 1)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        val r = Runnable {
            pendingReconnect = null
            if (!closed) connect()
        }
        pendingReconnect = r
        handler.postDelayed(r, delay)
    }

    /**
     * Drop the live socket and re-resolve — called when a better
     * transport just became reachable (LAN probe answered while we're
     * relay-linked) or the default-route network under a relay socket
     * was lost and TCP hasn't errored it yet. The close drives the
     * normal onClosed → scheduleReconnect path; pre-resetting backoff
     * keeps the re-dial immediate.
     */
    fun dropAndReconnect() {
        if (closed) return
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
        backoffMs = 1_000L
        ws?.close(1000, "transport switch") ?: connect()
    }

    /**
     * NetworkCallback fires this when a LAN transport appears — retry
     * now instead of sleeping out the backoff (that 3-minute gap).
     */
    fun kick() {
        if (closed) return
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
        backoffMs = 1_000L
        if (ws == null) connect()
    }

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        http.dispatcher.executorService.shutdown()
    }

    private companion object {
        // ~2 missed 30s heartbeats tolerated before a session is
        // declared zombie — scheduler/GC jank on the bridge can stall
        // individual beats without killing the link.
        const val ZOMBIE_MS = 75_000L
    }
}
