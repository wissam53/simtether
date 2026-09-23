package com.simtether.bridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.simtether.bridge.BridgeService
import com.simtether.shared.protocol.Protocol

/**
 * RECEIVE_SMS → reassembles PDUs → emits sms.received to the client.
 * Requires RECEIVE_SMS; on Play this rides the default-SMS-handler or
 * companion-device exception.
 */
class SmsRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val ts = messages[0].timestampMillis
        Log.d(TAG, "SMS_RECEIVED from=$address parts=${messages.size}")

        val sms = Protocol.SmsReceived(address, body, ts)
        // Always record locally — this SIM's Messages screen should
        // show complete history regardless of bridge state.
        com.simtether.shared.ConversationStore.init(context)
        com.simtether.shared.ConversationStore.onIncoming(sms)

        // User turned the bridge off — don't resurrect the service;
        // surface the SMS locally instead so the SIM phone stays usable.
        if (!BridgeService.isEnabled(context)) {
            com.simtether.shared.ContactLookup.init(context)
            // Receivers have no attachBaseContext — wrap for the
            // in-app language before producing user-facing strings.
            com.simtether.shared.SmsNotifier.notify(
                com.simtether.shared.LocaleHelper.wrap(context), sms)
            return
        }
        val payload = Protocol.json.encodeToString(
            Protocol.SmsReceived.serializer(), sms,
        )
        // Deliver the event via the start intent — survives the race
        // where the service isn't in memory yet (onStartCommand emits
        // once it's up). Works whether the service is running or not.
        // Android 12+ can refuse the background FGS start until the
        // dialer role / CDM association grants the exemption — park
        // the event instead of crashing the receiver.
        runCatching {
            context.startForegroundService(
                Intent(context, BridgeService::class.java)
                    .putExtra(BridgeService.EXTRA_EVENT_TYPE, "sms.received")
                    .putExtra(BridgeService.EXTRA_EVENT_PAYLOAD, payload)
            )
        }.onFailure {
            Log.w(TAG, "FGS start refused — parking event for next start", it)
            BridgeService.parkEvent(context, "sms.received", payload)
        }
    }

    private companion object {
        const val TAG = "SimTether.SmsRx"
    }
}

/** Service locator until a DI layer lands. */
object BridgeServiceHolder {
    var service: BridgeService? = null
}
