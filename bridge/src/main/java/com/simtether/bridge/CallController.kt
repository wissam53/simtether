package com.simtether.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.util.Log
import com.simtether.bridge.telecom.BridgeInCallService
import com.simtether.bridge.telecom.CallRegistry
import com.simtether.shared.CallStateBus
import com.simtether.shared.SecureStore
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

    /**
     * Wire numbers are whitelisted, not escaped: digits plus one
     * leading '+'. Anything else ('*', '#', ';', ',') is a carrier
     * service-code shape — an MMI string like *21*num# enables call
     * forwarding on the SIM, which survives re-pairing and is
     * invisible to the client. Reject the whole string rather than
     * strip characters (stripping can join benign halves into a new
     * code).
     */
    private val SAFE_NUMBER = Regex("^\\+?[0-9]{2,20}$")

    /**
     * True when [addr] is safe to dial or send SMS to from the wire.
     * Formatting characters (spaces, dashes, parens, dots) are stripped
     * first — contacts carry them and they can't form an MMI code.
     * '*', '#', ';', ',' are NOT stripped: removing them could join
     * benign halves into a service code.
     */
    fun isSafeNumber(addr: String): Boolean =
        SAFE_NUMBER.matches(addr.trim().filterNot { it in " -()." })

    fun dispatch(context: Context, cmd: Protocol.CallAction) {
        val call = CallRegistry.byId(cmd.callId) ?: run {
            Log.w("SimTether.Bridge", "call.action for unknown call ${cmd.callId}")
            return
        }
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
                // Can't hold → the client already flipped its UI
                // optimistically; push the true state back so the two
                // sides don't diverge.
                else BridgeInCallService.resync(cmd.callId)

            Protocol.CallAction.Action.UNHOLD ->
                if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.unhold()
                else BridgeInCallService.resync(cmd.callId)

            Protocol.CallAction.Action.DTMF -> {
                // Telecom requires play/stop pairs — a bare
                // playDtmfTone latches the tone on and later digits
                // never transmit (bank IVRs die here).
                val digits = cmd.digits
                    ?.filter { it in "0123456789*#" }?.take(32)
                    ?.takeIf { it.isNotEmpty() } ?: return
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
        val n = number.trim().filterNot { it in " -()." }
        // Carrier service codes aren't dialable numbers — route them
        // to the USSD path (opt-in gated) instead of refusing.
        if (Protocol.isServiceCode(n)) { ussd(context, n); return }
        if (!isSafeNumber(n)) {
            Log.w("SimTether.Bridge", "dial: rejected unsafe number shape")
            reportRejected(context, "unsafe")
            return
        }
        // CALL_PHONE is granted at role entry but the user can revoke
        // it — check rather than let the relay crash mid-command.
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("SimTether.Bridge", "dial: CALL_PHONE not granted")
            reportRejected(context, "no_permission")
            return
        }
        // As default dialer, placeCall goes straight to GSM (no UI).
        // fromParts, not Uri.parse: '#' is the URI fragment delimiter —
        // parse("tel:*123#") silently truncates to *123.
        val tm = context.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        // Dual-SIM: pin the outgoing PhoneAccount to the bridge's SIM.
        // Unpinned, an "ask every time" calling preference pops the
        // system account picker on this unattended phone and the call
        // silently stalls.
        val accounts = runCatching { tm.callCapablePhoneAccounts }.getOrNull().orEmpty()
        val subId = com.simtether.bridge.sms.SmsSender.activeSubId(context)
        val handle = handleForSubId(context, accounts, subId)
        if (handle != null) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        } else if (accounts.size > 1 && selectedOutgoingAccount(tm) == null) {
            Log.w("SimTether.Bridge", "dial: no resolvable PhoneAccount — " +
                "a picker would stall on the unattended screen")
            reportRejected(context, "failed")
            return
        }
        runCatching {
            tm.placeCall(Uri.fromParts("tel", n, null), extras)
        }.onFailure {
            Log.e("SimTether.Bridge", "dial: placeCall threw", it)
            reportRejected(context, "failed")
        }
    }

    /** The PhoneAccountHandle bound to [subId]'s SIM — no public
     *  handle→subId API exists, but AOSP encodes the ICCID in the SIM
     *  account's handle id. OEMs that diverge just return null and the
     *  call goes out on the user's default account. */
    // READ_PHONE_STATE backs getActiveSubscriptionInfo; a missing
    // grant throws SecurityException, which the runCatching already
    // degrades to "use the default account".
    @SuppressLint("MissingPermission")
    private fun handleForSubId(
        context: Context,
        accounts: List<android.telecom.PhoneAccountHandle>,
        subId: Int?,
    ): android.telecom.PhoneAccountHandle? {
        if (subId == null) return null
        val iccId = runCatching {
            context.getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.iccId
        }.getOrNull() ?: return null
        return accounts.firstOrNull { it.id == iccId }
    }

    /** The user's standing calling preference, if one is set — the
     *  picker only appears when it's absent AND multiple accounts. */
    // Needs a phone permission — a missing grant throws
    // SecurityException and runCatching degrades to null. The
    // "tel"-scheme getter exists since API 23, so no version branch:
    // the old <29 fallback called userSelectedOutgoingPhoneAccount
    // (API 29+), which silently NoSuchMethodError'd on 26–28.
    @SuppressLint("MissingPermission")
    private fun selectedOutgoingAccount(
        tm: TelecomManager,
    ): android.telecom.PhoneAccountHandle? = runCatching {
        tm.getDefaultOutgoingPhoneAccount("tel")
    }.getOrNull()

    // ── Carrier service codes (USSD/MMI) ─────────────────────────

    /** BridgeService wires this to emit("ussd.result", …) so the
     *  carrier's reply reaches the client that sent the code. */
    var onUssdResult: ((code: String, response: String?, error: String?) -> Unit)? = null

    /** BridgeService wires this to emit("dial.rejected", …) — the
     *  client's local outgoing Connection needs the teardown signal. */
    var onDialRejected: ((reason: String) -> Unit)? = null

    /**
     * Whether the paired client may run *#/MMI codes on this SIM.
     * Default OFF: codes like *21*num# silently enable SIM-level call
     * forwarding — invisible to the remote client and surviving
     * re-pairing. The bridge owner opts in explicitly from settings.
     */
    fun serviceCodesAllowed(context: Context): Boolean =
        SecureStore.getString(context, "features", "service_codes") == "1"

    fun setServiceCodesAllowed(context: Context, allowed: Boolean) {
        SecureStore.putString(context, "features", "service_codes", if (allowed) "1" else "0")
    }

    /**
     * Run [code] as a real USSD request — the carrier's reply text
     * comes back through [onUssdResult] (and the local pad via
     * CallStateBus.padNotice), which is the point: dialing the same
     * string as a GSM call would flash the reply on this unattended
     * screen and the client would never see it.
     */
    fun ussd(context: Context, code: String) {
        val c = code.trim().filterNot { it in " -()." }
        if (!serviceCodesAllowed(context)) {
            Log.w("SimTether.Bridge", "ussd: service codes not enabled")
            reportUssd(context, c, null, "service_codes_off")
            return
        }
        if (!Protocol.isServiceCode(c)) {
            Log.w("SimTether.Bridge", "ussd: rejected unsafe code shape")
            reportUssd(context, c, null, "unsafe")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("SimTether.Bridge", "ussd: CALL_PHONE not granted")
            reportUssd(context, c, null, "no_permission")
            return
        }
        var tm = context.getSystemService(TelephonyManager::class.java)
        // Dual-SIM: run the code on the same sub SMS uses — the
        // "bridge SIM" is the one carrying this product's traffic,
        // not necessarily the default voice sub.
        com.simtether.bridge.sms.SmsSender.activeSubId(context)?.let {
            runCatching { tm = tm?.createForSubscriptionId(it) }
        }
        if (tm == null) {
            reportUssd(context, c, null, "unsupported")
            return
        }
        runCatching {
            tm.sendUssdRequest(c, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence,
                ) = reportUssd(context, c, response.toString(), null)

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int,
                ) = reportUssd(context, c, null, "failed:$failureCode")
            }, Handler(Looper.getMainLooper()))
        }.onFailure {
            Log.e("SimTether.Bridge", "ussd: sendUssdRequest threw", it)
            reportUssd(context, c, null, "failed")
        }
    }

    private fun reportUssd(context: Context, code: String, response: String?, error: String?) {
        // Never log the code itself — MMI strings can embed phone
        // numbers (*21*num# forwarding targets).
        Log.d("SimTether.Bridge", "ussd len=${code.length} -> resp=${response != null} err=$error")
        // Local pad shows the text itself; the wire event carries the
        // machine code so the client can localize.
        val notice = response ?: when {
            error == "service_codes_off" ->
                context.getString(com.simtether.shared.R.string.call_rejected_codes_off)
            error == "no_permission" ->
                context.getString(com.simtether.shared.R.string.call_rejected_no_perm)
            error == "unsafe" ->
                context.getString(com.simtether.shared.R.string.call_rejected_unsafe)
            error != null ->
                context.getString(com.simtether.shared.R.string.ussd_failed)
            else -> null
        }
        CallStateBus.publishPadNotice(notice)
        onUssdResult?.invoke(code, response, error)
    }

    private fun reportRejected(context: Context, reason: String) {
        CallStateBus.publishPadNotice(
            context.getString(
                when (reason) {
                    "no_permission" -> com.simtether.shared.R.string.call_rejected_no_perm
                    "failed" -> com.simtether.shared.R.string.call_failed
                    else -> com.simtether.shared.R.string.call_rejected_unsafe
                }))
        onDialRejected?.invoke(reason)
    }
}
