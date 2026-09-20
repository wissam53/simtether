package com.simtether.bridge.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the bridge's LAN IPv4 (hotspot gateway address). */
object LanAddress {

    // AP-mode interface names across OEMs
    private val AP_PREFIXES = listOf("ap", "swlan", "rndis", "bt-pan", "wlan1", "ap_br")
    // Cellular interfaces — never the right answer for LAN pairing
    private val CELL_PREFIXES = listOf("rmnet", "ccmni", "pdp", "wwan", "usb0")

    fun localIpv4(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var lanCandidate: String? = null
        var lastResort: String? = null

        for (iface in ifaces.toList()) {
            if (iface.isLoopback || !iface.isUp) continue
            val name = iface.name.lowercase()
            for (addr in iface.inetAddresses.toList()) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                when {
                    AP_PREFIXES.any { name.startsWith(it) } -> return host
                    CELL_PREFIXES.none { name.startsWith(it) } ->
                        lanCandidate = lanCandidate ?: host
                    else -> lastResort = lastResort ?: host
                }
            }
        }
        return lanCandidate ?: lastResort
    }
}
