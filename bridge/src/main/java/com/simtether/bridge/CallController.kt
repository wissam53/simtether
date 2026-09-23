package com.simtether.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.simtether.bridge.telecom.BridgeInCallService
import com.simtether.bridge.telecom.CallRegistry
import com.simtether.shared.protocol.Protocol

/**
 * Executes call commands on the bridge's real GSM calls via the
 * InCallService Call objects (requires default dialer role).
 */
object CallController {

    /** Fired when Telecom sends a canned reply on reject — the SMS
     *  bypasses sms.send, so the service records + relays it here. */
    var onRejectSms: ((number: String, text: String) -> Unit)? = null

    // DTMF sequences need timed play/stop pairs — serialize them off
    // the caller's thread so digits can't interleave across calls.
    private val dtmfExec = java.util.concurrent.Executors
        .newSingleThreadExecutor { r -> Thread(r, "dtmf").also { it.isDaemon = true } }
    private const val DTMF_TONE_MS = 120L
    private const val DTMF_GAP_MS = 70L

    fun dispatch(context: Context, cmd: Protocol.CallAction) {
        val call = CallRegistry.byId(cmd.callId) ?: return
        when (cmd.action) {
            Protocol.CallAction.Action.ANSWER ->
                call.answer(VideoProfile.STATE_AUDIO_ONLY)

            Protocol.CallAction.Action.ANSWER_SPEAKER -> {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                BridgeInCallService.speakerOn()
            }

            Protocol.CallAction.Action.REJECT ->
                if (call.state == Call.STATE_RINGING) call.reject(false, null)
                else call.disconnect()

            Protocol.CallAction.Action.REJECT_WITH_SMS -> {
                call.reject(true, cmd.smsTemplate ?: "")
                // Telecom sends the canned reply itself — capture the
                // caller number + text so both phones log it.
                val num = call.details?.handle?.schemeSpecificPart
                val text = cmd.smsTemplate
                if (!num.isNullOrBlank() && !text.isNullOrBlank())
                    onRejectSms?.invoke(num, text)
            }

            Protocol.CallAction.Action.DISCONNECT ->
                call.disconnect()

            Protocol.CallAction.Action.HOLD ->
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.hold()

            Protocol.CallAction.Action.UNHOLD ->
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.unhold()

            Protocol.CallAction.Action.DTMF -> {
                // Telecom requires play/stop pairs — a bare
                // playDtmfTone latches the tone on and later digits
                // never transmit (bank IVRs die here).
                val digits = cmd.digits ?: return
                dtmfExec.execute {
                    for (d in digits) {
                        runCatching { call.playDtmfTone(d) }
                        runCatching { Thread.sleep(DTMF_TONE_MS) }
                        runCatching { call.stopDtmfTone() }
                        runCatching { Thread.sleep(DTMF_GAP_MS) }
                    }
                }
            }

            Protocol.CallAction.Action.AUDIO_ROUTE ->
                cmd.audioRoute?.let { BridgeInCallService.setRoute(it) }
        }
    }

    /** Outgoing dial: bridge places the GSM call on behalf of the client. */
    fun dial(context: Context, number: String) {
        // CALL_PHONE is granted at role entry but the user can revoke
        // it — check rather than let the relay crash mid-command.
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("SimTether.Bridge", "dial: CALL_PHONE not granted")
            return
        }
        // As default dialer, placeCall goes straight to GSM (no UI).
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.parse("tel:$number"), Bundle())
    }
}
