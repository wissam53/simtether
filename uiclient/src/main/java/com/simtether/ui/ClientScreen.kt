package com.simtether.ui

import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.simtether.client.ClientServiceHolder
import com.simtether.client.PairingStore
import com.simtether.client.StatusBus
import com.simtether.shared.R
import com.simtether.shared.CallLogStore
import com.simtether.shared.ContactLookup
import com.simtether.shared.ConversationStore
import com.simtether.shared.pairing.PairingPayload
import com.simtether.shared.protocol.Protocol

/**
 * Client-side orchestration — dashboard, controls, settings, pairing.
 * Lives in :uiclient because everything here is coupled to :client
 * (PairingStore, StatusBus, ClientServiceHolder); the generic screens
 * it navigates to live in :ui and are shared with bridge local mode.
 */
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
    val unreadTotal = messages.count { !it.outgoing && !it.read }
    val unseenCalls = calls.count { !it.seen }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)

        // Keystore fell back to plaintext — the client identity key and
        // pairing token sit unprotected (and now fail closed). Surface
        // it loudly instead of silently accepting a degraded store.
        if (!com.simtether.shared.SecureStore.encryptionReady()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    stringResource(R.string.keystore_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

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
            NavIcon(Icons.Filled.Call, stringResource(R.string.nav_calls),
                badgeCount = unseenCalls) { onNavigate(Screen.Calls) }
            NavIcon(Icons.AutoMirrored.Filled.Message, stringResource(R.string.nav_messages),
                badgeCount = unreadTotal) { onNavigate(Screen.Messages) }
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
    var pairing by remember { mutableStateOf(PairingStore.load(context)) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            runCatching { PairingPayload.decode(text) }
                .onSuccess {
                    PairingStore.save(context, it)
                    pairing = PairingStore.load(context)
                    onPaired()
                }
                .onFailure {
                    android.widget.Toast.makeText(
                        context, R.string.scan_invalid,
                        android.widget.Toast.LENGTH_LONG).show()
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
        // This phone's own identity fingerprint — compare against the
        // "Paired client" line on the bridge screen. Match = this phone
        // holds the pin; mismatch = someone else won the pairing race.
        Text(
            stringResource(R.string.client_identity_fp,
                com.simtether.shared.Identity.fingerprint(
                    PairingStore.clientKeyPair(context).second)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Which bridge this phone trusts — compare with the fingerprint
        // on the bridge's own screen. A bridge you can't physically
        // check is a bridge you shouldn't pair with.
        pairing?.bridgeStaticPubKey?.let { pub ->
            Text(
                stringResource(R.string.bridge_identity_fp,
                    com.simtether.shared.Identity.fingerprint(
                        android.util.Base64.decode(
                            pub, android.util.Base64.DEFAULT))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        // Destructive (erases message + call history) → confirm first.
        var confirmForget by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { confirmForget = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.forget_bridge)) }
        if (confirmForget) AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    PairingStore.clear(context)
                    // Forgetting the bridge also erases the forwarded
                    // history it produced — "unpair before selling the
                    // phone" shouldn't leave SMS bodies behind.
                    com.simtether.shared.ConversationStore.wipe()
                    com.simtether.shared.CallLogStore.wipe()
                    onForget()
                }) { Text(stringResource(R.string.forget_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )

        Text(
            stringResource(R.string.settings_language).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        // The picker's onLanguageChanged hook re-posts the running
        // service notification in the new language.
        LanguagePicker {
            ClientServiceHolder.service?.refreshNotification()
        }

        RemoteAccessCard(pairing)
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
    var advanced by remember { mutableStateOf(false) }
    // A stored value equal to the built-in default isn't a custom
    // override — show the field empty so the default stays in effect.
    var relayAddr by remember {
        mutableStateOf(pairing?.relay
            ?.takeIf { it != com.simtether.shared.RemoteStore.DEFAULT_RELAY } ?: "")
    }
    // Same rule as the address field: the QR also carries the built-in
    // hosted-relay token — showing it as an editable value invites a
    // token-only edit that freezes the override past ACCESS_TOKENS
    // rollover. Custom tokens still show; only the built-in is hidden.
    var relayTok by remember {
        mutableStateOf(pairing?.relayToken
            ?.takeIf { !com.simtether.shared.RemoteStore.isBuiltInToken(it) }
            .orEmpty())
    }
    var relayError by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.remote_access),
                style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.remote_client_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // Consent receipt — when this was turned on, visible
                // on-device. "I never enabled that" is checkable here.
                com.simtether.shared.RemoteStore.enabledAt(context)?.let { at ->
                    Text(
                        stringResource(R.string.remote_enabled_since,
                            java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.SHORT,
                                java.text.DateFormat.SHORT)
                                .format(java.util.Date(at))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // The hosted default has no user-meaningful
                        // address to show — the raw hostname only
                        // appears when a custom relay is configured.
                        if (relayAddr.isBlank())
                            stringResource(R.string.remote_active_relay_default)
                        else stringResource(R.string.remote_active_relay,
                            relayAddr,
                            stringResource(R.string.remote_src_custom)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (!advanced) {
                            // Re-read the store — the pairing object can be
                            // stale (rotate landed after this composition).
                            val p = PairingStore.load(context)
                            relayAddr = p?.relay?.takeIf {
                                it != com.simtether.shared.RemoteStore.DEFAULT_RELAY
                            }.orEmpty()
                            relayTok = p?.relayToken?.takeIf {
                                !com.simtether.shared.RemoteStore.isBuiltInToken(it)
                            }.orEmpty()
                            relayError = false
                        }
                        advanced = !advanced
                    }) {
                        Text(stringResource(R.string.remote_custom_relay))
                        Icon(
                            if (advanced) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).padding(start = 2.dp),
                        )
                    }
                }
                if (advanced) {
                    OutlinedTextField(
                        value = relayAddr,
                        onValueChange = { relayAddr = it; relayError = false },
                        label = { Text(stringResource(R.string.remote_relay_address)) },
                        isError = relayError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    if (relayError) Text(
                        stringResource(R.string.remote_invalid_relay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = relayTok, onValueChange = { relayTok = it },
                        label = { Text(stringResource(R.string.remote_relay_token)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val norm = com.simtether.shared.RemoteStore
                                    .normalizeRelay(relayAddr)
                                if (relayAddr.isNotBlank() && norm == null) {
                                    relayError = true
                                } else {
                                    // Blank address = token-only edit — keep
                                    // the QR/rotate-carried relay. Clearing
                                    // is what "Use default" is for.
                                    val kept = norm ?: PairingStore.load(context)?.relay
                                    PairingStore.updateRelay(context, kept, relayTok)
                                    relayAddr = kept?.takeIf {
                                        it != com.simtether.shared.RemoteStore.DEFAULT_RELAY
                                    }.orEmpty()
                                    ClientServiceHolder.service?.reconnect()
                                    advanced = false
                                }
                            },
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        ) { Text(stringResource(R.string.remote_save)) }
                        TextButton(
                            onClick = {
                                relayAddr = ""
                                relayTok = ""
                                relayError = false
                                PairingStore.updateRelay(context, null, null)
                                ClientServiceHolder.service?.reconnect()
                                advanced = false
                            },
                            modifier = Modifier.padding(start = 4.dp).padding(vertical = 4.dp),
                        ) { Text(stringResource(R.string.remote_use_default)) }
                    }
                }
            }
        }
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
