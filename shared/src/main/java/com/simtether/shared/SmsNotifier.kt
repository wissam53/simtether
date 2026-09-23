package com.simtether.shared

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.simtether.shared.protocol.Protocol

/**
 * SMS events → notifications. Shared module: the client posts these
 * for relayed SMS; the bridge posts them for local SMS when running
 * the SIM phone's own UI (bridge off).
 */
object SmsNotifier {
    private const val CHANNEL_ID = "sms"

    fun notify(context: Context, sms: Protocol.SmsReceived) {
        // Re-wrap so the notification follows the current language
        // pick even when the caller's context predates it.
        val context = LocaleHelper.wrap(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.nav_messages),
                NotificationManager.IMPORTANCE_HIGH)
        )
        val title = ContactLookup.nameFor(sms.address) ?: sms.address
        val intent = android.content.Intent()
            .setClassName(context.packageName, "com.simtether.MainActivity")
            .putExtra(EXTRA_OPEN_THREAD, sms.address)
        val pi = android.app.PendingIntent.getActivity(
            context, sms.address.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        // Lockscreen-safe public version — forwarded SMS are often OTPs;
        // a locked device shows only the sender, never the body.
        val pub = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_new_message))
            .setSmallIcon(R.drawable.ic_stat_simtether)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(
            sms.address.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(sms.body)
                .setSmallIcon(R.drawable.ic_stat_simtether)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .build(),
        )
    }

    /** Drop this thread's notification — the user just read it. */
    fun dismiss(context: Context, address: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(address.hashCode())
    }

    const val EXTRA_OPEN_THREAD = "com.simtether.OPEN_THREAD"
}
