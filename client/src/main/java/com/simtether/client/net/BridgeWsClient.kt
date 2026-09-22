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
)

class BridgeWsClient(
    private val targetProvider: () -> ResolvedTarget?,
    private val bridgeStaticPub: ByteArray,
    // Provider, not a snapshot — the bridge rotates the token every
    // session, so each connect attempt must read the latest stored one.
    private val pairingTokenProvider: () -> ByteArray,
    private val onEvent: (Protocol.Envelope) -> Unit,
    private val onState: (Boolean) -> Unit = {},
    // Reports which transport the live session rides (LAN vs relay) —
    // lets the UI label the link honestly.
    private val onTransport: (Boolean) -> Unit = {},
    // Fired when the bridge rejects our token (4003) — the pairing was
    // revoked/rotated; reconnecting is futile until the user re-pairs.
    private val onRevoked: () -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val seq = AtomicLong(0)
    private val TAG = "SimTether.Client"
    private val MAX_OUTBOX = 100
    // Mutated on OkHttp dispatcher threads, read by sendCommand()
    // callers on the UI thread — volatile or stale reads drop commands.
    @Volatile private var session: SecureSession? = null
    @Volatile private var pendingHandshake: SecureSession.ClientHandshake? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var backoffMs = 1_000L
    private var lastState: Boolean? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingReconnect: Runnable? = null
    // Last inbound frame — heartbeat silence means the socket is a
    // zombie even if TCP hasn't errored yet.
    @Volatile private var lastInbound = 0L
    private var watchdogStarted = false

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
                    android.os.SystemClock.elapsedRealtime() - lastInbound > 120_000L) {
                    Log.w(TAG, "heartbeat silence — killing zombie socket")
                    ws?.cancel()
                }
                handler.postDelayed(this, 60_000)
            }
        }
        handler.postDelayed(check, 60_000)
    }

    fun connect() {
        startWatchdog()
        // Resolution (cached-IP probe, then mDNS) can block — run off
        // the main thread.
        Thread({
            if (closed) return@Thread
            val target = targetProvider()
            if (target == null) {
                Log.d(TAG, "no reachable bridge yet, retrying")
                scheduleReconnect()
                return@Thread
            }
            openSocket(target)
        }, "st-resolve").start()
    }

    private fun openSocket(target: ResolvedTarget) {
        Log.d(TAG, "connecting to ${target.host}:${target.port}${target.path}" +
            if (target.viaRelay) " (relay)" else "")
        currentViaRelay = target.viaRelay
        val request = Request.Builder()
            .url("${target.scheme}://${target.host}:${target.port}${target.path}").build()
        val client = target.socketFactory?.let {
            http.newBuilder().socketFactory(it).build()
        } ?: http
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hs = SecureSession.clientHandshake(bridgeStaticPub, pairingTokenProvider())
                pendingHandshake = hs
                ws = webSocket
                // First frame: IK msg1 — e/es/s/ss + pairing token, all
                // AEAD-bound. No session until the bridge's msg2 lands.
                webSocket.send(hs.outgoing.toByteString())
                backoffMs = 1_000L
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastInbound = android.os.SystemClock.elapsedRealtime()
                val s = session ?: run {
                    val hs = pendingHandshake
                        ?: run { Log.w(TAG, "frame before handshake"); return }
                    session = runCatching { hs.complete(bytes.toByteArray()) }
                        .getOrElse {
                            Log.e(TAG, "handshake failed", it)
                            webSocket.close(4003, "handshake")
                            return
                        }
                    pendingHandshake = null
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
                val env = runCatching {
                    Protocol.decode(s.decrypt(bytes.toByteArray()).decodeToString())
                }.getOrElse {
                    Log.e(TAG, "decrypt/decode failed", it)
                    return
                }
                if (env.type == "hb") return  // heartbeat — liveness only
                Log.d(TAG, "recv ${env.type} seq=${env.seq}")
                onEvent(env)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message} (code=${response?.code})")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed code=$code reason=$reason")
                if (code == 4003) {
                    // Pairing token rejected — the bridge rotated its
                    // identity. Mark closed so no retry ever fires.
                    closed = true
                    emitState(false)
                    onRevoked()
                    return
                }
                scheduleReconnect()
            }
        })
    }

    fun sendCommand(type: String, payload: String, queueIfOffline: Boolean = false) {
        val s = session
        if (s == null || ws == null) {
            if (queueIfOffline) {
                while (outbox.size >= MAX_OUTBOX) outbox.poll() // drop oldest
                outbox.add(type to payload)
                Log.d(TAG, "queued $type (offline), depth=${outbox.size}")
            }
            return
        }
        val env = Protocol.Envelope(UUID.randomUUID().toString(), type, seq.incrementAndGet(), payload)
        ws?.send(s.encrypt(Protocol.encode(env).toByteArray()).toByteString())
    }

    private fun scheduleReconnect() {
        if (closed) return
        emitState(false)
        session = null
        pendingHandshake = null
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
}
