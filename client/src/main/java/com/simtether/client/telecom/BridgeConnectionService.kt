package com.simtether.client.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.simtether.client.ClientServiceHolder
import com.simtether.shared.protocol.Protocol

/**
 * Self-managed ConnectionService: each bridge call becomes a native
 * Connection → system incoming-call UI, audio focus, recents.
 * Actions taken on the native UI flow back through onAnswer/onReject
 * and are relayed to the bridge as CallAction commands.
 */
/**
 * Live call state for the in-call UI — the ConnectionService feeds it,
 * InCallActivity renders it.
 */
object CallStateBus {
    data class Ui(
        val callId: String,
        val number: String?,
        val name: String?,
        val state: Protocol.CallEvent.State,
        val incoming: Boolean,
        // GSM-side audio routing on the bridge (CallAudioState.ROUTE_*).
        val audioRoute: Int? = null,
        val availableRoutes: Int? = null,
    )

    private val _call = kotlinx.coroutines.flow.MutableStateFlow<Ui?>(null)
    val call: kotlinx.coroutines.flow.StateFlow<Ui?> = _call

    fun publish(u: Ui?) { _call.value = u }
}

class BridgeConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        val callId = request.extras.getString(EXTRA_CALL_ID) ?: return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR)
        )
        val address = request.extras.getParcelable<android.net.Uri>(
            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS
        )
        return bridgeConnection(callId, address, request.extras.getString(EXTRA_NAME), incoming = true)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        // Local callId — replaced by the bridge's id when its first
        // call.event arrives (matched by number in adoptOutgoing).
        val localId = "local-" + java.util.UUID.randomUUID().toString()
        val address = request.address
        val number = address?.schemeSpecificPart
        val name = number?.let { com.simtether.shared.ContactLookup.resolveBlocking(it) }
        val conn = bridgeConnection(localId, address, name, incoming = false)
        if (number != null) pendingOutgoing[number] = localId
        // Tell the bridge to actually place the GSM call.
        if (number != null) com.simtether.client.ClientServiceHolder.sendDial(number)
        return conn
    }

    private fun bridgeConnection(
        callId: String,
        address: android.net.Uri?,
        name: String?,
        incoming: Boolean,
    ): Connection {
        val number = address?.schemeSpecificPart
        fun publish(state: Protocol.CallEvent.State) {
            CallStateBus.publish(CallStateBus.Ui(callId, number, name, state, incoming))
        }
        return object : Connection() {
            init {
                setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
                name?.let { setCallerDisplayName(it, TelecomManager.PRESENTATION_ALLOWED) }
                connectionProperties = connectionProperties or
                    Connection.PROPERTY_SELF_MANAGED
                // API 37.2+: PROPERTY_IS_TETHERED_CALL — marks this as a
                // call physically handled on the bridge. TODO once the
                // constant ships in a compileSdk: set it here.
                if (incoming) setRinging() else setDialing()
                connectionCapabilities = connectionCapabilities or
                    Connection.CAPABILITY_SUPPORT_HOLD
                calls[callId] = this
                publish(if (incoming) Protocol.CallEvent.State.RINGING
                        else Protocol.CallEvent.State.DIALING)
            }

            override fun onStateChanged(state: Int) {
                when (state) {
                    Connection.STATE_ACTIVE -> publish(Protocol.CallEvent.State.ACTIVE)
                    Connection.STATE_HOLDING -> publish(Protocol.CallEvent.State.HOLDING)
                    Connection.STATE_DISCONNECTED -> {
                        publish(Protocol.CallEvent.State.DISCONNECTED)
                        calls.remove(callId)
                        CallStateBus.publish(null)
                    }
                    else -> Unit
                }
            }

            override fun onAnswer(videoState: Int) {
                relay(callId, Protocol.CallAction.Action.ANSWER)
                setActive()
            }

            override fun onReject() {
                relay(callId, Protocol.CallAction.Action.REJECT)
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
            }

            override fun onReject(replyMessage: String?) {
                relay(callId, Protocol.CallAction.Action.REJECT_WITH_SMS, replyMessage)
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
            }

            override fun onDisconnect() {
                relay(callId, Protocol.CallAction.Action.DISCONNECT)
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }

            override fun onHold() {
                relay(callId, Protocol.CallAction.Action.HOLD)
                setOnHold()
            }

            override fun onUnhold() {
                relay(callId, Protocol.CallAction.Action.UNHOLD)
                setActive()
            }

            override fun onPlayDtmfTone(c: Char) {
                relay(callId, Protocol.CallAction.Action.DTMF, digits = c.toString())
            }
        }
    }

    private fun relay(
        callId: String,
        action: Protocol.CallAction.Action,
        sms: String? = null,
        digits: String? = null,
    ) {
        // Outgoing connections are created under a local-* id and
        // re-keyed to the bridge's callId by adoptOutgoing — translate.
        ClientServiceHolder.sendCallAction(aliases[callId] ?: callId, action, sms, digits)
    }

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_NAME = "displayName"
        private val calls = mutableMapOf<String, Connection>()
        private val pendingOutgoing = mutableMapOf<String, String>() // number -> localId
        private val aliases = mutableMapOf<String, String>() // localId -> bridgeCallId

        /**
         * Bridge reports a call we dialed locally — re-key the local
         * Connection under the bridge's real callId so actions relay.
         */
        fun adoptOutgoing(bridgeCallId: String, number: String?) {
            if (calls.containsKey(bridgeCallId)) return
            val localId = pendingOutgoing[number] ?: pendingOutgoing.keys
                .firstOrNull() // fallback: only one outgoing at a time is sane
            if (localId != null) {
                pendingOutgoing.values.remove(localId)
                calls[localId]?.let { calls[bridgeCallId] = it }
                calls.remove(localId)
                aliases[localId] = bridgeCallId
                CallStateBus.call.value?.let { ui ->
                    if (ui.callId == localId)
                        CallStateBus.publish(ui.copy(callId = bridgeCallId))
                }
            }
        }

        fun disconnect(callId: String) {
            calls.remove(callId)?.let {
                it.setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
                it.destroy()
            }
        }

        fun updateState(callId: String, state: Protocol.CallEvent.State) {
            val c = calls[callId] ?: return
            when (state) {
                Protocol.CallEvent.State.ACTIVE -> c.setActive()
                Protocol.CallEvent.State.HOLDING -> c.setOnHold()
                else -> Unit
            }
        }

        /** Bridge reported the GSM audio route / available routes changed. */
        fun updateAudio(callId: String, route: Int?, available: Int?) {
            CallStateBus.call.value?.let { ui ->
                // ui.callId may be the pre-adoption local-* id — check aliases.
                if (ui.callId == callId || aliases[ui.callId] == callId)
                    CallStateBus.publish(ui.copy(audioRoute = route, availableRoutes = available))
            }
        }
    }
}
