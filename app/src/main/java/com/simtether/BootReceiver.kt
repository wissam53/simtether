package com.simtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.simtether.client.ClientService
import com.simtether.client.PairingStore

/**
 * Reboot survival: a paired client must come back on its own after a
 * reboot or OTA — the bridge keeps ringing it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED + MY_PACKAGE_REPLACED: an install/OTA force-stops
        // the app and kills the service — without this hook the link is
        // dead until someone opens the app.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (PairingStore.isPaired(context)) {
            Log.d(TAG, "boot: restarting client service")
            context.startForegroundService(Intent(context, ClientService::class.java))
        }
    }

    private companion object {
        const val TAG = "SimTether.Boot"
    }
}
