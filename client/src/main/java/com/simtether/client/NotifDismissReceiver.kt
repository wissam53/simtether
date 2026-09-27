package com.simtether.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android 14+ lets the user swipe-dismiss even an ongoing FGS
 * notification. The status notice shows this phone is holding a live
 * link to the bridge — re-post it immediately. If the service itself
 * is gone (shouldn't happen — a dead FGS removes its notification
 * anyway), restart it so the link never runs without its notice.
 */
class NotifDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClientService.ACTION_NOTIF_DISMISSED) return
        // A stopped link owns no notification — a stray dismiss
        // broadcast must not resurrect it.
        if (!ClientService.isEnabled(context)) return
        val svc = ClientServiceHolder.service
        if (svc != null) {
            Log.w("SimTether.ClientSvc", "status notification dismissed — re-posting")
            svc.refreshNotification()
        } else {
            Log.w("SimTether.ClientSvc",
                "status notification dismissed with dead service — restarting")
            runCatching {
                // startForegroundService, not startService — a plain
                // start leaves the service background (killed fast)
                // AND violates the notice contract. Notification
                // interaction is a documented background-start
                // exemption, so this is allowed even on 12+. A dead
                // service no-ops when unpaired or unentitled.
                context.startForegroundService(Intent(context, ClientService::class.java))
            }.onFailure { Log.e("SimTether.ClientSvc", "restart after dismiss failed", it) }
        }
    }
}
