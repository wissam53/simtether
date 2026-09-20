package com.simtether.bridge.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.simtether.shared.protocol.Protocol

/** Sends SMS from the bridge's SIM via SmsManager (SEND_SMS permission). */
object SmsSender {
    private const val TAG = "SimTether.SmsTx"
    const val EXTRA_REF = "ref"

    fun send(context: Context, address: String, body: String, deliveryReport: Boolean, ref: String?) {
        // getSystemService(SmsManager) can return null when there's no
        // context subscription — fall back to the active-data/default sub.
        val mgr = context.getSystemService(SmsManager::class.java)
            ?: activeSubId(context)?.let { SmsManager.getSmsManagerForSubscriptionId(it) }
            ?: @Suppress("DEPRECATION") runCatching { SmsManager.getDefault() }.getOrNull()
        if (mgr == null) {
            Log.e(TAG, "no SmsManager — SIM/subscription not ready?")
            report(ref, Protocol.SmsStatus.Status.FAILED, "no SmsManager")
            return
        }

        val sent = statusIntent(context, ACTION_SENT, ref)
        val delivered = if (deliveryReport) statusIntent(context, ACTION_DELIVERED, ref) else null

        runCatching {
            val parts = mgr.divideMessage(body)
            if (parts.size == 1) {
                mgr.sendTextMessage(address, null, body, sent, delivered)
            } else {
                mgr.sendMultipartTextMessage(
                    address, null, parts,
                    arrayListOf(sent), delivered?.let { arrayListOf(it) },
                )
            }
            Log.d(TAG, "sent to=$address parts=${parts.size} ref=$ref")
        }.onFailure {
            Log.e(TAG, "send failed to=$address", it)
            report(ref, Protocol.SmsStatus.Status.FAILED, it.message)
        }
    }

    private fun statusIntent(context: Context, action: String, ref: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ref?.hashCode() ?: 0,
            Intent(action).setPackage(context.packageName)
                .putExtra(EXTRA_REF, ref),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Status event → client AND local store (bridge-off local mode). */
    fun report(ref: String?, status: Protocol.SmsStatus.Status, error: String? = null) {
        val s = Protocol.SmsStatus(ref, status, error)
        // No matching ref in the local store for client-initiated
        // sends — onStatus no-ops, so this is safe on both paths.
        com.simtether.shared.ConversationStore.onStatus(s)
        val payload = Protocol.json.encodeToString(
            Protocol.SmsStatus.serializer(), s
        )
        BridgeServiceHolder.service?.emit("sms.status", payload)
    }

    private fun activeSubId(context: Context): Int? = runCatching {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val id = SubscriptionManager.getActiveDataSubscriptionId()
        if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) id
        else sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
    }.getOrNull()?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    const val ACTION_SENT = "com.simtether.SMS_SENT"
    const val ACTION_DELIVERED = "com.simtether.SMS_DELIVERED"
}
