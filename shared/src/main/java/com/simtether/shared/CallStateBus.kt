package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live call state for in-call UIs — published by whichever side owns
 * the connection: the client's ConnectionService (relayed calls) or
 * the bridge's local call UI (SIM-side fallback). InCallScreen renders
 * it on both apps, so it lives in shared.
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

    private val _call = MutableStateFlow<Ui?>(null)
    val call: StateFlow<Ui?> = _call

    fun publish(u: Ui?) { _call.value = u }
}
