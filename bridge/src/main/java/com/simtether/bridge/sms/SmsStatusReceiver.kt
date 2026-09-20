package com.simtether.bridge.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.simtether.shared.protocol.Protocol

/**
 * Catches SmsManager's SENT/DELIVERED result broadcasts and forwards
 * them to the client as sms.status events.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ref = intent.getStringExtra(SmsSender.EXTRA_REF)
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                Log.d(TAG, "SMS_SENT ref=$ref result=$resultCode")
                SmsSender.report(
                    ref,
                    if (ok) Protocol.SmsStatus.Status.SENT else Protocol.SmsStatus.Status.FAILED,
                    if (ok) null else errorName(resultCode),
                )
            }
            SmsSender.ACTION_DELIVERED -> {
                val ok = resultCode == Activity.RESULT_OK
                Log.d(TAG, "SMS_DELIVERED ref=$ref result=$resultCode")
                SmsSender.report(
                    ref,
                    if (ok) Protocol.SmsStatus.Status.DELIVERED else Protocol.SmsStatus.Status.FAILED,
                    if (ok) null else "delivery result $resultCode",
                )
            }
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        else -> "error $code"
    }

    private companion object {
        const val TAG = "SimTether.SmsStatus"
    }
}
