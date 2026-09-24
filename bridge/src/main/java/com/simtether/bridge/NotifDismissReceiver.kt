package com.simtether.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android 14+ lets the user swipe-dismiss even an ongoing FGS
 * notification. The status notice is the device owner's only
 * always-visible "SMS/calls are being relayed" signal — re-post it
 * immediately. If the service itself is gone (shouldn't happen — a
 * dead FGS removes its notification anyway), restart it so the
 * bridge never runs without its notice.
 */
class NotifDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BridgeService.ACTION_NOTIF_DISMISSED) return
        val svc = com.simtether.bridge.sms.BridgeServiceHolder.service
        if (svc != null) {
            Log.w("SimTether.Bridge", "status notification dismissed — re-posting")
            svc.refreshNotification()
        } else {
            Log.w("SimTether.Bridge",
                "status notification dismissed with dead service — restarting")
            runCatching {
                // startForegroundService, not startService — a plain
                // start leaves the service background (killed fast)
                // AND violates the notice contract. Notification
                // interaction is a documented background-start
                // exemption, so this is allowed even on 12+.
                context.startForegroundService(Intent(context, BridgeService::class.java))
            }.onFailure { Log.e("SimTether.Bridge", "restart after dismiss failed", it) }
        }
    }
}
