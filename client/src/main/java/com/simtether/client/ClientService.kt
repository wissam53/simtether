package com.simtether.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.simtether.client.net.BridgeDiscovery
import com.simtether.client.net.BridgeWsClient
import com.simtether.client.telecom.CallRouter
import com.simtether.shared.CallLogStore
import com.simtether.shared.ConversationStore
import com.simtether.shared.Identity
import com.simtether.shared.SmsNotifier
import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Client foreground service: persistent reconnecting WS connection to
 * the bridge over the hotspot LAN. Routes incoming events to SMS
 * notifications / Telecom presentation.
 */
class ClientService : LifecycleService() {

    private var ws: BridgeWsClient? = null
    private var target: String? = null

    // Live call-audio session (rooted bridge relay). Started by the
    // bridge's call.audio event; torn down on call end or link loss —
    // a stale session would hold the mic open, so every exit path
    // closes it.
    private var audioSession: com.simtether.client.audio.ClientAudioSession? = null

    // Single-threaded: preserves event ordering while keeping store
    // writes, contact lookups, and Telecom binder calls off the UI
    // thread. Dispatchers.IO is a pool — ordering would not hold.
    private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val eventScope = CoroutineScope(eventDispatcher)

    // Recently-processed envelope ids — redeliveries (the bridge never
    // got our ack) are re-acked but not re-processed.
    private val seenIds = com.simtether.shared.IdDedup()

