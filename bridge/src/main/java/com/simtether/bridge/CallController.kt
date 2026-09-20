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

            Protocol.CallAction.Action.REJECT_WITH_SMS ->
                call.reject(true, cmd.smsTemplate ?: "")

            Protocol.CallAction.Action.DISCONNECT ->
                call.disconnect()

            Protocol.CallAction.Action.HOLD ->
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.hold()

            Protocol.CallAction.Action.UNHOLD ->
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.unhold()

            Protocol.CallAction.Action.DTMF ->
                cmd.digits?.forEach { call.playDtmfTone(it) }

            Protocol.CallAction.Action.AUDIO_ROUTE ->
                cmd.audioRoute?.let { BridgeInCallService.setRoute(it) }
        }
    }

    /** Outgoing dial: bridge places the GSM call on behalf of the client. */
    fun dial(context: Context, number: String) {
        // As default dialer, placeCall goes straight to GSM (no UI).
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.parse("tel:$number"), Bundle())
    }
}
