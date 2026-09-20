package com.simtether.bridge.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the bridge over mDNS so paired clients can rediscover it
 * on any LAN (hotspot or shared WiFi) without a re-pair. The service
 * name embeds the bridge key fingerprint; location is ephemeral,
 * identity is not.
 */
class BridgeAdvertiser(
    context: Context,
    private val serviceName: String,
    private val port: Int,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = this@BridgeAdvertiser.serviceName
            serviceType = com.simtether.shared.Identity.SERVICE_TYPE
            setPort(this@BridgeAdvertiser.port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "advertising as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "registerService threw", it) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "SimTether.Nsd"
    }
}
