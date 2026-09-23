package com.simtether.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallMissedOutgoing
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simtether.shared.R
import com.simtether.shared.CallLogEntry
import com.simtether.shared.CallLogStore
import com.simtether.shared.ContactLookup
import com.simtether.shared.ConversationStore
import com.simtether.shared.UiBackend
import com.simtether.shared.protocol.Protocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generic phone UI — Messages/Calls/Dialer/Thread plus the shared
 * chrome (Screen, nav, headers). Used by the client app AND the
 * bridge's local mode, so nothing here may touch :client — sends and
 * dials go through UiBackend, which each app wires to its own backend.
 */

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

sealed class FeedItem {
    abstract val timestamp: Long
    data class Msg(val m: com.simtether.shared.ChatMessage) : FeedItem() {
        override val timestamp get() = m.timestamp
    }
    data class Call(val c: com.simtether.shared.CallLogEntry) : FeedItem() {
        override val timestamp get() = c.timestamp
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

/** Direction/status glyph — shared by the feed, Recents, and local mode. */
fun callIcon(c: CallLogEntry): ImageVector = when {
    c.missed -> Icons.AutoMirrored.Filled.CallMissed
    c.incoming -> Icons.AutoMirrored.Filled.CallReceived
    c.state == Protocol.CallEvent.State.DISCONNECTED && !c.answered ->
        Icons.AutoMirrored.Filled.CallMissedOutgoing
    else -> Icons.AutoMirrored.Filled.CallMade
}

/** Missed calls read red; everything else keeps the primary tint. */
@Composable
fun callIconTint(c: CallLogEntry): Color? =
    if (c.missed) MaterialTheme.colorScheme.error else null

@Composable
fun NavIcon(icon: ImageVector, label: String, badgeCount: Int = 0, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp),
    ) {
        IconButton(onClick = onClick) {
            BadgedBox(badge = {
                if (badgeCount > 0) Badge { Text("$badgeCount") }
            }) {
                Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Offline warning strip — shown under every screen header. */
@Composable
private fun OfflineBanner() {
    val connected by UiBackend.connected.collectAsState()
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

fun offlineToast(context: android.content.Context) {
    android.widget.Toast.makeText(
        context, context.getString(R.string.toast_offline),
        android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
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
    unread: Boolean = false,
    iconTint: Color? = null,
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
                tint = iconTint ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 10.dp).size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else null)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    color = iconTint ?: Color.Unspecified)
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
    val unreadByThread = messages.filter { !it.outgoing && !it.read }
        .groupingBy { it.address }.eachCount()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHeader(stringResource(R.string.nav_messages), onBack)
        LazyColumn {
            items(threads) { last ->
                val unread = unreadByThread[last.address] ?: 0
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
                                fontWeight = if (unread > 0) FontWeight.Bold else null,
                            )
                            Text(last.body, maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (unread > 0) FontWeight.Bold else null)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtTime(last.timestamp),
                                style = MaterialTheme.typography.labelSmall)
                            if (unread > 0) Badge { Text("$unread") }
                        }
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
                    UiBackend.sendSms(to, body.trim(), ref)
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
    val connected by UiBackend.connected.collectAsState()

    // Opening the thread — or a new message landing while it's open —
    // marks it read and clears its notification.
    androidx.compose.runtime.LaunchedEffect(address, thread.size) {
        ConversationStore.markThreadRead(address)
        com.simtether.shared.SmsNotifier.dismiss(toastContext, address)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackHeader(displayName(names, address), onBack) {
            IconButton(onClick = {
                if (connected) UiBackend.dial(address)
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
                        UiBackend.sendSms(address, body.trim(), ref)
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
    val connected by UiBackend.connected.collectAsState()
    val context = LocalContext.current

    // Viewing Recents acknowledges missed calls and clears their
    // notifications — including one that lands while the list is open.
    androidx.compose.runtime.LaunchedEffect(calls) {
        calls.filter { !it.seen }.forEach {
            UiBackend.dismissMissedCall(context, it.callId)
        }
        CallLogStore.markAllSeen()
    }

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
                        entry = c,
                        title = c.displayName ?: displayName(names, c.number),
                        subtitle = c.label(context),
                        time = c.timestamp,
                        onCall = {
                            c.number?.let {
                                if (connected) UiBackend.dial(it)
                                else offlineToast(context)
                            }
                        },
                    )
                }
            }
        } else {
            ContactPicker(
                onPick = {
                    if (connected) UiBackend.dial(it.number)
                    else offlineToast(context)
                },
                trailing = { c ->
                    IconButton(onClick = {
                        if (connected) UiBackend.dial(c.number)
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
private fun CallRow(
    entry: CallLogEntry, title: String, subtitle: String,
    time: Long?, onCall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(callIcon(entry), null,
            tint = callIconTint(entry) ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (!entry.seen) FontWeight.Bold else null)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = callIconTint(entry) ?: Color.Unspecified)
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
    val connected by UiBackend.connected.collectAsState()
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
                    if (number.isNotBlank()) UiBackend.dial(number)
                },
            ) {
                Icon(Icons.Filled.Call, null)
                Text(stringResource(R.string.action_call),
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * In-app language override picker — framework per-app locales are API
 * 33+, so the tag is stored and applied via LocaleHelper.wrap on every
 * component. Shared by client Settings, the bridge screen, and local
 * mode. Picking recreates the activity to apply the new config.
 *
 * [onLanguageChanged] lets each app refresh its own service's ongoing
 * notification in the new language.
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
