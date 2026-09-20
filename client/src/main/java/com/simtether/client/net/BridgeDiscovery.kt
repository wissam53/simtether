package com.simtether.client.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.simtether.shared.Identity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import kotlin.coroutines.resume

/**
 * Resolves the bridge's current LAN address via mDNS. Matches on the
 * key-fingerprinted service name so other SimTether users on the same
 * network are never even probed.
 */
class BridgeDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Returns the bridge's current host:port, or null on timeout.
     * [fingerprint] is Identity.fingerprint(bridgeStaticPub).
     */
    suspend fun resolve(fingerprint: String, timeoutMs: Long = 6_000): InetSocketAddress? =
        withTimeoutOrNull(timeoutMs) { discover(fingerprint) }

    private suspend fun discover(fingerprint: String): InetSocketAddress? =
        suspendCancellableCoroutine { cont ->
            val wanted = "st1-$fingerprint"

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!info.serviceName.startsWith(wanted)) return
                    Log.d(TAG, "found ${info.serviceName}, resolving")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            Log.d(TAG, "resolved ${info.serviceName} -> $host:${info.port}")
                            stop()
                            if (cont.isActive) cont.resume(InetSocketAddress(host, info.port))
                        }
                    })
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "discovering $serviceType for $wanted")
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery start failed: $errorCode")
                    stop()
                    if (cont.isActive) cont.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                fun stop() = runCatching { nsd.stopServiceDiscovery(this) }
            }

            cont.invokeOnCancellation { discoveryListener.stop() }
            runCatching {
                nsd.discoverServices(
                    Identity.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener
                )
            }.onFailure {
                Log.w(TAG, "discoverServices threw", it)
                if (cont.isActive) cont.resume(null)
            }
        }

    private companion object {
        const val TAG = "SimTether.Discovery"
    }
}
