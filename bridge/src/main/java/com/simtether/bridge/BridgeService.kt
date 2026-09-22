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
        tokenRotator = com.simtether.shared.TokenRotator(
            token, loadPendingToken()) { cur, pend -> persistTokens(cur, pend) }
        startRelay(staticKey)
        pending.load()
        acquireLocks()
        // Fresh battery/signal for the client's home card while a
        // session is live — not just once at connect time.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(STATUS_PUSH_MS)
                if (server?.isReady() == true) StatusReporter.emit(applicationContext)
            }
        }
    }

    private fun startRelay(staticKey: Pair<ByteArray, ByteArray>) {
        server = BridgeWsServer(
            port = Protocol.WS_PORT,
            staticKeyPair = staticKey,
            tokenValid = { tokenRotator?.accept(it) == true },
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
                lifecycleScope.launch { updateNotification(connected = false) }
            },
            onCommand = { env -> lifecycleScope.launch(Dispatchers.IO) { handleCommand(env) } },
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
    fun refreshRemote() {
        val srv = server ?: return
        val addr = if (com.simtether.shared.RemoteStore.isEnabled(this))
            com.simtether.shared.RemoteStore.relay(this) else null
        if (addr == null) {
            relayLink?.stop()
            relayLink = null
            return
        }
        relayLink?.stop()
        relayLink = com.simtether.bridge.net.RelayLink(
            srv,
            addr,
            Identity.fingerprint(srv.staticPubKey),
            com.simtether.shared.RemoteStore.relayToken(this),
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
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "static_priv", enc.encodeToString(pair.first))
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "static_pub", enc.encodeToString(pair.second))
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token", enc.encodeToString(token))
        tokenRotator = com.simtether.shared.TokenRotator(token) { cur, pend ->
            persistTokens(cur, pend)
        }
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token_pending", null)
        runCatching { server?.stop() }
        server = null
        runCatching { advertiser?.stop() }
        advertiser = null
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
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "static_priv", enc.encodeToString(pair.first))
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "static_pub", enc.encodeToString(pair.second))
        com.simtether.shared.SecureStore
            .putString(this, "bridge_keys", "pairing_token", enc.encodeToString(token))
        return pair to token
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
                com.simtether.shared.RemoteStore.relay(this) else null,
            relayToken = if (com.simtether.shared.RemoteStore.isEnabled(this))
                com.simtether.shared.RemoteStore.relayToken(this) else null,
        )
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
                SmsSender.send(applicationContext, cmd.address, cmd.body, cmd.requestDeliveryReport, cmd.ref)
            }
            "call.action" -> {
                val cmd = env.payloadAs<Protocol.CallAction>()
                CallController.dispatch(applicationContext, cmd)
            }
            "dial" -> {
                val cmd = env.payloadAs<Protocol.DialRequest>()
                CallController.dial(applicationContext, cmd.number)
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
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, com.simtether.shared.LocaleHelper.wrap(this)
                    .getString(com.simtether.shared.R.string.channel_bridge),
                NotificationManager.IMPORTANCE_LOW)
        )
        val notif = buildNotification(connected = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, foregroundType())
        } else {
            startForeground(NOTIF_ID, notif)
        }
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
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(connected))
    }

    private var clientConnected = false

    /** Re-post in the current language — called after a locale change. */
    fun refreshNotification() = updateNotification(clientConnected)

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
        private const val TAG = "SimTether.Bridge"
        private const val MAX_PENDING = 200
        private const val STATUS_PUSH_MS = 60_000L
        const val EXTRA_EVENT_TYPE = "com.simtether.bridge.EVENT_TYPE"
        const val EXTRA_EVENT_PAYLOAD = "com.simtether.bridge.EVENT_PAYLOAD"

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
