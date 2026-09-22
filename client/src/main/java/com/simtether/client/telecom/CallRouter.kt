package com.simtether.client.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.simtether.shared.protocol.Protocol

/**
 * Turns bridge call events into native Telecom calls on the main phone.
 * Uses a self-managed ConnectionService (MANAGE_OWN_CALLS — unrestricted
 * permission) + PROPERTY_IS_TETHERED_CALL on API 37.2+.
 */
object CallRouter {
    private const val ACCOUNT_ID = "simtether_bridge"

    fun phoneAccountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, BridgeConnectionService::class.java), ACCOUNT_ID)

    fun ensurePhoneAccount(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java)
        val account = PhoneAccount.builder(phoneAccountHandle(context), "Bridge calls")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        tm.registerPhoneAccount(account)
    }

    fun onCallEvent(context: Context, event: Protocol.CallEvent) {
        val tm = context.getSystemService(TelecomManager::class.java)
        when (event.state) {
            Protocol.CallEvent.State.RINGING -> {
                // Never log the number — logcat is readable by OEM
                // tooling, adb, and anything with root.
                android.util.Log.d("SimTether.CallRouter", "RINGING callId=${event.callId}")
                startRinging(context)
                val extras = Bundle().apply {
                    putParcelable(
                        TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                        Uri.parse("tel:${event.number ?: "unknown"}"),
                    )
                    putString(BridgeConnectionService.EXTRA_CALL_ID, event.callId)
                    (event.displayName ?: event.number?.let {
                        com.simtether.shared.ContactLookup.resolveBlocking(it)
                    })?.let { putString(BridgeConnectionService.EXTRA_NAME, it) }
                }
                ensurePhoneAccount(context)
                runCatching {
                    tm.addNewIncomingCall(phoneAccountHandle(context), extras)
                }.onFailure {
                    android.util.Log.e("SimTether.CallRouter", "addNewIncomingCall failed", it)
                }
                showCallUi(context, incoming = true)
            }
            Protocol.CallEvent.State.DISCONNECTED -> {
                stopRinging()
                BridgeConnectionService.disconnect(event.callId)
                dismissCallUi(context)
            }
            else -> {
                stopRinging()
                // Outgoing path: adopt the bridge's callId for a pending
                // local Connection created by placeCall.
                BridgeConnectionService.adoptOutgoing(event.callId, event.number)
                BridgeConnectionService.updateState(event.callId, event.state)
            }
        }
        if (event.audioRoute != null || event.availableRoutes != null)
            BridgeConnectionService.updateAudio(
                event.callId, event.audioRoute, event.availableRoutes)
    }

    /**
     * Place an outgoing call through our self-managed PhoneAccount —
     * Telecom binds BridgeConnectionService and shows the native
     * in-call UI; the Connection relays the dial to the bridge.
     */
    fun placeOutgoingCall(context: Context, number: String) {
        // MANAGE_OWN_CALLS is requested at role entry, but the user can
        // revoke it in Settings — check rather than crash the dial path.
        if (context.checkSelfPermission(android.Manifest.permission.MANAGE_OWN_CALLS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("SimTether.CallRouter", "placeCall: MANAGE_OWN_CALLS missing")
            return
        }
        ensurePhoneAccount(context)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle(context))
        }
        runCatching {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.parse("tel:$number"), extras)
        }.onFailure {
            android.util.Log.e("SimTether.CallRouter", "placeCall failed", it)
        }
        showCallUi(context, incoming = false)
    }

    private const val CALL_CHANNEL = "calls"
    private const val IN_CALL_CLASS = "com.simtether.InCallActivity"

    /**
     * Self-managed calls have no system UI — show ours. Incoming rings
     * go through a full-screen-intent notification (background activity
     * launches are blocked); outgoing launches directly since the app
     * is foreground when the user dials.
     */
    fun showCallUi(context: Context, incoming: Boolean) {
        // Re-wrap so notification text follows the current language
        // pick even though the caller's context predates it.
        val context = com.simtether.shared.LocaleHelper.wrap(context)
        val intent = android.content.Intent()
            .setClassName(context.packageName, IN_CALL_CLASS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (incoming) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CALL_CHANNEL, context.getString(com.simtether.shared.R.string.nav_calls),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                )
            )
            val pi = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = androidx.core.app.NotificationCompat.Builder(context, CALL_CHANNEL)
                .setSmallIcon(com.simtether.shared.R.drawable.ic_stat_call)
                .setContentTitle(context.getString(com.simtether.shared.R.string.call_incoming))
                .setContentText(context.getString(com.simtether.shared.R.string.call_via_bridge))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .build()
            runCatching { nm.notify(CALL_NOTIF_ID, n) }
                .onFailure { android.util.Log.e("SimTether.CallRouter", "call notif failed", it) }
            // Also try a direct launch — works when app is foreground.
            runCatching { context.startActivity(intent) }
        } else {
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.e("SimTether.CallRouter", "in-call launch failed", it) }
        }
    }

    fun dismissCallUi(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(CALL_NOTIF_ID)
    }

    /** Incoming call that ended unanswered — post a missed-call notification. */
    fun notifyMissedCall(context: Context, event: Protocol.CallEvent) {
        val context = com.simtether.shared.LocaleHelper.wrap(context)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                MISSED_CHANNEL, context.getString(com.simtether.shared.R.string.channel_missed),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent = android.content.Intent()
            .setClassName(context.packageName, "com.simtether.MainActivity")
            .putExtra(EXTRA_OPEN_CALLS, true)
        val pi = android.app.PendingIntent.getActivity(
            context, event.callId.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, MISSED_CHANNEL)
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
    // Self-managed ConnectionServices get no system ringtone — the
    // app plays it. USAGE_NOTIFICATION_RINGTONE respects ringer mode
    // (silent → quiet); vibration runs when not in silent mode.
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

    private const val CALL_NOTIF_ID = 42
    private const val MISSED_CHANNEL = "missed_calls"
    private const val MISSED_NOTIF_ID = 1000
    const val EXTRA_OPEN_CALLS = "com.simtether.OPEN_CALLS"
}
