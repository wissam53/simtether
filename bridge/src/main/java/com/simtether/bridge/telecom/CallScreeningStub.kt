package com.simtether.bridge.telecom

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Required component for the dialer role. Pass-through: never blocks —
 * we want calls to ring so the client sees them.
 */
class CallScreeningStub : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().build())
    }
}
