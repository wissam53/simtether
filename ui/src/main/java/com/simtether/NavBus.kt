package com.simtether

/** Notification taps → in-app navigation requests (thread deep-links). */
object NavBus {
    val openThread = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val openCalls = kotlinx.coroutines.flow.MutableStateFlow(false)
}
