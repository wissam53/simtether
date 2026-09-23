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

        runCatching {
            val parts = mgr.divideMessage(body)
            // One PendingIntent per part — a single intent for an N-part
            // send reports status for part 1 only.
            com.simtether.shared.SendStatusTracker.expect(ref, parts.size)
            if (parts.size == 1) {
                mgr.sendTextMessage(
                    address, null, body,
                    statusIntent(context, ACTION_SENT, ref, 0),
                    if (deliveryReport) statusIntent(context, ACTION_DELIVERED, ref, 0) else null,
                )
            } else {
                mgr.sendMultipartTextMessage(
                    address, null, parts,
                    ArrayList(parts.indices.map { statusIntent(context, ACTION_SENT, ref, it) }),
                    if (deliveryReport)
                        ArrayList(parts.indices.map { statusIntent(context, ACTION_DELIVERED, ref, it) })
                    else null,
                )
            }
            Log.d(TAG, "sent to=$address parts=${parts.size} ref=$ref")
        }.onFailure {
            Log.e(TAG, "send failed to=$address", it)
            report(ref, Protocol.SmsStatus.Status.FAILED, it.message)
        }
    }

    // Per-part requestCode — with FLAG_UPDATE_CURRENT, identical
    // requestCodes collapse every part onto one PendingIntent.
    private fun statusIntent(
        context: Context, action: String, ref: String?, part: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (ref?.hashCode() ?: 0) * 31 + part,
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

    /** The SIM this bridge's traffic belongs on — the active-data sub,
     *  else the first active subscription. Shared with the USSD path
     *  so a dual-SIM bridge runs service codes on the right line. */
    internal fun activeSubId(context: Context): Int? = runCatching {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        // getActiveDataSubscriptionId is API 30+.
        val id = if (android.os.Build.VERSION.SDK_INT >= 30)
            SubscriptionManager.getActiveDataSubscriptionId()
        else SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) id
        // activeSubscriptionInfoList needs READ_PHONE_STATE.
        else if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
        else null
    }.getOrNull()?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    const val ACTION_SENT = "com.simtether.SMS_SENT"
    const val ACTION_DELIVERED = "com.simtether.SMS_DELIVERED"
}
