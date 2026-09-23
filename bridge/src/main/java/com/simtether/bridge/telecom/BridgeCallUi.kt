package com.simtether.bridge.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Fallback in-call UI on the bridge phone. We're the default dialer —
 * when no client is linked, incoming GSM calls still need a local UI
 * or they're unanswerable. Same pattern as the client: full-screen
 * intent notification + direct launch when foreground.
 */
object BridgeCallUi {
    private const val CHANNEL = "bridge_calls"
    private const val NOTIF_ID = 43
    private const val ACTIVITY = "com.simtether.BridgeCallActivity"
    // Must match CallRouter's missed-call scheme so the shared Calls
    // screen's dismissMissedCall() clears these too.
    private const val MISSED_CHANNEL = "missed_calls"
    private const val MISSED_NOTIF_ID = 1000
    private const val EXTRA_OPEN_CALLS =
        com.simtether.shared.IntentKeys.EXTRA_OPEN_CALLS

    fun notify(context: Context) {
        val context = com.simtether.shared.LocaleHelper.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, context.getString(com.simtether.shared.R.string.nav_calls),
                NotificationManager.IMPORTANCE_HIGH)
                // Transient ring state — the missed-call notif is the
                // one that should badge afterwards.
                .apply { setShowBadge(false) }
        )
        val intent = Intent()
            .setClassName(context.packageName, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.simtether.shared.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(com.simtether.shared.R.string.call_incoming))
            .setContentText(context.getString(com.simtether.shared.R.string.notif_answer_here))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        // Direct launch works while the app is foreground.
        runCatching { context.startActivity(intent) }
        startRinging(context)
    }

    /** Clear one missed-call notification (Recents viewed it). */
    fun dismissMissed(context: Context, callId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(MISSED_NOTIF_ID + callId.hashCode())
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopRinging()
    }

    /**
     * Missed call while no client was linked — the fallback ring was
     * the only place it surfaced, so leave a local notification that
     * opens the Calls tab.
     */
    fun notifyMissed(context: Context, event: com.simtether.shared.protocol.Protocol.CallEvent) {
        val context = com.simtether.shared.LocaleHelper.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                MISSED_CHANNEL,
                context.getString(com.simtether.shared.R.string.channel_missed),
                NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent()
            .setClassName(context.packageName, "com.simtether.MainActivity")
            .putExtra(EXTRA_OPEN_CALLS, true)
        val pi = PendingIntent.getActivity(
            context, event.callId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(com.simtether.shared.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(com.simtether.shared.R.string.notif_missed_call))
            .setContentText(event.displayName ?: event.number
                ?: context.getString(com.simtether.shared.R.string.unknown))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(MISSED_NOTIF_ID + event.callId.hashCode(), n) }
    }

    // ── Ringing ─────────────────────────────────────────────────
    // Manifest declares IN_CALL_SERVICE_RINGING — Telecom expects the
    // dialer app (us) to play the ringtone, so the system won't.
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: android.os.Vibrator? = null

    private fun startRinging(context: Context) {
        stopRinging()
        val uri = android.media.RingtoneManager
            .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE) ?: return
        ringtone = android.media.RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        val am = context.getSystemService(android.media.AudioManager::class.java)
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_SILENT) {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION")
                context.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0)
            )
            vibrator = v
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.takeIf { it.isPlaying }?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
