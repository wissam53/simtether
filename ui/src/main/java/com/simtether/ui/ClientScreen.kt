package com.simtether.ui

import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.shared.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import com.simtether.shared.CallLogStore
import com.simtether.client.ClientServiceHolder
import com.simtether.shared.ContactLookup
import com.simtether.shared.ConversationStore
import com.simtether.client.PairingStore
import com.simtether.client.StatusBus
import com.simtether.shared.pairing.PairingPayload
import com.simtether.shared.protocol.Protocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class Screen {
    data object Home : Screen()
    data object Messages : Screen()
    data object NewMessage : Screen()
    data class Thread(val address: String) : Screen()
    data object Calls : Screen()
    data object Dialer : Screen()
    data object Controls : Screen()
    data object Settings : Screen()
}

/** Client dashboard: bridge status card + icon nav + recent feed + quick-dial bar. */
@Composable
fun ClientScreen(onPaired: () -> Unit, onResetRole: () -> Unit) {
    // Back stack — Home is the root; system back pops like a real app.
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun home() { stack = listOf(Screen.Home) }
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }

    // Notification taps (e.g. new SMS) request navigation via NavBus.
    val pendingThread by com.simtether.NavBus.openThread.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingThread) {
        pendingThread?.let {
            stack = listOf(Screen.Home, Screen.Messages, Screen.Thread(it))
            com.simtether.NavBus.openThread.value = null
        }
    }
    val openCalls by com.simtether.NavBus.openCalls.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openCalls) {
        if (openCalls) {
            stack = listOf(Screen.Home, Screen.Calls)
            com.simtether.NavBus.openCalls.value = false
        }
    }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(
            onNavigate = { push(it) },
            onThread = { push(Screen.Thread(it)) },
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
        is Screen.Thread -> ThreadScreen(
            address = s.address,
            onBack = { pop() },
        )
        Screen.Calls -> CallsScreen(
            onBack = { home() },
            onDial = { push(Screen.Dialer) },
        )
        Screen.Dialer -> DialerScreen(onBack = { pop() })
        Screen.Controls -> ControlsScreen(onBack = { home() })
        Screen.Settings -> SettingsScreen(
            onBack = { home() },
            onPaired = onPaired,
            onForget = onResetRole,
        )
    }
}

/** Read a contact name, triggering an async lookup on first sight. */
@Composable
fun displayName(names: Map<String, String>, number: String?): String {
    number ?: return stringResource(R.string.unknown)
    androidx.compose.runtime.LaunchedEffect(number) {
        ContactLookup.nameFor(number)
    }
    return names[number] ?: number
}

// ── Home ─────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(onNavigate: (Screen) -> Unit, onThread: (String) -> Unit) {
    val context = LocalContext.current
    val connected by ClientServiceHolder.connected.collectAsState()
    val status by StatusBus.status.collectAsState()
    val messages by ConversationStore.messages.collectAsState()
    val calls by CallLogStore.entries.collectAsState()
    val names by ContactLookup.names.collectAsState()
    val paired = remember { PairingStore.isPaired(context) }
    val revoked by ClientServiceHolder.pairingRevoked.collectAsState()
    val viaRelay by ClientServiceHolder.viaRelay.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)

        if (paired && revoked) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.pairing_revoked),
                        style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { onNavigate(Screen.Settings) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.repair_scan)) }
                }
            }
        }

        if (!paired) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.home_no_bridge_title),
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.home_no_bridge_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { onNavigate(Screen.Settings) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.home_pair_now)) }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    status?.deviceName ?: stringResource(R.string.bridge_fallback_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (connected)
                        if (viaRelay) stringResource(R.string.home_linked_remote)
                        else status?.network?.let {
                            stringResource(R.string.home_linked_via, it)
                        } ?: stringResource(R.string.home_linked)
                    else stringResource(R.string.home_offline),
                    color = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
                status?.let {
                    Text(
                        stringResource(
                            R.string.home_battery_line, it.batteryPct,
                            it.carrier ?: stringResource(R.string.carrier_unknown),
                        ) + if (!it.simReady)
                            stringResource(R.string.home_sim_not_ready) else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavIcon(Icons.Filled.Call, stringResource(R.string.nav_calls)) { onNavigate(Screen.Calls) }
            NavIcon(Icons.AutoMirrored.Filled.Message, stringResource(R.string.nav_messages)) { onNavigate(Screen.Messages) }
            NavIcon(Icons.Filled.Tune, stringResource(R.string.nav_controls)) { onNavigate(Screen.Controls) }
            NavIcon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) { onNavigate(Screen.Settings) }
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
                        onClick = { onThread(item.m.address) },
                    )
                    is FeedItem.Call -> FeedRow(
                        title = item.c.displayName
                            ?: displayName(names, item.c.number),
                        subtitle = item.c.label(context),
                        time = item.c.timestamp,
                        icon = if (item.c.missed) Icons.AutoMirrored.Filled.CallMissed
                               else Icons.Filled.Call,
                        onClick = { onNavigate(Screen.Calls) },
                    )
                }
            }
        }
    }
}

