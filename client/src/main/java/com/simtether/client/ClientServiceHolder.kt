package com.simtether.client

import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Service locator for UI→service calls until a DI layer lands. */
object ClientServiceHolder {
    var service: ClientService? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    fun setConnected(v: Boolean) {
        _connected.value = v
        // Generic screens (shared :ui) read UiBackend.connected — keep
        // the two in lockstep.
        com.simtether.shared.UiBackend.setConnected(v)
    }

    /** True while the live session rides the relay (internet) rather
     *  than the LAN — surfaced in the status line. */
    private val _viaRelay = MutableStateFlow(false)
    val viaRelay: StateFlow<Boolean> = _viaRelay
    fun setViaRelay(v: Boolean) { _viaRelay.value = v }

    /** Bridge rotated its identity — the stored pairing is dead and
     *  the user must re-scan the new QR. */
    private val _pairingRevoked = MutableStateFlow(false)
    val pairingRevoked: StateFlow<Boolean> = _pairingRevoked
    fun setPairingRevoked(v: Boolean) { _pairingRevoked.value = v }

    /** Bridge speaks a newer wire protocol than we understand —
     *  envelopes still decode (forward tolerance), but the user should
     *  update this app. */
    private val _peerNewer = MutableStateFlow(false)
    val peerNewer: StateFlow<Boolean> = _peerNewer
    fun setPeerNewer(v: Boolean) { _peerNewer.value = v }

    /** Rooted-bridge audio relay is live for the current call —
     *  the call UI shows this as "audio relay: active". */
    private val _audioActive = MutableStateFlow(false)
    val audioActive: StateFlow<Boolean> = _audioActive
    fun setAudioActive(v: Boolean) { _audioActive.value = v }

    fun sendSms(address: String, body: String, ref: String? = null) {
        val payload = Protocol.json.encodeToString(
            Protocol.SmsSend.serializer(),
            Protocol.SmsSend(address, body, ref = ref),
        )
        // SMS is retryable — queue it when the bridge is offline so it
        // sends on reconnect (bubble stays "sending…" until then).
        service?.sendCommand("sms.send", payload, queueIfOffline = true)
    }

    fun sendCallAction(
        callId: String,
        action: Protocol.CallAction.Action,
        smsTemplate: String? = null,
        digits: String? = null,
        audioRoute: Int? = null,
    ) {
        val payload = Protocol.json.encodeToString(
            Protocol.CallAction.serializer(),
            Protocol.CallAction(callId, action, smsTemplate, digits, audioRoute),
        )
        service?.sendCommand("call.action", payload)
    }

    /**
     * UI entry point: place a self-managed outgoing call — Telecom
     * binds our ConnectionService (native UI) and it relays the
     * dial to the bridge via [sendDial].
     */
    fun dial(number: String) {
        val ctx = service ?: return
        com.simtether.client.telecom.CallRouter.placeOutgoingCall(ctx, number)
    }

    /** Raw bridge dial command — called by BridgeConnectionService. */
    fun sendDial(number: String) {
        val payload = Protocol.json.encodeToString(
            Protocol.DialRequest.serializer(),
            Protocol.DialRequest(number),
        )
        service?.sendCommand("dial", payload)
    }

    fun sendBridgeCommand(action: Protocol.BridgeCommand.Action, arg: String? = null) {
        val payload = Protocol.json.encodeToString(
            Protocol.BridgeCommand.serializer(),
            Protocol.BridgeCommand(action, arg),
        )
        service?.sendCommand("bridge.command", payload)
    }
}
