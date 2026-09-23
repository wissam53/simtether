package com.simtether.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.shared.R
import com.simtether.shared.CallLogStore
import com.simtether.shared.ContactLookup
import com.simtether.shared.ConversationStore
import com.simtether.shared.PhoneBackend
import com.simtether.shared.UiBackend

/**
 * Bridge-off mode: the SIM phone runs the same Messages/Calls UI the
 * client has, backed by its own GSM radio. [onStartBridge] flips the
 * master switch back on and restarts the service.
 */
@Composable
fun LocalPhoneScreen(onStartBridge: () -> Unit) {
    val context = LocalContext.current

    // Local backend: sends/dials hit the SIM directly, not the WS.
    DisposableEffect(Unit) {
        ConversationStore.init(context)
        CallLogStore.init(context)
        ContactLookup.init(context)
        UiBackend.impl = object : PhoneBackend {
            override fun sendSms(address: String, body: String, ref: String?) {
                com.simtether.bridge.sms.SmsSender.send(
                    context, address, body, deliveryReport = true, ref = ref)
            }
            override fun dial(number: String) {
                com.simtether.bridge.CallController.dial(context, number)
                // No client will surface the call UI — open ours. The
                // DIALING event lands in BridgeCallBus via the
                // InCallService, so the activity renders it.
                context.startActivity(
                    Intent().setClassName(
                        context.packageName, "com.simtether.BridgeCallActivity"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            override fun dismissMissedCall(context: android.content.Context, callId: String) {
                com.simtether.bridge.telecom.BridgeCallUi
                    .dismissMissed(context, callId)
            }
        }
        // Local link is always "up" — hides the offline banner.
        UiBackend.setConnected(true)
        onDispose {
            UiBackend.impl = null
            UiBackend.setConnected(false)
        }
    }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun home() { stack = listOf(Screen.Home) }
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }

    // Notification taps open the thread — same NavBus the client uses.
    val pendingThread by com.simtether.NavBus.openThread.collectAsState()
    LaunchedEffect(pendingThread) {
        pendingThread?.let {
            stack = listOf(Screen.Home, Screen.Messages, Screen.Thread(it))
            com.simtether.NavBus.openThread.value = null
        }
    }
    val openCalls by com.simtether.NavBus.openCalls.collectAsState()
    LaunchedEffect(openCalls) {
        if (openCalls) {
            stack = listOf(Screen.Home, Screen.Calls)
            com.simtether.NavBus.openCalls.value = false
        }
    }

    when (val s = stack.last()) {
        Screen.Home -> LocalHome(
            onNavigate = { push(it) },
            onThread = { push(Screen.Thread(it)) },
            onStartBridge = onStartBridge,
        )
        Screen.Messages -> MessagesScreen(
            onBack = { home() },
            onThread = { push(Screen.Thread(it)) },
            onNew = { push(Screen.NewMessage) },
        )
        Screen.NewMessage -> NewMessageScreen(
            onBack = { pop() },
            onSent = { pop(); push(Screen.Thread(it)) },
        )
        is Screen.Thread -> ThreadScreen(address = s.address, onBack = { pop() })
        Screen.Calls -> CallsScreen(onBack = { home() }, onDial = { push(Screen.Dialer) })
        Screen.Dialer -> DialerScreen(onBack = { pop() })
        // Local mode has no bridge controls/settings — fold to home.
        Screen.Controls, Screen.Settings -> home()
    }
}

@Composable
private fun LocalHome(
    onNavigate: (Screen) -> Unit,
    onThread: (String) -> Unit,
    onStartBridge: () -> Unit,
) {
    val context = LocalContext.current
    val messages by ConversationStore.messages.collectAsState()
    val calls by CallLogStore.entries.collectAsState()
    val names by ContactLookup.names.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.bridge_off_title),
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.bridge_off_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onStartBridge,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.start_bridge)) }
            }
        }

        LanguagePicker {
            com.simtether.bridge.sms.BridgeServiceHolder.service?.refreshNotification()
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavIcon(Icons.Filled.Call, stringResource(R.string.nav_calls),
                badgeCount = calls.count { !it.seen }) { onNavigate(Screen.Calls) }
            NavIcon(Icons.AutoMirrored.Filled.Message, stringResource(R.string.nav_messages),
                badgeCount = messages.count { !it.outgoing && !it.read }) { onNavigate(Screen.Messages) }
        }

        Text(stringResource(R.string.recent), style = MaterialTheme.typography.labelLarge)
        LazyColumn(modifier = Modifier.weight(1f)) {
            val feed = (messages.map { FeedItem.Msg(it) } +
                        calls.map { FeedItem.Call(it) })
                .sortedByDescending { it.timestamp }
                .take(5)
            items(feed) { item ->
                when (item) {
                    is FeedItem.Msg -> FeedRow(
                        title = displayName(names, item.m.address),
                        subtitle = item.m.body,
                        time = item.m.timestamp,
                        icon = Icons.AutoMirrored.Filled.Message,
                        unread = !item.m.read && !item.m.outgoing,
                        onClick = { onThread(item.m.address) },
                    )
                    is FeedItem.Call -> FeedRow(
                        title = item.c.displayName
                            ?: displayName(names, item.c.number),
                        subtitle = item.c.label(context),
                        time = item.c.timestamp,
                        icon = callIcon(item.c),
                        iconTint = callIconTint(item.c),
                        unread = !item.c.seen,
                        onClick = { onNavigate(Screen.Calls) },
                    )
                }
            }
        }
    }
}
