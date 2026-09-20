package com.simtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simtether.bridge.BridgeService
import com.simtether.client.ClientService
import com.simtether.client.PairingStore

/**
 * Reboot survival: restart whichever role this phone was configured
 * for. The bridge especially is an appliance — it must come back on
 * its own after a reboot or OTA.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED + MY_PACKAGE_REPLACED: an install/OTA force-stops
        // the app and kills the service — without this hook the bridge is
        // dead until someone opens the app.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        when (context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("role", null)) {
            "bridge" -> if (BridgeService.isEnabled(context)) {
                Log.d(TAG, "boot: restarting bridge service")
                context.startForegroundService(Intent(context, BridgeService::class.java))
            }
            "client" -> if (PairingStore.isPaired(context)) {
                Log.d(TAG, "boot: restarting client service")
                context.startForegroundService(Intent(context, ClientService::class.java))
            }
        }
    }

    private companion object {
        const val TAG = "SimTether.Boot"
    }
}
