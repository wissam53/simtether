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

    fun notify(context: Context) {
        val context = com.simtether.shared.LocaleHelper.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, context.getString(com.simtether.shared.R.string.nav_calls),
                NotificationManager.IMPORTANCE_HIGH)
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
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        // Direct launch works while the app is foreground.
        runCatching { context.startActivity(intent) }
        startRinging(context)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopRinging()
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
