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

    /**
     * First byte of a decrypted frame that carries raw call audio
     * instead of a JSON envelope. Envelopes always start with '{' —
     * a media tag keeps audio bytes out of the serializer entirely
     * (no base64/JSON overhead at 50 frames/s).
     *
     * Only sent to peers that advertised "audio" in client.hello:
     * an older peer would JSON-decode the frame, fail the AEAD-path
     * decode, and tear the session down — the caps handshake is what
     * makes this safe to add.
     */
    const val MEDIA_TAG: Byte = 0x01

    /** client.hello capability string: understands MEDIA_TAG frames. */
    const val CAP_AUDIO = "audio"

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

    /**
     * Bridge → client: the rooted bridge's call-audio relay started or
     * stopped for the live call. `active` opens the client's audio
     * session; on false the client tears it down. Old clients ignore
     * the unknown type (no audio, call control still works).
     */
    @Serializable
    data class CallAudio(
        val active: Boolean,
        val downlink: Boolean = true,   // caller's voice is streamed to us
        val uplink: Boolean = true,     // bridge will try to inject our mic
    )

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

    /**
     * Client → bridge, sent once per session right after the Noise
     * handshake: which optional features this client understands.
     * The bridge must not emit a feature's frames to a session that
     * didn't claim it (see MEDIA_TAG — unknown bytes kill old
     * clients' sessions).
     */
    @Serializable
    data class ClientHello(val caps: List<String> = emptyList())

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
