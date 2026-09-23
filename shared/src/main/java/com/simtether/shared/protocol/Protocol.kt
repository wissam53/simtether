package com.simtether.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire protocol between bridge (SIM phone) and client (main phone).
 * All frames are JSON envelopes; payloads are sealed types below.
 * Transport: WebSocket inside the encrypted session (see crypto/).
 */
object Protocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    const val WS_PORT = 44710

    /** Wire format version — bump on breaking envelope/payload changes.
     *  Carried on every frame so either side can reject a peer it
     *  can't understand instead of failing silently. */
    const val PROTOCOL_VERSION = 1

    // ── Envelope ──────────────────────────────────────────────────

    @Serializable
    data class Envelope(
        val id: String,          // uuid — dedup + acks
        val type: String,        // discriminator matching payload type
        val seq: Long,           // per-sender monotonic sequence
        val payload: String,     // serialized payload (inner JSON)
        val pv: Int = PROTOCOL_VERSION,
    ) {
        inline fun <reified T> payloadAs(): T = json.decodeFromString(payload)
    }

    // ── Bridge → Client events ────────────────────────────────────

    @Serializable
    data class SmsReceived(
        val address: String,
        val body: String,
        val timestamp: Long,
        val subId: Int = -1,
    )

    /** A message that left the SIM outside sms.send — e.g. Telecom's
     *  canned reply on REJECT_WITH_SMS. Echoed so the client's thread
     *  shows what actually went out. */
    @Serializable
    data class SmsEcho(
        val address: String,
        val body: String,
        val timestamp: Long,
        val ref: String? = null,    // correlates the follow-up sms.status
    )

    @Serializable
    data class CallEvent(
        val callId: String,
        val state: State,
        val number: String?,       // may be withheld
        val displayName: String? = null,
        val incoming: Boolean = false,   // GSM call direction
        // GSM-side audio routing (CallAudioState.ROUTE_* bit values).
        val audioRoute: Int? = null,
        val availableRoutes: Int? = null,
    ) {
        enum class State { RINGING, DIALING, ACTIVE, HOLDING, DISCONNECTED }
    }

    @Serializable
    data class BridgeStatus(
        val batteryPct: Int,
        val simReady: Boolean,
        val carrier: String?,
        val mccMnc: String?,      // for carrier-aware cost disclosure
        val network: String? = null,   // "hotspot" | "wifi" | "none"
        val signalDbm: Int? = null,
        val deviceName: String? = null,
        val ringerMode: Int? = null,   // AudioManager.RINGER_MODE_*
        val appVersion: String? = null, // bridge APK versionName — stale-bridge warnings
    )

    @Serializable
    data class Ack(val forId: String)

    /** Bridge → client: next session's pairing token. Sent reliable —
     *  the bridge promotes it to current only once acked (or once the
     *  client authenticates with it), so a mid-rotation link drop
     *  can't brick the pairing. */
    @Serializable
    data class PairingRotate(val token: String)   // base64

    // ── Client → Bridge commands ──────────────────────────────────

    @Serializable
    data class SmsSend(
        val address: String,
        val body: String,
        val requestDeliveryReport: Boolean = true,
        val ref: String? = null,    // client-chosen id, echoed in sms.status
    )

    @Serializable
    data class SmsStatus(
        val ref: String?,
        val status: Status,
        val error: String? = null,
    ) {
        enum class Status { SENT, DELIVERED, FAILED, UNCONFIRMED }
    }

    @Serializable
    data class CallAction(
        val callId: String,
        val action: Action,
        val smsTemplate: String? = null,  // for REJECT_WITH_SMS
        val digits: String? = null,       // for DTMF
        val audioRoute: Int? = null,      // for AUDIO_ROUTE — CallAudioState.ROUTE_*
    ) {
        enum class Action { ANSWER, ANSWER_SPEAKER, REJECT, REJECT_WITH_SMS, DISCONNECT, HOLD, UNHOLD, DTMF, AUDIO_ROUTE }
    }

    @Serializable
    data class DialRequest(val number: String)

    /** Client → bridge housekeeping commands (remote control surface). */
    @Serializable
    data class BridgeCommand(
        val action: Action,
        val arg: String? = null,
    ) {
        enum class Action {
            STATUS_REFRESH,   // bridge re-emits bridge.status now
            MUTE,             // set bridge ringer silent
            UNMUTE,
            SWITCH_WIFI,      // leave hotspot, join pre-approved WiFi (suggestion flow)
            WAKE_UI,          // show the bridge's screen (if reachable)
        }
    }

    // ── Codec helpers ─────────────────────────────────────────────

    fun encode(env: Envelope): String = json.encodeToString(Envelope.serializer(), env)
    fun decode(raw: String): Envelope = json.decodeFromString(Envelope.serializer(), raw)

    inline fun <reified T> wrap(id: String, type: String, seq: Long, payload: T): Envelope =
        Envelope(id, type, seq, json.encodeToString(payload))
}