sealed class FeedItem {
    abstract val timestamp: Long
    data class Msg(val m: com.simtether.shared.ChatMessage) : FeedItem() {
        override val timestamp get() = m.timestamp
    }
    data class Call(val c: com.simtether.shared.CallLogEntry) : FeedItem() {
        override val timestamp get() = c.timestamp
    }
}

@Composable
fun NavIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Offline warning strip — shown under every screen header. */
@Composable
private fun OfflineBanner() {
    val connected by ClientServiceHolder.connected.collectAsState()
    if (!connected) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudOff, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun offlineToast(context: android.content.Context) {
    android.widget.Toast.makeText(
        context, context.getString(R.string.toast_offline),
        android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Column {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    OfflineBanner()
    }
}

@Composable
fun FeedRow(
    title: String, subtitle: String, time: Long,
    icon: ImageVector, onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 10.dp).size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Text(fmtTime(time), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Messages ─────────────────────────────────────────────────────

@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    onThread: (String) -> Unit,
    onNew: () -> Unit,
) {
    val messages by ConversationStore.messages.collectAsState()
    val names by ContactLookup.names.collectAsState()
    val threads = messages.groupBy { it.address }
        .mapValues { it.value.maxByOrNull { m -> m.timestamp }!! }
        .values.sortedByDescending { it.timestamp }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHeader(stringResource(R.string.nav_messages), onBack)
        LazyColumn {
            items(threads) { last ->
                Card(
                    onClick = { onThread(last.address) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayName(names, last.address),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(last.body, maxLines = 1,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(fmtTime(last.timestamp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
        FloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) { Icon(Icons.Filled.Add, stringResource(R.string.new_message)) }
    }
}

@Composable
fun NewMessageScreen(onBack: () -> Unit, onSent: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val connected by ClientServiceHolder.connected.collectAsState()
    val context = LocalContext.current

    if (picking) {
        ContactPickerDialog(
            onPick = { address = it.number; picking = false },
            onDismiss = { picking = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()).padding(16.dp)) {
        BackHeader(stringResource(R.string.new_message), onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text(stringResource(R.string.field_to)) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = { picking = true }) {
                Icon(Icons.Filled.Contacts, stringResource(R.string.cd_pick_contact),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            label = { Text(stringResource(R.string.field_message)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Button(
            onClick = {
                val to = address.trim()
                if (to.isNotBlank() && body.isNotBlank()) {
                    val ref = ConversationStore.onOutgoing(to, body.trim())
                    ClientServiceHolder.sendSms(to, body.trim(), ref)
                    onSent(to)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null)
            Text(stringResource(R.string.action_send), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private fun filterContacts(
    contacts: List<ContactLookup.Contact>, query: String,
) = if (query.isBlank()) contacts
   else contacts.filter {
       it.name.contains(query, ignoreCase = true) || it.number.contains(query)
   }

/**
 * Searchable contact list — the single contacts UI. Used inline in the
 * Calls tab and inside the compose dialog, so both look identical.
 * `trailing` adds a per-row affordance (e.g. a call icon).
 */
@Composable
private fun ContactPicker(
    onPick: (ContactLookup.Contact) -> Unit,
    trailing: (@Composable (ContactLookup.Contact) -> Unit)? = null,
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactLookup.Contact>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        contacts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ContactLookup.all(context)
        }
    }
    Column {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_contacts)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        LazyColumn {
            items(filterContacts(contacts, query)) { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(c.number, style = MaterialTheme.typography.bodySmall)
                    }
                    trailing?.invoke(c)
                }
            }
        }
    }
}

@Composable
private fun ContactPickerDialog(
    onPick: (ContactLookup.Contact) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_contact_title)) },
        text = { ContactPicker(onPick) },
        confirmButton = {},
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ThreadScreen(address: String, onBack: () -> Unit) {
    val messages by ConversationStore.messages.collectAsState()
    val names by ContactLookup.names.collectAsState()
    val thread = messages.filter { it.address == address }.sortedBy { it.timestamp }
    var body by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val toastContext = LocalContext.current
    val connected by ClientServiceHolder.connected.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHeader(displayName(names, address), onBack) {
            IconButton(onClick = {
                if (connected) ClientServiceHolder.dial(address)
                else offlineToast(toastContext)
            }) {
                Icon(Icons.Filled.Call, stringResource(R.string.action_call),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(thread) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboard.setText(
                                        androidx.compose.ui.text.AnnotatedString(m.body))
                                    android.widget.Toast.makeText(
                                        toastContext,
                                        toastContext.getString(R.string.copied),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                },
                            ),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(m.body, style = MaterialTheme.typography.bodyMedium)
                            val st = m.status
                            if (m.outgoing && st != null) {
                                Text(
                                    stringResource(when (st) {
                                        "sending" -> R.string.sms_sending
                                        "sent" -> R.string.sms_sent
                                        "delivered" -> R.string.sms_delivered
                                        "unconfirmed" -> R.string.sms_unconfirmed
                                        else -> R.string.sms_failed
                                    }),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (st == "failed")
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text(stringResource(R.string.field_message)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (body.isNotBlank()) {
                        val ref = ConversationStore.onOutgoing(address, body.trim())
                        ClientServiceHolder.sendSms(address, body.trim(), ref)
                        body = ""
                    }
                },
                modifier = Modifier.padding(start = 6.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ── Calls ────────────────────────────────────────────────────────

@Composable
fun CallsScreen(onBack: () -> Unit, onDial: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val calls by CallLogStore.entries.collectAsState()
    val names by ContactLookup.names.collectAsState()
    val connected by ClientServiceHolder.connected.collectAsState()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHeader(stringResource(R.string.nav_calls), onBack)
        Row {
            TextButton(onClick = { tab = 0 }) {
                Text(stringResource(R.string.tab_recents),
                    color = if (tab == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { tab = 1 }) {
                Text(stringResource(R.string.tab_contacts),
                    color = if (tab == 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
            }
        }
        HorizontalDivider()
        if (tab == 0) {
            LazyColumn {
                items(calls.sortedByDescending { it.timestamp }) { c ->
                    CallRow(
                        title = c.displayName ?: displayName(names, c.number),
                        subtitle = c.label(context),
                        time = c.timestamp,
                        onCall = {
                            c.number?.let {
                                if (connected) ClientServiceHolder.dial(it)
                                else offlineToast(context)
                            }
                        },
                    )
                }
            }
        } else {
            ContactPicker(
                onPick = {
                    if (connected) ClientServiceHolder.dial(it.number)
                    else offlineToast(context)
                },
                trailing = { c ->
                    IconButton(onClick = {
                        if (connected) ClientServiceHolder.dial(c.number)
                        else offlineToast(context)
                    }) {
                        Icon(Icons.Filled.Call, stringResource(R.string.action_call),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        }
    }
        FloatingActionButton(
            onClick = onDial,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) { Icon(Icons.Filled.Dialpad, stringResource(R.string.new_call)) }
    }
}

@Composable
private fun CallRow(title: String, subtitle: String, time: Long?, onCall: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        time?.let { Text(fmtTime(it), style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = onCall) {
            Icon(Icons.Filled.Call, stringResource(R.string.action_call),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun DialerScreen(onBack: () -> Unit) {
    var number by remember { mutableStateOf("") }
    val connected by ClientServiceHolder.connected.collectAsState()
    val context = LocalContext.current
    val names by ContactLookup.names.collectAsState()
    androidx.compose.runtime.LaunchedEffect(number) {
        if (number.isNotBlank()) ContactLookup.nameFor(number)
    }
    Column(modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()).padding(16.dp)) {
        BackHeader(stringResource(R.string.dialer_title), onBack)
        Text(
            number.ifBlank { stringResource(R.string.enter_number) },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        // Resolved contact name under the digits, like a stock dialer.
        Text(
            names[number] ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        val keys = listOf("1","2","3","4","5","6","7","8","9","*","0","#")
        keys.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { k ->
                    OutlinedButton(
                        onClick = { number += k },
                        modifier = Modifier.padding(4.dp),
                    ) { Text(k, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { number = number.dropLast(1) }) {
                Icon(Icons.AutoMirrored.Filled.Backspace,
                    stringResource(R.string.cd_delete))
            }
            Button(
                onClick = {
                    if (!connected) { offlineToast(context); return@Button }
                    if (number.isNotBlank()) ClientServiceHolder.dial(number)
                },
            ) {
                Icon(Icons.Filled.Call, null)
                Text(stringResource(R.string.action_call),
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

// ── Controls & Settings ──────────────────────────────────────────

@Composable
private fun ControlsScreen(onBack: () -> Unit) {
    val status by StatusBus.status.collectAsState()
    val connected by ClientServiceHolder.connected.collectAsState()
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()).padding(16.dp)) {
        BackHeader(stringResource(R.string.controls_title), onBack)
        status?.let {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.controls_network, it.network ?: "?"))
                    Text(stringResource(R.string.controls_battery, it.batteryPct))
                    Text(stringResource(R.string.controls_carrier, it.carrier ?: "?"))
                    Text(stringResource(R.string.controls_ringer, when (it.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> stringResource(R.string.ringer_silent)
                        AudioManager.RINGER_MODE_VIBRATE -> stringResource(R.string.ringer_vibrate)
                        AudioManager.RINGER_MODE_NORMAL -> stringResource(R.string.ringer_normal)
                        else -> stringResource(R.string.ringer_unknown)
                    }))
                }
            }
        }
        Button(
            onClick = {
                if (!connected) { offlineToast(context); return@Button }
                ClientServiceHolder.sendBridgeCommand(
                    Protocol.BridgeCommand.Action.STATUS_REFRESH
                )
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.refresh_status)) }
        val muted = status?.ringerMode != AudioManager.RINGER_MODE_NORMAL
        OutlinedButton(
            onClick = {
                if (!connected) { offlineToast(context); return@OutlinedButton }
                ClientServiceHolder.sendBridgeCommand(
                    if (muted) Protocol.BridgeCommand.Action.UNMUTE
                    else Protocol.BridgeCommand.Action.MUTE
                )
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(
                if (muted) R.string.unmute_bridge else R.string.mute_bridge))
        }
        Text(
            stringResource(R.string.controls_wifi_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = remember { PairingStore.load(context) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            runCatching { PairingPayload.decode(text) }
                .onSuccess {
                    PairingStore.save(context, it)
                    onPaired()
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()).padding(16.dp)) {
        BackHeader(stringResource(R.string.nav_settings), onBack)
        Text(stringResource(R.string.settings_bridge_section),
            style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.settings_last_bridge,
            pairing?.deviceName ?: "?", pairing?.host ?: "?",
            pairing?.port?.toString() ?: "?"))
        Button(
            onClick = {
                scanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(context.getString(R.string.scan_prompt))
                })
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Icon(Icons.Filled.QrCodeScanner, null)
            Text(stringResource(R.string.repair_scan),
                modifier = Modifier.padding(start = 6.dp))
        }
        OutlinedButton(
            onClick = {
                PairingStore.clear(context)
                onForget()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.forget_bridge)) }

        RemoteAccessCard(pairing)

        Text(
            stringResource(R.string.settings_language).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        LanguagePicker()
    }
}

/**
 * Remote-access card — opt-in internet fallback through a splice
 * relay the user chooses (BYO). The toggle is the consent: enabling
 * asks once what the internet path means, disabling drops a live
 * relay link immediately.
 */
@Composable
private fun RemoteAccessCard(pairing: PairingPayload?) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(com.simtether.shared.RemoteStore.isEnabled(context))
    }
    var confirm by remember { mutableStateOf(false) }
    var relayAddr by remember { mutableStateOf(pairing?.relay ?: "") }
    var relayTok by remember { mutableStateOf(pairing?.relayToken ?: "") }

    Text(
        stringResource(R.string.remote_access).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.remote_client_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { want ->
                if (want) confirm = true
                else {
                    com.simtether.shared.RemoteStore.setEnabled(context, false)
                    enabled = false
                    ClientServiceHolder.service?.applyRemotePref()
                }
            },
        )
    }
    if (enabled) {
        OutlinedTextField(
            value = relayAddr, onValueChange = { relayAddr = it },
            label = { Text(stringResource(R.string.remote_relay_address)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = relayTok, onValueChange = { relayTok = it },
            label = { Text(stringResource(R.string.remote_relay_token)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedButton(
            onClick = {
                PairingStore.updateRelay(context, relayAddr, relayTok)
                ClientServiceHolder.service?.reconnect()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.remote_save)) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.remote_consent_title)) },
        text = { Text(stringResource(R.string.remote_consent_client)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                com.simtether.shared.RemoteStore.setEnabled(context, true)
                enabled = true
                ClientServiceHolder.service?.reconnect()
            }) { Text(stringResource(R.string.remote_agree)) }
        },
        dismissButton = {
            TextButton(onClick = { confirm = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * In-app language override picker — framework per-app locales are API
 * 33+, so the tag is stored and applied via LocaleHelper.wrap on every
 * component. Shared by client Settings, the bridge screen, and local
 * mode. Picking recreates the activity to apply the new config.
 *
 * [onLanguageChanged] lets each app refresh its own service's ongoing
 * notification in the new language — the client service is handled
 * here; the bridge app passes its own hook.
 */
@Composable
fun LanguagePicker(onLanguageChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var langTag by remember {
        mutableStateOf(com.simtether.shared.LocaleHelper.storedTag(context))
    }
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            LANG_NAMES.firstOrNull { it.first == langTag }?.second
                ?: langTag
                ?: stringResource(R.string.language_system)
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    // Native names — never translated.
                    LANG_NAMES.forEach { (tag, name) ->
                        TextButton(
                            onClick = {
                                com.simtether.shared.LocaleHelper.set(context, tag)
                                langTag = tag
                                picking = false
                                // Re-post the running service's ongoing
                                // notification in the new language.
                                com.simtether.client.ClientServiceHolder.service
                                    ?.refreshNotification()
                                onLanguageChanged()
                                // New config only applies to freshly
                                // created components — recreate.
                                (context as? android.app.Activity)?.recreate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                name ?: stringResource(R.string.language_system),
                                color = if (tag == langTag)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** tag → native display name; null = follow the system locale. */
private val LANG_NAMES: List<Pair<String?, String?>> = listOf(
    null to null,
    "en" to "English",
    "tr" to "Türkçe",
    "ar" to "العربية",
    "ru" to "Русский",
    "tk" to "Türkmençe",
    "fa" to "فارسی",
)

private fun fmtTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
