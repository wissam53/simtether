package com.simtether.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.simtether.bridge.net.BridgeAdvertiser
import com.simtether.bridge.net.BridgeWsServer
import com.simtether.bridge.net.LanAddress
import com.simtether.shared.Identity
import com.simtether.bridge.sms.BridgeServiceHolder
import com.simtether.bridge.sms.SmsSender
import com.simtether.shared.pairing.PairingPayload
import com.simtether.shared.protocol.Protocol
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge foreground service: owns the WS server on the hotspot LAN,
 * the encrypted session, and the store-and-forward event queue.
 * Everything the client sees originates here.
 */
class BridgeService : LifecycleService() {

    // In-app language override for notification strings.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    private val seq = AtomicLong(0)
    private var server: BridgeWsServer? = null
    private var advertiser: BridgeAdvertiser? = null
    /** Retained for watchdog restarts — same pair loadOrCreateIdentity
     *  returned, or the fresh pair after rePair(). */
    private var staticKey: Pair<ByteArray, ByteArray>? = null
    private var relayLink: com.simtether.bridge.net.RelayLink? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Store-and-forward: reliable events stay queued (persisted JSONL)
    // until the client acks them — survives link drops mid-flush AND
    // service restarts.
    private val pending by lazy {
        com.simtether.shared.PendingEventQueue(
            java.io.File(filesDir, "pending_events.jsonl"), MAX_PENDING)
    }
    // Pairing-token rotation: current + an optional in-flight pending
    // token, persisted so a restart keeps the window open.
    private var tokenRotator: com.simtether.shared.TokenRotator? = null

