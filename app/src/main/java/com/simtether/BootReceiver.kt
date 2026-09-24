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
        // Entitlement gate — a lapsed subscription must not resurrect
        // the link at boot. (Entitled==null = Play cache hasn't
        // answered yet — allow; Billing's callback stops us if the
        // answer is "not subscribed".)
        if (PairingStore.isPaired(context) &&
            com.simtether.billing.Billing.entitled.value != false) {
            Log.d(TAG, "boot: restarting client service")
            // Background-start limits can reject this on 12+ — crash
            // of a receiver is worse than a missed restart (START_STICKY
            // + the app's own launch path are the recovery).
            runCatching {
                context.startForegroundService(Intent(context, ClientService::class.java))
            }.onFailure { Log.w(TAG, "boot start refused", it) }
        }
    }

    private companion object {
        const val TAG = "SimTether.Boot"
    }
}
