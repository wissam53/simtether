package com.simtether.bridge.telecom

import android.telecom.Call

/** Live Call objects ↔ stable callIds for the protocol. */
object CallRegistry {
    private val byId = mutableMapOf<String, Call>()
    private val idByCall = mutableMapOf<Call, String>()

    @Synchronized
    fun register(call: Call): String {
        idByCall[call]?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        byId[id] = call
        idByCall[call] = id
        return id
    }

    @Synchronized fun byId(id: String): Call? = byId[id]
    @Synchronized fun idOf(call: Call): String? = idByCall[call]
    @Synchronized fun all(): List<Pair<String, Call>> = byId.toList()

    @Synchronized
    fun remove(call: Call) {
        idByCall.remove(call)?.let { byId.remove(it) }
    }
}

/**
 * Latest live call for the bridge-local fallback UI — shown when no
 * client is connected, since as default dialer we own the in-call UI.
 */
object BridgeCallBus {
    val call = kotlinx.coroutines.flow.MutableStateFlow<com.simtether.shared.protocol.Protocol.CallEvent?>(null)
}