    // In-app language override — notification strings resolve through
    // the service context, so it must be wrapped too.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ClientServiceHolder.service = this
        ConversationStore.init(applicationContext)
        CallLogStore.init(applicationContext)
        com.simtether.shared.ContactLookup.init(applicationContext)
        startForegroundWithNotification()
        registerNetCallback()
        connectToBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Re-invoked on re-pair: reload pairing + reconnect with fresh target
        connectToBridge()
        return START_STICKY
    }

    /**
     * Retry the bridge the moment ANY internet-capable network appears.
     * WiFi-only used to leave the relay path asleep: a phone that's
     * cellular-only (the whole point of remote access) never fired the
     * kick and waited out the full backoff after a flap.
     */
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetCallback() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (!ClientServiceHolder.connected.value) {
                    Log.d(TAG, "network up — kicking reconnect")
                    ws?.kick()
                }
            }
        }
        cm.registerNetworkCallback(
            android.net.NetworkRequest.Builder()
                .addCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb,
        )
        netCallback = cb
    }

    private fun connectToBridge() {
        val pairing = PairingStore.load(applicationContext) ?: return // not paired yet
        // A fresh attempt with (possibly) fresh credentials — clear any
        // stale revocation banner from a previous dead pairing.
        ClientServiceHolder.setPairingRevoked(false)

        // Skip if we're already connecting/connected to the same bridge —
        // onCreate + onStartCommand both run on first start and would
        // otherwise spawn a duplicate socket that confuses the server.
        val t = "${pairing.host}:${pairing.port}:${pairing.bridgeStaticPubKey}"
        if (t == target && ws != null) return
        target = t
        ws?.close()
        ws = null
        // Corrupt stored pairing (edited prefs, old-format value) —
        // decode throws and connectToBridge dies into a START_STICKY
        // restart loop. Forget it instead; the user re-scans.
        val pubKey = runCatching {
            java.util.Base64.getDecoder().decode(pairing.bridgeStaticPubKey)
        }.getOrNull()?.takeIf { it.size == 32 } ?: run {
            Log.w(TAG, "stored pairing has a bad pubkey — forgetting it")
            PairingStore.clear(applicationContext)
            return
        }
        ws = BridgeWsClient(
            targetProvider = { resolveTarget(pairing, pubKey) },
            bridgeStaticPub = pubKey,
            // Persistent identity key — the bridge pins it, so the
            // pairing token alone stops being a sufficient credential.
            clientStaticPriv = PairingStore.clientKeyPair(applicationContext).first,
            pairingTokenProvider = {
                // Read fresh each attempt — a pairing.rotate lands
                // between connects, and the token we were built with
                // may already be retired.
                java.util.Base64.getDecoder().decode(
                    PairingStore.load(applicationContext)?.pairingToken ?: "")
            },
            onEvent = { env -> eventScope.launch { handleEvent(env) } },
            onMedia = { pcm -> audioSession?.onDownlink(pcm) },
            onState = { up ->
                ClientServiceHolder.setConnected(up)
                if (!up) stopAudio()
                updateNotification(up)
            },
            onTransport = { viaRelay -> ClientServiceHolder.setViaRelay(viaRelay) },
            onRevoked = { ClientServiceHolder.setPairingRevoked(true) },
        ).also { it.connect() }
    }

    /**
     * Locate the bridge: fast TCP probe of the last-known address,
     * then mDNS rediscovery by key fingerprint. The pinned pubkey is
     * the identity — the IP is just a hint that goes stale.
     *
     * Every result carries the Network's socketFactory so traffic is
     * bound to the LAN the bridge lives on — otherwise Android routes
     * the socket over the default network (cellular) and the private
     * address is unreachable.
     */
    private fun resolveTarget(
        pairing: com.simtether.shared.pairing.PairingPayload,
        pubKey: ByteArray,
    ): com.simtether.client.net.ResolvedTarget? {
        val cachedNet = networkFor(pairing.host)
        if (cachedNet != null && probe(cachedNet, pairing.host, pairing.port)) {
            return com.simtether.client.net.ResolvedTarget(
                pairing.host, pairing.port, cachedNet.socketFactory
            )
        }
        val fp = Identity.fingerprint(pubKey)
        Log.d(TAG, "cached address unreachable, mDNS for fp=$fp")
        val found = runBlocking {
            BridgeDiscovery(applicationContext).resolve(fp)
        } ?: run {
            // Cold-start race: the bridge may have finished booting its
            // WS server during the mDNS window — one cheap re-probe of
            // the cached address before paying a relay dial.
            if (cachedNet != null && probe(cachedNet, pairing.host, pairing.port)) {
                return com.simtether.client.net.ResolvedTarget(
                    pairing.host, pairing.port, cachedNet.socketFactory
                )
            }
            return relayTarget(pairing, pubKey)
        }
        val host = found.hostString ?: return null
        val net = networkFor(host) ?: run {
            Log.w(TAG, "resolved $host but no local network owns that subnet")
            return null
        }
        PairingStore.updateHost(applicationContext, host, found.port)
        Log.d(TAG, "rediscovered bridge at $host:${found.port}")
        return com.simtether.client.net.ResolvedTarget(host, found.port, net.socketFactory)
    }

    /**
     * Remote fallback — opt-in (RemoteStore) and only when the pairing
     * carried a relay address. The relay is a byte splice: the same
     * Noise IK session runs end-to-end through it, so the operator
     * sees ciphertext and timing, never content. socketFactory stays
     * null — public addresses route over the default network.
     */
    private fun relayTarget(
        pairing: com.simtether.shared.pairing.PairingPayload,
        pubKey: ByteArray,
    ): com.simtether.client.net.ResolvedTarget? {
        if (!com.simtether.shared.RemoteStore.isEnabled(applicationContext)) return null
        // Blank in the pairing = the built-in hosted relay; a non-blank
        // value is either the QR-carried address or a custom override.
        val relay = com.simtether.shared.RemoteStore.normalizeRelay(pairing.relay)
            ?: com.simtether.shared.RemoteStore.DEFAULT_RELAY
        // Address may carry a scheme — "wss://host:port" when the relay
        // sits behind TLS termination; bare "host:port" means ws.
        val secure = relay.startsWith("wss://")
        val hostport = relay.substringAfter("://")
        val host = hostport.substringBeforeLast(':', "")
        val port = hostport.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank() || port == null) return null
        // Full-pubkey room id — a 32-bit fp room is collision-mineable
        // (~2^32 keygens) which would let a token holder evict a
        // victim's room.
        val roomId = Identity.roomId(pubKey)
        // Token + ticket ride upgrade headers — query strings land in
        // TLS-terminator access logs (Fly.io edge), headers don't.
        val headers = mutableMapOf<String, String>()
        (pairing.relayToken?.takeIf { it.isNotBlank() }
            ?: com.simtether.shared.RemoteStore.effectiveRelayToken(applicationContext))
            ?.let { headers["x-st-token"] = it }
        // Room ticket — derived from the QR-carried relay secret. The
        // relay rejects /connect without it once the bridge has proven
        // ownership, so a token holder can't occupy a stranger's room.
        pairing.relaySecret?.let { s ->
            headers["x-st-ticket"] = java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString(
                    com.simtether.shared.RelayProof.ticket(
                        java.util.Base64.getDecoder().decode(s), roomId))
        }
        Log.d(TAG, "LAN unreachable — falling back to relay $relay")
        return com.simtether.client.net.ResolvedTarget(
            host, port, null, "/connect/$roomId", viaRelay = true,
            scheme = if (secure) "wss" else "ws", headers = headers)
    }

    /** The Network whose interface owns the subnet containing [host]. */
    private fun networkFor(host: String): android.net.Network? {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val target = runCatching { java.net.InetAddress.getByName(host) }
            .getOrNull() as? java.net.Inet4Address ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any { la ->
                val a = la.address
                a is java.net.Inet4Address && sameSubnet(a, target, la.prefixLength)
            } == true
        }
    }

    private fun sameSubnet(a: java.net.Inet4Address, b: java.net.Inet4Address, prefix: Int): Boolean {
        val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
        val ai = java.nio.ByteBuffer.wrap(a.address).int
        val bi = java.nio.ByteBuffer.wrap(b.address).int
        return (ai and mask) == (bi and mask)
    }

    private fun probe(net: android.net.Network, host: String, port: Int): Boolean = runCatching {
        net.socketFactory.createSocket().use {
            it.connect(java.net.InetSocketAddress(host, port), 1500)
        }
        true
    }.getOrDefault(false)

    /** Settings changed (remote toggle) — retry resolution now. */
    fun reconnect() = ws?.kick()

    /**
     * Remote toggled OFF while a relay link is live — drop the socket
     * so the reconnect lands back on LAN-only paths. A live LAN link
     * is unaffected: remote is only ever a fallback.
     */
    fun applyRemotePref() {
        if (!com.simtether.shared.RemoteStore.isEnabled(applicationContext) &&
            ClientServiceHolder.viaRelay.value) {
            Log.d(TAG, "remote disabled — dropping relay link")
            target = null
            ws?.close()
            ws = null
            connectToBridge()
        }
    }

    override fun onDestroy() {
        ClientServiceHolder.service = null
        stopAudio()
        ClientServiceHolder.setConnected(false)
        netCallback?.let {
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            }
        }
        ws?.close()
        ws = null
        target = null
        eventScope.cancel()
        eventDispatcher.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Dedup → process → ack. The ack goes out only after route()
     * completes, so an event that kills processing is redelivered on
     * the next session. Heartbeats are neither deduped nor acked —
     * they never enter the bridge's pending queue.
     */
    private fun handleEvent(env: Protocol.Envelope) {
        if (env.pv > Protocol.PROTOCOL_VERSION) {
            Log.w(TAG, "bridge speaks newer protocol pv=${env.pv} — update the client")
            ClientServiceHolder.setPeerNewer(true)
        }
        if (env.type != "hb") {
            if (!seenIds.add(env.id)) {
                sendAck(env.id)
                return
            }
        }
        runCatching { route(env) }
            .onFailure { Log.e(TAG, "route ${env.type} failed", it) }
        if (env.type != "hb") sendAck(env.id)
    }

    private fun sendAck(forId: String) {
        val payload = Protocol.json.encodeToString(
            Protocol.Ack.serializer(), Protocol.Ack(forId))
        ws?.sendCommand("ack", payload)
    }

    private fun route(env: Protocol.Envelope) {
        when (env.type) {
            "sms.received" -> {
                val e = env.payloadAs<Protocol.SmsReceived>()
                Log.d(TAG, "sms.received from=${e.address}")
                ConversationStore.onIncoming(e)
                SmsNotifier.notify(applicationContext, e)
            }
            "call.event" -> {
                val e = env.payloadAs<Protocol.CallEvent>()
                Log.d(TAG, "call.event id=${e.callId} state=${e.state} num=${e.number}")
                val entry = CallLogStore.onEvent(e)
                if (entry?.missed == true)
                    CallRouter.notifyMissedCall(applicationContext, e)
                CallRouter.onCallEvent(applicationContext, e)
                // Belt-and-suspenders mic teardown: if the bridge's
                // call.audio(off) was lost, DISCONNECTED still kills
                // the session — a stale one holds the mic open.
                if (e.state == Protocol.CallEvent.State.DISCONNECTED) stopAudio()
            }
            "call.audio" -> {
                // Rooted bridge: its audio relay is up for the call —
                // open (or close) our playback + mic session to match.
                val e = env.payloadAs<Protocol.CallAudio>()
                Log.d(TAG, "call.audio active=${e.active} uplink=${e.uplink}")
                if (e.active) startAudio(e.uplink) else stopAudio()
            }
            "bridge.status" -> {
                val e = env.payloadAs<Protocol.BridgeStatus>()
                StatusBus.publish(e)
            }
            "sms.status" -> {
                val e = env.payloadAs<Protocol.SmsStatus>()
                Log.d(TAG, "sms.status ref=${e.ref} -> ${e.status}")
                ConversationStore.onStatus(e)
            }
            "sms.echo" -> {
                // A message left the SIM outside sms.send (e.g. Telecom
                // canned reply on call reject) — log it in the thread.
                val e = env.payloadAs<Protocol.SmsEcho>()
                ConversationStore.onEcho(e.address, e.body, e.timestamp, e.ref)
            }
            "pairing.rotate" -> {
                // Bridge rotated the token — persist so the NEXT
                // connect authenticates with it. The ack for this
                // event is what retires the old token.
                val e = env.payloadAs<Protocol.PairingRotate>()
                PairingStore.updateToken(applicationContext, e.token)
                Log.d(TAG, "pairing token rotated")
            }
        }
    }

    /**
     * The bridge's rooted audio relay is live — open our half:
     * playback for downlink frames + mic capture for uplink (when the
     * bridge will inject and RECORD_AUDIO is granted).
     */
    private fun startAudio(uplink: Boolean) {
        audioSession?.stop()
        audioSession = com.simtether.client.audio.ClientAudioSession(
            applicationContext, uplink,
            sendPcm = { pcm -> ws?.sendMedia(pcm) },
        ).also { it.start() }
        ClientServiceHolder.setAudioActive(true)
        // Re-publish the live call with the relay badge on.
        com.simtether.shared.CallStateBus.call.value?.let {
            com.simtether.shared.CallStateBus.publish(it.copy(audioRelay = true))
        }
    }

    private fun stopAudio() {
        audioSession?.stop()
        audioSession = null
        ClientServiceHolder.setAudioActive(false)
        com.simtether.shared.CallStateBus.call.value?.let {
            if (it.audioRelay)
                com.simtether.shared.CallStateBus.publish(it.copy(audioRelay = false))
        }
    }

    /** Client→bridge command entry point. */
    fun sendCommand(type: String, payload: String, queueIfOffline: Boolean = false) =
        ws?.sendCommand(type, payload, queueIfOffline)

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, com.simtether.shared.LocaleHelper.wrap(this)
                    .getString(com.simtether.shared.R.string.channel_bridge_link),
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
     * persistent link. connectedDevice has no cap but requires
     * NEARBY_WIFI_DEVICES granted or a CDM association; dataSync is
     * the pre-grant fallback only.
     */
    private fun foregroundType(): Int {
        val nearbyGranted = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (nearbyGranted)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private fun buildNotification(connected: Boolean): Notification {
        // Re-wrap at build time so a language change applies to the
        // next post without restarting the service.
        val ctx = com.simtether.shared.LocaleHelper.wrap(this)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(
                if (connected) com.simtether.shared.R.string.notif_connected
                else com.simtether.shared.R.string.notif_offline
            ))
            .setSmallIcon(com.simtether.shared.R.drawable.ic_stat_simtether)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Channel badge setting is locked at creation — the
            // per-notification flag fixes installs that already have it.
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(connected))
    }

    /** Re-post in the current language — called after a locale change. */
    fun refreshNotification() =
        updateNotification(ClientServiceHolder.connected.value)

    companion object {
        private const val CHANNEL_ID = "client"
        private const val NOTIF_ID = 2
        private const val TAG = "SimTether.ClientSvc"
    }
}
