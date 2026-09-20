package com.simtether

import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simtether.shared.R
import com.simtether.client.ClientServiceHolder
import com.simtether.client.telecom.CallStateBus
import com.simtether.shared.protocol.Protocol
import com.simtether.ui.CallColors

/**
 * Self-managed calls get no system UI — this activity is ours.
 * Launched over the lockscreen for incoming rings, or in-app for
 * outgoing dials. Actions relay to the bridge via call.action.
 */
class InCallActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark surface — light status bar icons.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            // Call UI is always dark by convention — but on-brand dark.
            MaterialTheme(colorScheme = com.simtether.ui.SimTetherDarkColors) {
                val call by CallStateBus.call.collectAsState()
                // Connection creation is async — don't finish on the
                // initial null, only when a shown call has ended.
                var seen by remember { mutableStateOf(false) }
                if (call != null) seen = true
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CallColors.Surface,
                ) {
                    when {
                        call != null -> InCallScreen(
                            call!!, onDone = { finish() },
                            send = { cmd ->
                                ClientServiceHolder.sendCallAction(
                                    cmd.callId, cmd.action, cmd.smsTemplate,
                                    cmd.digits, cmd.audioRoute)
                            },
                        )
                        seen -> finish()
                        else -> Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(stringResource(R.string.call_connecting),
                                color = CallColors.Secondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared in-call UI. `send` abstracts the action target: the client
 * relays over the bridge link, the bridge's fallback UI dispatches
 * locally via CallController.
 */
@Composable
fun InCallScreen(
    call: CallStateBus.Ui,
    onDone: () -> Unit,
    send: (Protocol.CallAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Identity block ────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(56.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(CallColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val label = call.name ?: call.number
                val initial = label?.firstOrNull()?.uppercase()
                if (initial != null)
                    Text(initial, style = MaterialTheme.typography.headlineLarge,
                        color = CallColors.Secondary)
                else
                    Icon(Icons.Filled.Person, null,
                        tint = CallColors.Secondary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                call.name ?: call.number ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.headlineMedium,
                color = CallColors.OnSurface,
                textAlign = TextAlign.Center,
            )
            val num = call.number
            if (call.name != null && num != null)
                Text(num, style = MaterialTheme.typography.bodyMedium,
                    color = CallColors.Secondary)
            Spacer(Modifier.height(10.dp))
            Text(
                when (call.state) {
                    Protocol.CallEvent.State.RINGING -> stringResource(
                        if (call.incoming) R.string.call_incoming
                        else R.string.call_ringing)
                    Protocol.CallEvent.State.DIALING -> stringResource(R.string.call_dialing)
                    Protocol.CallEvent.State.ACTIVE -> stringResource(R.string.call_active)
                    Protocol.CallEvent.State.HOLDING -> stringResource(R.string.call_holding)
                    Protocol.CallEvent.State.DISCONNECTED -> stringResource(R.string.call_ended)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (call.state == Protocol.CallEvent.State.RINGING && call.incoming)
                    CallColors.AnswerAccent else CallColors.Secondary,
            )
            Text(stringResource(R.string.call_via_bridge),
                style = MaterialTheme.typography.labelSmall,
                color = CallColors.Tertiary, modifier = Modifier.padding(top = 4.dp))
        }

        // ── Actions ───────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (call.state) {
                Protocol.CallEvent.State.RINGING -> if (call.incoming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CallActionButton(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.call_decline),
                            bg = CallColors.Decline,
                        ) {
                            send(Protocol.CallAction(
                                call.callId, Protocol.CallAction.Action.REJECT))
                            onDone()
                        }
                        CallActionButton(
                            icon = Icons.Filled.Call,
                            label = stringResource(R.string.call_answer),
                            bg = CallColors.Answer,
                        ) {
                            send(Protocol.CallAction(
                                call.callId, Protocol.CallAction.Action.ANSWER))
                        }
                    }
                } else EndCallButton(call.callId, onDone, send)

                Protocol.CallEvent.State.DIALING,
                Protocol.CallEvent.State.ACTIVE,
                Protocol.CallEvent.State.HOLDING -> {
                    AudioRouteButtons(call, send)
                    Spacer(Modifier.height(24.dp))
                    if (call.state != Protocol.CallEvent.State.DIALING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            if (call.state == Protocol.CallEvent.State.HOLDING)
                                SecondaryButton(
                                    Icons.Filled.PlayArrow,
                                    stringResource(R.string.call_resume)) {
                                    send(Protocol.CallAction(
                                        call.callId, Protocol.CallAction.Action.UNHOLD))
                                }
                            else
                                SecondaryButton(
                                    Icons.Filled.Pause,
                                    stringResource(R.string.call_hold)) {
                                    send(Protocol.CallAction(
                                        call.callId, Protocol.CallAction.Action.HOLD))
                                }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    EndCallButton(call.callId, onDone, send)
                }

                Protocol.CallEvent.State.DISCONNECTED -> {
                    Spacer(Modifier.height(24.dp))
                    SecondaryButton(
                        Icons.Filled.CallEnd, stringResource(R.string.call_close),
                        bg = CallColors.SurfaceVariant) {
                        onDone()
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Big round primary action — Answer / Decline / End. */
@Composable
private fun CallActionButton(
    icon: ImageVector, label: String, bg: Color, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(bg),
        ) {
            Icon(icon, label, tint = CallColors.OnAction, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = CallColors.Secondary)
    }
}

/** Smaller round secondary action — Hold / Resume / Close. */
@Composable
private fun SecondaryButton(
    icon: ImageVector, label: String,
    bg: Color = CallColors.SurfaceVariant, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp).clip(CircleShape).background(bg),
        ) {
            Icon(icon, label, tint = CallColors.OnAction, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = CallColors.Secondary)
    }
}

@Composable
private fun EndCallButton(
    callId: String, onDone: () -> Unit, send: (Protocol.CallAction) -> Unit,
) {
    CallActionButton(
        Icons.Filled.CallEnd, stringResource(R.string.call_end), CallColors.Decline) {
        send(Protocol.CallAction(callId, Protocol.CallAction.Action.DISCONNECT))
        onDone()
    }
}

/**
 * Bridge-side GSM audio routing — CallAudioState.ROUTE_* mask reported
 * by the bridge; only routes it says are available are shown (Bluetooth
 * appears only when a headset is connected to the bridge phone).
 */
@Composable
private fun AudioRouteButtons(
    call: CallStateBus.Ui, send: (Protocol.CallAction) -> Unit,
) {
    val routes = listOf(
        Triple(CallAudioState.ROUTE_EARPIECE, Icons.AutoMirrored.Filled.VolumeDown,
            stringResource(R.string.route_earpiece)),
        Triple(CallAudioState.ROUTE_SPEAKER, Icons.AutoMirrored.Filled.VolumeUp,
            stringResource(R.string.route_speaker)),
        Triple(CallAudioState.ROUTE_BLUETOOTH, Icons.Filled.Bluetooth,
            stringResource(R.string.route_bluetooth)),
        Triple(CallAudioState.ROUTE_WIRED_HEADSET, Icons.Filled.Headset,
            stringResource(R.string.route_wired)),
    ).filter { (mask, _, _) ->
        call.availableRoutes?.let { it and mask != 0 }
            ?: (mask == CallAudioState.ROUTE_EARPIECE ||
                mask == CallAudioState.ROUTE_SPEAKER)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        routes.forEach { (route, icon, label) ->
            val active = call.audioRoute == route
            SecondaryButton(
                icon, label,
                bg = if (active) CallColors.Active else CallColors.SurfaceVariant,
            ) {
                send(Protocol.CallAction(
                    call.callId, Protocol.CallAction.Action.AUDIO_ROUTE,
                    audioRoute = route))
            }
        }
    }
}