    // emit() runs here: a single thread keeps queue order while the
    // synchronous persist (serialize + encrypt + write) stays off the
    // Telecom/main threads that produce call.event and sms.status.
    private val emitExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bridge-emit").also { it.isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        BridgeServiceHolder.service = this
        com.simtether.shared.ContactLookup.init(applicationContext)
        startForegroundWithNotification()
        val (staticKey, token) = loadOrCreateIdentity()
        this.staticKey = staticKey
        tokenRotator = com.simtether.shared.TokenRotator(
            token, loadPendingToken()) { cur, pend -> persistTokens(cur, pend) }
        // Telecom sends the canned reply itself on reject-with-message —
        // record it locally, echo it to the client, and confirm via the
        // sent-box write rather than optimistically marking it sent.
        com.simtether.bridge.sms.SentBoxWatcher.start(this)
        CallController.onRejectSms = { number, text ->
            val ref = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            com.simtether.shared.ConversationStore.onEcho(number, text, ts, ref)
            com.simtether.bridge.sms.SentBoxWatcher.expect(number, text, ref) { sent ->
                val st = Protocol.SmsStatus(ref,
                    if (sent) Protocol.SmsStatus.Status.SENT
                    else Protocol.SmsStatus.Status.UNCONFIRMED)
                com.simtether.shared.ConversationStore.onStatus(st)
                emit("sms.status", Protocol.json.encodeToString(
                    Protocol.SmsStatus.serializer(), st))
            }
            emit("sms.echo", Protocol.json.encodeToString(
                Protocol.SmsEcho.serializer(),
                Protocol.SmsEcho(number, text, ts, ref)))
        }
        // Carrier's USSD reply / dial refusal → the wire, so the
        // client that sent the code sees what the network answered.
        CallController.onUssdResult = { code, response, error ->
            emit("ussd.result", Protocol.json.encodeToString(
                Protocol.UssdResult.serializer(),
                Protocol.UssdResult(code, response, error)))
        }
        CallController.onDialRejected = { reason ->
            emit("dial.rejected", Protocol.json.encodeToString(
                Protocol.DialRejected.serializer(),
                Protocol.DialRejected(reason)))
        }
        startRelay(staticKey)
        pending.load()
        drainParked()
        acquireLocks()
        // Fresh battery/signal for the client's home card while a
        // session is live — not just once at connect time.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(STATUS_PUSH_MS)
                if (server?.isReady() == true) StatusReporter.emit(applicationContext)
            }
        }
        startServerWatchdog()
    }

    /**
     * The service outlives its own socket: if the WS selector thread
     * dies (or the port is lost), the process stays up — FGS, wake
     * lock, START_STICKY all look healthy — while the bridge is deaf.
     * Probe the port; when it refuses, rebuild server+advertiser.
     * Cooldown-bounded: a wedged port retries slowly rather than
     * churning NSD registrations every cycle.
     */
    private var lastServerRestart = 0L
    private fun startServerWatchdog() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(SERVER_WATCHDOG_MS)
                val srv = server ?: continue
                val alive = runCatching {
                    java.net.Socket().use {
                        it.connect(java.net.InetSocketAddress(
                            "127.0.0.1", Protocol.WS_PORT), 2_000)
                    }
                }.isSuccess
                if (alive) continue
                val now = System.currentTimeMillis()
                // Cooldown, not edge-trigger: a failed rebuild gets
                // retried, but a wedged port never churns a rebuild
                // every cycle (each attempt re-registers NSD + relay).
                if (now - lastServerRestart < SERVER_RESTART_COOLDOWN_MS) continue
                lastServerRestart = now
                Log.w(TAG, "watchdog: WS server not listening — restarting")
                val key = staticKey ?: continue
                // rePair() may have swapped in a fresh server while
                // the probe was in flight — only rebuild when the
                // dead instance is still the live one.
                if (server !== srv) continue
                runCatching { advertiser?.stop() }
                runCatching { srv.stop(1_000) }
                advertiser = null
                runCatching { startRelay(key) }
                    .onFailure { Log.e(TAG, "watchdog restart failed", it) }
            }
        }
    }

    private fun startRelay(staticKey: Pair<ByteArray, ByteArray>) {
        server = BridgeWsServer(
            port = Protocol.WS_PORT,
            staticKeyPair = staticKey,
            tokenValid = { tokenRotator?.accept(it) == true },
            clientKeyAccepted = { pub -> acceptClientKey(pub) },
            onClientReady = {
                lifecycleScope.launch {
                    clientConnected = true
                    updateNotification(connected = true)
                    // A linked client owns the call UI — drop the local fallback.
                    com.simtether.bridge.telecom.BridgeCallUi.dismiss(applicationContext)
                    StatusReporter.emit(applicationContext)
                    maybeRotateToken()
                }
                emitExec.execute { flushPending() }
            },
            onClientDisconnected = {
                clientConnected = false
                // The new session re-announces caps via client.hello —
                // until then assume the peer can't take media frames.
                clientCaps = emptySet()
                audioActive = false
                audioAttempted = false
                com.simtether.bridge.audio.AudioRelayProvider.relay?.stop()
                lifecycleScope.launch { updateNotification(connected = false) }
            },
            onCommand = { env -> lifecycleScope.launch(Dispatchers.IO) { handleCommand(env) } },
            onMedia = { pcm ->
                com.simtether.bridge.audio.AudioRelayProvider.relay?.inject(pcm)
            },
        ).also { it.start() }
        advertiser = BridgeAdvertiser(
            applicationContext,
            Identity.serviceName(staticKey.second),
            Protocol.WS_PORT,
        ).also { it.start() }
        refreshRemote()
    }

    /**
     * (Re)apply remote-access prefs: start or stop the relay link.
     * Called on service start, on re-pair (the room name is the key
     * fingerprint — it changes), and from the settings toggle.
     */
    @Synchronized
    fun refreshRemote() {
        val srv = server ?: return
        val addr = if (com.simtether.shared.RemoteStore.isEnabled(this))
            com.simtether.shared.RemoteStore.effectiveRelay(this) else null
        if (addr == null) {
            relayLink?.stop()
            relayLink = null
            return
        }
        relayLink?.stop()
        relayLink = com.simtether.bridge.net.RelayLink(
            srv,
            addr,
            Identity.roomId(srv.staticPubKey),
            com.simtether.shared.RemoteStore.effectiveRelayToken(this),
            staticPriv = srv.staticPrivKey,
            staticPub = srv.staticPubKey,
            relaySecret = loadRelaySecret() ?: ByteArray(0),
        ).also { it.start() }
        Log.d(TAG, "remote access on — registering with relay $addr")
    }

    /**
     * Re-pair: rotate the static identity + pairing token. A leaked QR
     * or lost client phone loses access instantly — the old key can't
     * authenticate an IK handshake, the old token fails verification,
     * and the mDNS advert moves to the new key's fingerprint. The user
     * scans the fresh QR on the client to restore the link.
     */
    fun rePair() {
        val enc = Base64.getEncoder()
        val pair = com.simtether.shared.crypto.SecureSession.generateKeyPair()
        val token = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val relaySecret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        // failClosed — same rule as loadOrCreateIdentity: the static
        // private key never lands in plaintext prefs. .all{} not && —
        // a skipped write must never leave a half-rotated identity.
        val persisted = listOf(
            "static_priv" to enc.encodeToString(pair.first),
            "static_pub" to enc.encodeToString(pair.second),
            "pairing_token" to enc.encodeToString(token),
            "relay_secret" to enc.encodeToString(relaySecret),
        ).all { (k, v) ->
            com.simtether.shared.SecureStore.putString(
                this, "bridge_keys", k, v, failClosed = true)
        }
        if (!persisted) {
            // Transient keystore failure + recoverable old entries =
            // the revoked pairing resurrects on next process start
            // (SecureStore.key is lazy; getString would decrypt the
            // stale copies fine once the keystore recovers). Clear
            // them — removal needs no crypto.
            Log.e(TAG, "identity NOT persisted — clearing stale keys so " +
                "a recovered keystore can't resurrect the revoked pairing")
            listOf("static_priv", "static_pub", "pairing_token",
                "relay_secret", "client_pub", "pairing_token_pending"
            ).forEach {
                com.simtether.shared.SecureStore.putString(
                    this, "bridge_keys", it, null)
            }
        }
        tokenRotator = com.simtether.shared.TokenRotator(token) { cur, pend ->
            persistTokens(cur, pend)
        }
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token_pending", null)
        // Fresh identity = fresh trust — the pinned client key goes
        // too, so the next valid-token auth re-pins whoever pairs.
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "client_pub", null)
        runCatching { server?.stop() }
        server = null
        runCatching { advertiser?.stop() }
        advertiser = null
        staticKey = pair
        startRelay(pair)
        Log.i(TAG, "identity rotated — previous pairing revoked")
    }

    /**
     * The bridge is a LAN appliance: with the screen off, Android/MIUI dozes
     * wlan0 and the CPU, making the server unreachable (ARP fails, inbound TCP
     * drops). A partial wake lock keeps the WS server + mDNS responder alive,
     * and a low-latency WifiLock keeps the radio responsive to inbound packets.
     * Battery cost is acceptable — the bridge is expected to stay plugged in.
     */
    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simtether:bridge")
                .also { it.acquire() }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "simtether:bridge")
                .also { it.setReferenceCounted(false); it.acquire() }
            Log.d(TAG, "wake+wifi locks acquired")
        }.onFailure { Log.w(TAG, "lock acquire failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Events delivered by broadcast receivers while we were down.
        val type = intent?.getStringExtra(EXTRA_EVENT_TYPE)
        val payload = intent?.getStringExtra(EXTRA_EVENT_PAYLOAD)
        if (type != null && payload != null) emit(type, payload)
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeServiceHolder.service = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
        advertiser?.stop()
        advertiser = null
        relayLink?.stop()
        relayLink = null
        server?.stop()
        server = null
        emitExec.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** True when a paired client holds an open encrypted session. */
    fun clientReady() = server?.isReady() == true

    /**
     * Call audio is (about to be) flowing — raise the relay's
     * per-socket byte cap for this room. Idempotent; safe to call
     * with no relay link (LAN-only mode is a no-op).
     */
    fun setCallMedia(active: Boolean) {
        relayLink?.setMediaMode(active)
    }

    /**
     * Entry point for all bridge→client events (SMS, call state, status).
     * Reliable events stay in [pending] — persisted — until the client
     * acks them; a send into a half-dead socket is otherwise
     * indistinguishable from delivery. Ephemeral events (bridge.status)
     * are send-only: the next emit carries fresher data anyway.
     */
    fun emit(type: String, payload: String, reliable: Boolean = true) {
        val env = Protocol.Envelope(UUID.randomUUID().toString(), type, seq.incrementAndGet(), payload)
        emitExec.execute {
            if (reliable) {
                pending.add(env).forEach {
                    Log.w(TAG, "pending full — dropped unacked ${it.type}")
                }
                if (server?.isReady() != true)
                    Log.d(TAG, "queued $type (no ready client), depth=${pending.size}")
            }
            if (server?.isReady() == true) server?.send(env)
        }
    }

    /**
     * Static X25519 identity + pairing token, persisted so service
     * restarts don't silently break an established pairing — a fresh
     * key would make every client frame undecryptable. The token gates
     * the handshake so random LAN devices can't steal the client slot.
     * Stored Keystore-encrypted via SecureStore — the static private
     * key is the bridge's whole identity.
     */
    private fun loadOrCreateIdentity(): Pair<Pair<ByteArray, ByteArray>, ByteArray> {
        val dec = Base64.getDecoder()
        val enc = Base64.getEncoder()
        val privB64 = com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "static_priv")
        val pubB64 = com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "static_pub")
        val tokenB64 = com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "pairing_token")
        if (privB64 != null && pubB64 != null && tokenB64 != null) {
            return (dec.decode(privB64) to dec.decode(pubB64)) to dec.decode(tokenB64)
        }
        val pair = com.simtether.shared.crypto.SecureSession.generateKeyPair()
        val token = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        // failClosed — the static private key IS the bridge identity;
        // a plaintext write on a keystore-broken device is worse than
        // an ephemeral identity that dies with the process.
        val persisted = listOf(
            "static_priv" to enc.encodeToString(pair.first),
            "static_pub" to enc.encodeToString(pair.second),
            "pairing_token" to enc.encodeToString(token),
        ).all { (k, v) ->
            com.simtether.shared.SecureStore.putString(
                this, "bridge_keys", k, v, failClosed = true)
        }
        if (!persisted) {
            // The service still runs on the in-memory identity — but
            // clear stale entries too, or a transient keystore failure
            // leaves old keys that a recovered keystore decrypts on the
            // next start.
            Log.e(TAG, "identity NOT persisted — pairing breaks on restart")
            listOf("static_priv", "static_pub", "pairing_token",
                "relay_secret").forEach {
                com.simtether.shared.SecureStore.putString(
                    this, "bridge_keys", it, null)
            }
        }
        // Same lifetime as the identity — loadRelaySecret creates it
        // on first read if an upgrade left it absent.
        loadRelaySecret()
        return pair to token
    }

    /**
     * Per-identity secret that derives the relay room ticket — rides
     * the QR so the paired client can prove it belongs to our room
     * without the relay ever learning the pairing token. Rotates with
     * rePair(); load-or-create so pre-feature installs gain one.
     */
    private fun loadRelaySecret(): ByteArray? {
        val existing = com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "relay_secret")
        if (existing != null) return Base64.getDecoder().decode(existing)
        val s = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        com.simtether.shared.SecureStore.putString(
            this, "bridge_keys", "relay_secret",
            Base64.getEncoder().encodeToString(s), failClosed = true)
        return s
    }

    /** QR content for pairing: LAN addr hint + pinned pubkey + pairing token. */
    fun pairingPayload(): PairingPayload? {
        val host = LanAddress.localIpv4() ?: return null
        val b64 = Base64.getEncoder()
        return PairingPayload(
            host = host,
            port = Protocol.WS_PORT,
            bridgeStaticPubKey = b64.encodeToString(server?.staticPubKey ?: return null),
            pairingToken = b64.encodeToString(tokenRotator?.current ?: return null),
            deviceName = android.os.Build.MODEL,
            // Remote access rides the QR — the client learns the
            // rendezvous without a second config step. Only present
            // while the bridge owner opted in.
            relay = if (com.simtether.shared.RemoteStore.isEnabled(this))
                com.simtether.shared.RemoteStore.effectiveRelay(this) else null,
            relayToken = if (com.simtether.shared.RemoteStore.isEnabled(this))
                com.simtether.shared.RemoteStore.effectiveRelayToken(this) else null,
            // The room ticket derives from this — the client can't
            // /connect without it, and the relay never learns it.
            relaySecret = if (com.simtether.shared.RemoteStore.isEnabled(this))
                loadRelaySecret()?.let { b64.encodeToString(it) } else null,
        )
    }

    /**
     * Re-queue events a receiver parked because Android refused the
     * FGS start (background-start restrictions before the dialer role
     * or CDM association lands). Envelopes keep the id assigned at
     * park time, so a drain that raced a process death is idempotent —
     * the client dedups on id.
     */
    private fun drainParked() {
        emitExec.execute {
            val f = java.io.File(filesDir, PARKED_FILE)
            val text = com.simtether.shared.SecureFile.read(f) ?: return@execute
            text.lineSequence()
                .mapNotNull { runCatching { Protocol.decode(it) }.getOrNull() }
                .forEach { env ->
                    pending.add(env).forEach {
                        Log.w(TAG, "pending full — dropped parked ${it.type}")
                    }
                }
            f.delete()
            flushPending()
        }
    }

    /**
     * Re-send everything the client hasn't acked. Events stay queued —
     * removal happens only in the "ack" command handler — so a link
     * that dies mid-flush loses nothing.
     */
    private fun flushPending() {
        Log.d(TAG, "client ready, flushing ${pending.size} unacked events")
        for (env in pending.snapshot()) {
            if (server?.isReady() != true) break
            server?.send(env)
        }
    }

    private fun loadPendingToken(): ByteArray? = com.simtether.shared.SecureStore
        .getString(this, "bridge_keys", "pairing_token_pending")
        ?.let { Base64.getDecoder().decode(it) }

    /**
     * Mutual auth — trust-on-first-use pin of the client's static key.
     * The first caller to pass token verification gets pinned; after
     * that the same key must present every session. A QR photographed
     * mid-window still yields a valid token, but the thief's key won't
     * match the pin once the real client has connected — and if the
     * thief connects FIRST, the real client's mismatch shows up as a
     * revoked banner on its side, surfacing the compromise instead of
     * silently coexisting. rePair() clears the pin.
     */
    private val clientKeyLock = Any()
    private fun acceptClientKey(pub: ByteArray): Boolean = synchronized(clientKeyLock) {
        val pin = com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "client_pub")
        if (pin == null) {
            com.simtether.shared.SecureStore.putString(
                this, "bridge_keys", "client_pub",
                Base64.getEncoder().encodeToString(pub))
            Log.i(TAG, "pinned client key fp=${Identity.fingerprint(pub)}")
            return true
        }
        java.security.MessageDigest.isEqual(Base64.getDecoder().decode(pin), pub)
    }

    /**
     * Short fingerprint of the pinned client key, or null pre-pair.
     * Shown in the bridge UI so a user can verify WHICH device holds
     * the link — a rogue pairing is visible here, not hidden.
     */
    fun pinnedClientFp(): String? =
        com.simtether.shared.SecureStore
            .getString(this, "bridge_keys", "client_pub")
            ?.let { runCatching {
                Identity.fingerprint(Base64.getDecoder().decode(it)) }.getOrNull() }

    private fun persistTokens(current: ByteArray, pending: ByteArray?) {
        val enc = Base64.getEncoder()
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token", enc.encodeToString(current))
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token_pending",
                pending?.let { enc.encodeToString(it) })
    }

    /**
     * Rotate the pairing token every session — the QR credential is a
     * bearer token, so it shouldn't stay valid forever. The new token
     * rides a reliable "pairing.rotate" event and only becomes current
     * once acked (or once the client authenticates with it), so a link
     * that dies mid-rotation can't brick the pairing.
     */
    private fun maybeRotateToken() {
        val next = tokenRotator?.rotate() ?: return  // rotation in flight
        emit("pairing.rotate", Protocol.json.encodeToString(
            Protocol.PairingRotate.serializer(),
            Protocol.PairingRotate(Base64.getEncoder().encodeToString(next))))
    }

    private fun handleCommand(env: Protocol.Envelope) {
        if (env.pv > Protocol.PROTOCOL_VERSION)
            Log.w(TAG, "client speaks newer protocol pv=${env.pv} — update the bridge")
        // One bad command must never kill the service — the whole
        // relay (WS server + mDNS advert) lives in this process.
        runCatching { dispatchCommand(env) }
            .onFailure { Log.e(TAG, "command ${env.type} failed", it) }
    }

    private fun dispatchCommand(env: Protocol.Envelope) {
        when (env.type) {
            "sms.send" -> {
                val cmd = env.payloadAs<Protocol.SmsSend>()
                if (CallController.isSafeNumber(cmd.address)) {
                    SmsSender.send(applicationContext, cmd.address, cmd.body, cmd.requestDeliveryReport, cmd.ref)
                } else {
                    Log.w(TAG, "sms.send: rejected unsafe address shape")
                    // Tell the client — a silent drop leaves the bubble
                    // looking sent (same failure class as the AEAD fix).
                    emit("sms.status", Protocol.json.encodeToString(
                        Protocol.SmsStatus.serializer(),
                        Protocol.SmsStatus(cmd.ref,
                            Protocol.SmsStatus.Status.FAILED,
                            "unsafe address")))
                }
            }
            "call.action" -> {
                val cmd = env.payloadAs<Protocol.CallAction>()
                CallController.dispatch(applicationContext, cmd)
            }
            "dial" -> {
                val cmd = env.payloadAs<Protocol.DialRequest>()
                CallController.dial(applicationContext, cmd.number)
            }
            "ussd" -> {
                val cmd = env.payloadAs<Protocol.UssdRequest>()
                CallController.ussd(applicationContext, cmd.code)
            }
            "ack" -> {
                val acked = pending.remove(env.payloadAs<Protocol.Ack>().forId)
                // The rotate ack retires the old token — the client has
                // durably stored the new one.
                if (acked?.type == "pairing.rotate") tokenRotator?.onAck()
            }
            "bridge.command" -> {
                val cmd = env.payloadAs<Protocol.BridgeCommand>()
                StatusReporter.handleCommand(applicationContext, cmd)
            }
            "client.hello" -> {
                val caps = env.payloadAs<Protocol.ClientHello>().caps.toSet()
                clientCaps = caps
                Log.d(TAG, "client caps: ${caps.joinToString()}")
                // A call may already be ACTIVE when the hello lands —
                // evaluate the audio relay now that we know the peer.
                updateCallAudio(
                    com.simtether.bridge.telecom.CallRegistry.all()
                        .any { it.second.state == android.telecom.Call.STATE_ACTIVE })
            }
        }
    }

    // Capabilities announced by the live client (empty = old client or
    // no session). Media frames are never sent to a session that
    // didn't claim "audio" — untagged binary would kill its session.
    @Volatile private var clientCaps: Set<String> = emptySet()
    @Volatile private var audioActive = false
    // A failed start() latches for the call — Telecom re-emits details
    // constantly and a dead capture path shouldn't be retried per
    // detailsChanged. Reset when no call is active.
    @Volatile private var audioAttempted = false

    /**
     * Call-audio relay lifecycle — driven by BridgeInCallService on
     * every state change. Starts the rooted capture/injection session
     * when a call is ACTIVE AND the client understands media frames;
     * tears it down when neither holds. The relay impl lives in the
     * rooted flavor; on the store build the provider is empty and
     * this is a no-op.
     *
     * Runs on emitExec: relay.start() can block for seconds (su probe,
     * tinymix scan, process spawn) and callers sit on Telecom's main-
     * thread callback — a stall there would ANR the in-call service.
     */
    fun updateCallAudio(anyActive: Boolean) {
        emitExec.execute { updateCallAudioInternal(anyActive) }
    }

    private fun updateCallAudioInternal(anyActive: Boolean) {
        val relay = com.simtether.bridge.audio.AudioRelayProvider.relay
        val want = anyActive && clientCaps.contains(Protocol.CAP_AUDIO) &&
            relay?.available(applicationContext) == true
        if (!want) {
            if (audioActive || audioAttempted) {
                audioActive = false
                audioAttempted = false
                relay?.stop()
                emit("call.audio", Protocol.json.encodeToString(
                    Protocol.CallAudio.serializer(),
                    Protocol.CallAudio(active = false)), reliable = false)
                Log.i(TAG, "call audio relay stopped")
            }
            return
        }
        if (audioActive || audioAttempted) return
        audioAttempted = true
        val up = relay!!.start(applicationContext) { pcm -> server?.sendMedia(pcm) }
        audioActive = up
        emit("call.audio", Protocol.json.encodeToString(
            Protocol.CallAudio.serializer(),
            Protocol.CallAudio(active = up, downlink = true, uplink = up)),
            reliable = false)
        Log.i(TAG, "call audio relay ${if (up) "started" else "unavailable"}")
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, com.simtether.shared.LocaleHelper.wrap(this)
                    .getString(com.simtether.shared.R.string.channel_bridge),
                NotificationManager.IMPORTANCE_LOW)
                // Persistent status — never counts toward the app badge.
                .apply { setShowBadge(false) }
        )
        startForegroundSafely(buildNotification(connected = false))
    }

    /**
     * API 34+ needs an explicit type, and API 35 refuses
     * connectedDevice/dataSync/phoneCall when the process was started
     * by a BOOT_COMPLETED receiver — exactly the path BootReceiver
     * uses. Fall back to specialUse (declared in the manifest for this
     * case), and never let a refusal crash the service: an untyped
     * start is the last resort on 29–33, and on 34+ a total refusal
     * means the system stops us — logged, not crashed.
     */
    private fun startForegroundSafely(notif: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            for (type in listOf(foregroundType(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE).distinct()) {
                try {
                    startForeground(NOTIF_ID, notif, type)
                    return
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "startForeground type=$type refused: ${e.message}")
                }
            }
        }
        runCatching { startForeground(NOTIF_ID, notif) }
            .onFailure { Log.e(TAG, "startForeground refused entirely", it) }
    }

    /**
     * dataSync FGS is capped at 6h/24h on Android 15 — fatal for a
     * 24/7 relay. connectedDevice (made for persistent companion links)
     * has no such cap; it needs NEARBY_WIFI_DEVICES granted or a live
     * CDM association, so we degrade to dataSync only when neither
     * prerequisite can hold yet (pre-grant first start).
     */
    private fun foregroundType(): Int {
        val nearbyGranted = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        // Any live CDM association also satisfies the prerequisite.
        val cdmAssociated = Build.VERSION.SDK_INT >= 33 && runCatching {
            getSystemService(android.companion.CompanionDeviceManager::class.java)
                ?.myAssociations?.isNotEmpty() == true
        }.getOrDefault(false)
        return if (nearbyGranted || cdmAssociated)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private fun buildNotification(connected: Boolean): Notification {
        // Re-wrap per build — a language change applies on the next
        // post without restarting the service.
        val ctx = com.simtether.shared.LocaleHelper.wrap(this)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(com.simtether.shared.R.string.notif_bridge_active))
            .setContentText(ctx.getString(
                if (connected) com.simtether.shared.R.string.notif_client_connected
                else com.simtether.shared.R.string.notif_waiting_client
            ))
            .setSmallIcon(com.simtether.shared.R.drawable.ic_stat_simtether)
            .setOngoing(true)
            // Post immediately — the default DEFERRED behavior can hide
            // the notice for seconds after start, a window where the
            // bridge runs with no visible signal.
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 14+ lets users swipe-dismiss even ongoing FGS
            // notifications — deleteIntent fires on dismiss and the
            // receiver re-posts it. The bridge must never run invisibly.
            .setDeleteIntent(
                android.app.PendingIntent.getBroadcast(
                    this, 0,
                    android.content.Intent(this, NotifDismissReceiver::class.java)
                        .setAction(ACTION_NOTIF_DISMISSED),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                        or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            // Channel badge setting is locked at creation — the
            // per-notification flag fixes installs that already have it.
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
    }

    /** True when the bridge's status channel still shows — a muted
     *  channel means the FGS could run with no visible indicator. */
    fun statusChannelVisible(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(CHANNEL_ID) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(connected))
    }

    private var clientConnected = false

    /** Re-post in the current language — called after a locale change. */
    fun refreshNotification() = updateNotification(clientConnected)

    companion object {
        const val ACTION_NOTIF_DISMISSED = "com.simtether.bridge.NOTIF_DISMISSED"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
        private const val TAG = "SimTether.Bridge"
        private const val MAX_PENDING = 200
        private const val STATUS_PUSH_MS = 60_000L
        private const val SERVER_WATCHDOG_MS = 60_000L
        private const val SERVER_RESTART_COOLDOWN_MS = 5 * 60_000L
        private const val PARKED_FILE = "parked_events.jsonl"
        const val EXTRA_EVENT_TYPE = "com.simtether.bridge.EVENT_TYPE"
        const val EXTRA_EVENT_PAYLOAD = "com.simtether.bridge.EVENT_PAYLOAD"

        /**
         * Receiver-side parking for when the OS refuses the FGS start
         * (Android 12+ background-start rules before the dialer role /
         * CDM association grants the exemption). Stored as a full
         * envelope — the fixed id makes a re-drain idempotent.
         */
        fun parkEvent(context: android.content.Context, type: String, payload: String) {
            val env = Protocol.Envelope(UUID.randomUUID().toString(), type, 0, payload)
            val f = java.io.File(context.filesDir, PARKED_FILE)
            val prev = com.simtether.shared.SecureFile.read(f) ?: ""
            com.simtether.shared.SecureFile.write(f, prev + Protocol.encode(env) + "\n")
        }

        /**
         * User-controlled master switch — when off, nothing may restart
         * the bridge (activity open, boot, incoming SMS all gate on it).
         */
        fun isEnabled(context: android.content.Context) =
            context.getSharedPreferences("app", MODE_PRIVATE)
                .getBoolean("bridge_enabled", true)

        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences("app", MODE_PRIVATE).edit()
                .putBoolean("bridge_enabled", enabled).apply()
        }
    }
}
