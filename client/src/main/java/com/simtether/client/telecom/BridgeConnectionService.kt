package com.simtether.client.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.simtether.client.ClientServiceHolder
import com.simtether.shared.CallStateBus
import com.simtether.shared.protocol.Protocol

/**
 * Self-managed ConnectionService: each bridge call becomes a native
 * Connection → system incoming-call UI, audio focus, recents.
 * Actions taken on the native UI flow back through onAnswer/onReject
 * and are relayed to the bridge as CallAction commands.
 */
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
        // Name resolves off-thread — a ContentProvider query has no
        // business on this binder→main callback.
        val conn = bridgeConnection(localId, address, null, incoming = false)
        if (number != null) com.simtether.shared.ContactLookup.resolveAsync(number) { name ->
            if (name == null) return@resolveAsync
            mainHandler.post {
                conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
                CallStateBus.call.value?.let { ui ->
                    if (ui.callId == localId) CallStateBus.publish(ui.copy(name = name))
                }
            }
        }
        if (number != null) pendingOutgoing[number] = localId
        // Tell the bridge to actually place the GSM call.
        if (number != null) com.simtether.client.ClientServiceHolder.sendDial(number)
        return conn
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                // PROPERTY_IS_TETHERED_CALL (API 37.2+) is deliberately
                // NOT set: the API forbids it on self-managed
                // connections — that model is for system-managed
                // companion calls, not ours.
                if (incoming) setRinging() else setDialing()
                connectionCapabilities = connectionCapabilities or
                    Connection.CAPABILITY_SUPPORT_HOLD
                // Without this the system incoming-call UI hides the
                // "reply with message" affordance entirely.
                if (incoming && android.os.Build.VERSION.SDK_INT >= 30)
                    connectionCapabilities = connectionCapabilities or
                        Connection.CAPABILITY_RESPOND_VIA_TEXT
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
                        drop(callId)
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
        // Touched from Telecom binder threads, the client event
        // dispatcher, and the main handler — plain HashMap writes can
        // corrupt the table under concurrency.
        private val calls = java.util.concurrent.ConcurrentHashMap<String, Connection>()
        private val pendingOutgoing = java.util.concurrent.ConcurrentHashMap<String, String>() // number -> localId
        private val aliases = java.util.concurrent.ConcurrentHashMap<String, String>() // localId -> bridgeCallId

        /** Remove a call and any localId↔bridgeCallId alias touching it. */
        private fun drop(callId: String): Connection? {
            aliases.entries.removeIf { it.key == callId || it.value == callId }
            return calls.remove(callId)
        }

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
            drop(callId)?.let {
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
