package com.simtether.bridge.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.simtether.bridge.sms.BridgeServiceHolder
import com.simtether.shared.protocol.Protocol

/**
 * Bound by Telecom while our app holds the default dialer role on the
 * bridge phone — gives us real Call objects for every GSM call.
 * All state changes become call.event envelopes to the client.
 */
class BridgeInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val id = CallRegistry.register(call)
        emit(id, call)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = emit(id, c)
            override fun onDetailsChanged(c: Call, details: Call.Details) = emit(id, c)
        })
    }

    override fun onCallRemoved(call: Call) {
        // Safety net: guarantee a terminal DISCONNECTED reaches the
        // client even if the state callback missed the transition.
        CallRegistry.idOf(call)?.let { id ->
            emit(id, call, forceDisconnected = true)
            lastEvents.remove(id)
        }
        CallRegistry.remove(call)
        // emit() ran while the call was still registered — re-check
        // now that it's gone, or a stale ACTIVE could latch media on.
        val anyActive = CallRegistry.all().any { it.second.state == Call.STATE_ACTIVE }
        BridgeServiceHolder.service?.setCallMedia(anyActive)
        BridgeServiceHolder.service?.updateCallAudio(anyActive)
        super.onCallRemoved(call)
    }

    // Telecom fires onDetailsChanged/onStateChanged/onCallAudioStateChanged
    // liberally — dedupe so the client only sees actual changes.
    private val lastEvents = HashMap<String, Protocol.CallEvent>()

    /** Current GSM-side audio route — changes arrive via onCallAudioStateChanged. */
    private var audioState: CallAudioState? = null

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        audioState = state
        // Re-emit so the client's route picker reflects the new state.
        CallRegistry.all().forEach { (id, call) -> emit(id, call) }
    }

    private fun emit(id: String, call: Call, forceDisconnected: Boolean = false) {
        val d = call.details
        // callDirection is API 29+ — on 26–28 infer from the ringing
        // state (incoming calls pass through RINGING, outgoing don't).
        // 0 = DIRECTION_INCOMING, 1 = DIRECTION_OUTGOING.
        val direction = if (android.os.Build.VERSION.SDK_INT >= 29) d.callDirection
                        else if (call.state == Call.STATE_RINGING) 0 else 1
        val number = d.handle?.schemeSpecificPart
        val event = Protocol.CallEvent(
            callId = id,
            state = if (forceDisconnected) Protocol.CallEvent.State.DISCONNECTED
                    else mapState(call.state, direction),
            number = number,
            displayName = d.callerDisplayName ?: number?.let { n ->
                // Carriers rarely supply callerDisplayName — resolve
                // against this phone's contacts so the fallback UI
                // (and the client) can show a real name. Cache read
                // only; a miss kicks a background lookup that re-emits.
                com.simtether.shared.ContactLookup.cachedName(n) ?: run {
                    kickNameLookup(id, call, n)
                    null
                }
            },
            incoming = direction == 0,
            audioRoute = audioState?.route,
            availableRoutes = audioState?.supportedRouteMask,
        )
        if (lastEvents[id] == event) return
        lastEvents[id] = event
        // Call audio needs the relay's media byte cap (~34KB/s vs the
        // 2KB/s signaling cap). On while any call is ACTIVE; the flag
        // survives reconnects via RelayLink's re-assert.
        val anyActive = CallRegistry.all().any { it.second.state == Call.STATE_ACTIVE }
        BridgeServiceHolder.service?.setCallMedia(anyActive)
        // Rooted build only: spin up/downlink capture + uplink
        // injection for the active call. No-ops on the store build.
        BridgeServiceHolder.service?.updateCallAudio(anyActive)
        // Local call log — the SIM phone's own Calls tab needs recents
        // whether or not a client is linked.
        val entry = com.simtether.shared.CallLogStore.onEvent(event)
        // Bridge-local fallback UI state — when the main phone isn't
        // linked, someone still has to be able to answer this call.
        BridgeCallBus.call.value =
            if (event.state == Protocol.CallEvent.State.DISCONNECTED) null else event
        if (event.state == Protocol.CallEvent.State.RINGING) {
            if (BridgeServiceHolder.service?.clientReady() != true)
                BridgeCallUi.notify(applicationContext)
        } else {
            // Anything past RINGING (answered locally, ended, outgoing)
            // kills the fallback ring/notification — the activity stays.
            BridgeCallUi.dismiss(applicationContext)
            // Unanswered incoming with no client linked: the fallback
            // ring was the only surface, so post a missed-call notif.
            if (entry?.missed == true &&
                BridgeServiceHolder.service?.clientReady() != true) {
                BridgeCallUi.notifyMissed(applicationContext, event)
            }
        }
        val payload = Protocol.json.encodeToString(Protocol.CallEvent.serializer(), event)
        // Unreliable on purpose: a replayed RINGING would phantom-ring a
        // client that was offline for the call (the queued DISCONNECTED
        // arrives later). Stale call state is worse than a dropped one.
        BridgeServiceHolder.service?.emit("call.event", payload, reliable = false)
    }

    private val nameLookupKicked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** One background lookup per number; the result re-emits on main. */
    private fun kickNameLookup(callId: String, call: Call, number: String) {
        if (!nameLookupKicked.add(number)) return
        com.simtether.shared.ContactLookup.resolveAsync(number) { name ->
            if (name == null) return@resolveAsync
            mainHandler.post {
                // Skip if the call already left Telecom's list.
                if (CallRegistry.idOf(call) == callId) emit(callId, call)
            }
        }
    }

    private fun mapState(state: Int, direction: Int): Protocol.CallEvent.State = when (state) {
        Call.STATE_RINGING -> Protocol.CallEvent.State.RINGING
        Call.STATE_DIALING, Call.STATE_CONNECTING -> Protocol.CallEvent.State.DIALING
        Call.STATE_ACTIVE -> Protocol.CallEvent.State.ACTIVE
        Call.STATE_HOLDING -> Protocol.CallEvent.State.HOLDING
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> Protocol.CallEvent.State.DISCONNECTED
        // NEW / SELECT_PHONE_ACCOUNT / AUDIO_PROCESSING / SIMULATED_RINGING:
        // pre-active states — incoming means ringing, outgoing means dialing
        else -> if (direction == Call.Details.DIRECTION_INCOMING)
            Protocol.CallEvent.State.RINGING else Protocol.CallEvent.State.DIALING
    }

    companion object {
        /** Route audio to speaker after answering (answer_speaker action). */
        var instance: BridgeInCallService? = null
            private set

        fun speakerOn() {
            instance?.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }

        /** Route GSM audio: ROUTE_EARPIECE/SPEAKER/BLUETOOTH/WIRED_HEADSET. */
        fun setRoute(route: Int) {
            instance?.setAudioRoute(route)
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Telecom can bind us while BridgeService is down — the contact
        // resolver and local stores need a context regardless.
        com.simtether.shared.ContactLookup.init(applicationContext)
        com.simtether.shared.CallLogStore.init(applicationContext)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
