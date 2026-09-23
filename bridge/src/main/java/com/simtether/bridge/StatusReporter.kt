package com.simtether.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.simtether.shared.protocol.Protocol

/**
 * Collects bridge telemetry (battery, SIM, carrier, network) and
 * emits bridge.status events. Pushed on client connect, on demand
 * (STATUS_REFRESH), and every 60s while a session is live.
 */
object StatusReporter {

    fun current(context: Context): Protocol.BridgeStatus {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return Protocol.BridgeStatus(
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            simReady = runCatching { tm.simState == TelephonyManager.SIM_STATE_READY }
                .getOrDefault(false),
            carrier = runCatching { tm.networkOperatorName }.getOrNull()
                ?.takeIf { it.isNotBlank() },
            mccMnc = runCatching { tm.networkOperator }.getOrNull()
                ?.takeIf { it.length >= 5 },
            network = detectNetwork(cm),
            // Synchronous since API 28, cellSignalStrengths since 29 —
            // strongest reported cell; null on older devices.
            signalDbm = if (Build.VERSION.SDK_INT >= 29) runCatching {
                tm.signalStrength?.cellSignalStrengths?.maxOfOrNull { it.dbm }
            }.getOrNull() else null,
            deviceName = Build.MODEL,
            ringerMode = runCatching {
                context.getSystemService(AudioManager::class.java).ringerMode
            }.getOrNull(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
        )
    }

    fun emit(context: Context) {
        val status = current(context)
        val payload = Protocol.json.encodeToString(
            Protocol.BridgeStatus.serializer(), status
        )
        // Ephemeral — a stale status must never occupy a queue slot
        // meant for SMS/call events while the client is offline.
        com.simtether.bridge.sms.BridgeServiceHolder.service
            ?.emit("bridge.status", payload, reliable = false)
    }

    /**
     * Hotspot heuristic: when hosting, the WiFi STA is down (no active
     * WiFi network) but wlan0 still carries the AP subnet's private
     * IPv4. Client mode shows WiFi as the active network instead.
     */
    private fun detectNetwork(cm: ConnectivityManager): String {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (onWifi) return "wifi"
        val hasLanAddr = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().any { ni ->
                ni.isUp && !ni.isLoopback &&
                    ni.interfaceAddresses.any { it.address is java.net.Inet4Address }
            }
        }.getOrDefault(false)
        return if (hasLanAddr) "hotspot" else "none"
    }

    fun handleCommand(context: Context, cmd: Protocol.BridgeCommand) {
        Log.d("SimTether.Bridge", "bridge.command: ${cmd.action}")
        when (cmd.action) {
            Protocol.BridgeCommand.Action.STATUS_REFRESH -> {}
            Protocol.BridgeCommand.Action.MUTE -> mute(context)
            Protocol.BridgeCommand.Action.UNMUTE -> setRinger(context, AudioManager.RINGER_MODE_NORMAL)
            Protocol.BridgeCommand.Action.SWITCH_WIFI -> switchWifi(context, cmd.arg)
            Protocol.BridgeCommand.Action.WAKE_UI -> wakeUi(context)
        }
        // Every command ends with a fresh status so the client sees
        // the actual result instead of assuming success.
        emit(context)
    }

    /**
     * RINGER_MODE_SILENT trips DND, which needs notification-policy
     * access — without it the call throws SecurityException. Fall
     * back to vibrate so "mute" still silences the ring.
     */
    private fun mute(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val canSilent = nm.isNotificationPolicyAccessGranted
        setRinger(context, if (canSilent) AudioManager.RINGER_MODE_SILENT
                          else AudioManager.RINGER_MODE_VIBRATE)
    }

    private fun setRinger(context: Context, mode: Int) {
        runCatching {
            context.getSystemService(AudioManager::class.java).ringerMode = mode
        }.onFailure { Log.w("SimTether.Bridge", "setRinger failed", it) }
    }

    /**
     * Apps can't force a WiFi join — the correct API is a network
     * suggestion, which surfaces a system notification the user taps
     * to connect. arg format: "ssid" or "ssid|wpa2-password".
     */
    private fun switchWifi(context: Context, arg: String?) {
        // The wire value is attacker-shapable input — bound it before
        // it reaches WifiManager: SSIDs are ≤32 chars, WPA2
        // passphrases are 8..63, and neither may carry control bytes.
        val ssid = arg?.substringBefore('|')
            ?.takeIf { it.length in 1..32 && it.none { c -> c.code < 0x20 } }
        if (ssid == null) {
            Log.w("SimTether.Bridge", "SWITCH_WIFI: bad ssid arg")
            return
        }
        // WifiNetworkSuggestion is API 29+ — on older builds there's
        // no supported way to nudge a network join, so just report.
        if (Build.VERSION.SDK_INT < 29) {
            Log.w("SimTether.Bridge", "SWITCH_WIFI unsupported on API < 29")
            return
        }
        val pass = arg.substringAfter('|', "")
        if ('|' in arg && pass.length !in 8..63) {
            Log.w("SimTether.Bridge", "SWITCH_WIFI: bad passphrase length")
            return
        }
        val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply { if (pass.isNotBlank()) setWpa2Passphrase(pass) }
            .build()
        val res = context.getSystemService(android.net.wifi.WifiManager::class.java)
            .addNetworkSuggestions(listOf(suggestion))
        Log.d("SimTether.Bridge", "wifi suggestion '$ssid' -> status=$res")
    }

    /** Wake the screen and bring the app forward. */
    private fun wakeUi(context: Context) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        @Suppress("DEPRECATION") // ACQUIRE_CAUSES_WAKEUP needs a bright lock
        pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "simtether:wake",
        ).acquire(3_000)
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) } }
    }
}
