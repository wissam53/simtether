package com.simtether.shared

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the generic screens (Messages/Calls/Dialer/Thread) need from a
 * backend. The client app wires this to ClientServiceHolder; the
 * bridge's local mode wires it to the SIM directly. Defined in shared
 * so :ui never needs :client.
 */
interface PhoneBackend {
    fun sendSms(address: String, body: String, ref: String? = null)
    fun dial(number: String)
    fun dismissMissedCall(context: Context, callId: String)
}

/** UI-facing entry point — screens call these, not a concrete service. */
object UiBackend {
    // Single stable flow: the client service and bridge local mode both
    // drive it, so generic screens collect one source regardless of
    // which backend is installed.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    fun setConnected(v: Boolean) { _connected.value = v }

    @Volatile var impl: PhoneBackend? = null

    fun sendSms(address: String, body: String, ref: String? = null) =
        impl?.sendSms(address, body, ref)

    fun dial(number: String) = impl?.dial(number)

    fun dismissMissedCall(context: Context, callId: String) =
        impl?.dismissMissedCall(context, callId)
}
