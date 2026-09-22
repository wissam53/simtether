package com.simtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simtether.bridge.BridgeService

/**
 * Reboot survival: the bridge is an appliance — it must come back on
 * its own after a reboot or OTA.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED + MY_PACKAGE_REPLACED: an install/OTA force-stops
        // the app and kills the service — without this hook the bridge is
        // dead until someone opens the app.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (BridgeService.isEnabled(context)) {
            Log.d(TAG, "boot: restarting bridge service")
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }

    private companion object {
        const val TAG = "SimTether.Boot"
    }
}
