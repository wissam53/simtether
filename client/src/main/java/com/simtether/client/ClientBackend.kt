package com.simtether.client

import android.content.Context
import com.simtether.shared.PhoneBackend

/**
 * Client app's PhoneBackend: generic screens (shared :ui) reach the
 * bridge link through here — sendSms queues offline, dial goes through
 * Telecom's self-managed ConnectionService, missed-call dismissal
 * clears the notification CallRouter posted.
 */
class ClientBackend : PhoneBackend {
    override fun sendSms(address: String, body: String, ref: String?) =
        ClientServiceHolder.sendSms(address, body, ref)

    override fun dial(number: String) =
        ClientServiceHolder.dial(number)

    override fun dismissMissedCall(context: Context, callId: String) =
        com.simtether.client.telecom.CallRouter.dismissMissedCall(context, callId)
}
