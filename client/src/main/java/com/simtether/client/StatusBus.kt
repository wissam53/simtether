package com.simtether.client

import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Latest bridge status for UI consumption. */
object StatusBus {
    private val _status = MutableStateFlow<Protocol.BridgeStatus?>(null)
    val status: StateFlow<Protocol.BridgeStatus?> = _status

    fun publish(s: Protocol.BridgeStatus) { _status.value = s }
}
