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
import androidx.lifecycle.lifecycleScope
import com.simtether.client.net.BridgeDiscovery
import com.simtether.client.net.BridgeWsClient
import com.simtether.client.telecom.CallRouter
import com.simtether.shared.CallLogStore
import com.simtether.shared.ConversationStore
import com.simtether.shared.Identity
import com.simtether.shared.SmsNotifier
import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Client foreground service: persistent reconnecting WS connection to
 * the bridge over the hotspot LAN. Routes incoming events to SMS
 * notifications / Telecom presentation.
 */
class ClientService : LifecycleService() {

    private var ws: BridgeWsClient? = null
    private var target: String? = null

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

    /** Retry the bridge the moment a WiFi/LAN transport appears. */
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetCallback() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (!ClientServiceHolder.connected.value) {
                    Log.d(TAG, "wifi transport up — kicking reconnect")
                    ws?.kick()
                }
            }
        }
        cm.registerNetworkCallback(
            android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            cb,
        )
        netCallback = cb
    }

    private fun connectToBridge() {
        val pairing = PairingStore.load(applicationContext) ?: return // not paired yet

        // Skip if we're already connecting/connected to the same bridge —
        // onCreate + onStartCommand both run on first start and would
        // otherwise spawn a duplicate socket that confuses the server.
        val t = "${pairing.host}:${pairing.port}:${pairing.bridgeStaticPubKey}"
        if (t == target && ws != null) return
        target = t
        ws?.close()
        ws = null
        val pubKey = java.util.Base64.getDecoder().decode(pairing.bridgeStaticPubKey)
        ws = BridgeWsClient(
            targetProvider = { resolveTarget(pairing, pubKey) },
            bridgeStaticPub = pubKey,
            pairingToken = java.util.Base64.getDecoder().decode(pairing.oneTimeToken),
            onEvent = { env -> lifecycleScope.launch { route(env) } },
            onState = { up ->
                ClientServiceHolder.setConnected(up)
                updateNotification(up)
            },
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
        } ?: return null
        val host = found.hostString ?: return null
        val net = networkFor(host) ?: run {
            Log.w(TAG, "resolved $host but no local network owns that subnet")
            return null
        }
        PairingStore.updateHost(applicationContext, host, found.port)
        Log.d(TAG, "rediscovered bridge at $host:${found.port}")
        return com.simtether.client.net.ResolvedTarget(host, found.port, net.socketFactory)
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

    override fun onDestroy() {
        ClientServiceHolder.service = null
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
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
        )
        val notif = buildNotification(connected = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
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
