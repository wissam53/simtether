package com.simtether.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.shared.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.core.content.ContextCompat
import com.simtether.companion.CompanionLink
import com.simtether.bridge.BridgeService
import com.simtether.bridge.sms.BridgeServiceHolder
import com.simtether.shared.pairing.PairingPayload

/** Bridge home: pairing QR + link status. Deliberately minimal. */
@Composable
fun BridgeScreen() {
    val context = LocalContext.current
    var payload by remember { mutableStateOf<PairingPayload?>(null) }
    var serviceUp by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(BridgeService.isEnabled(context)) }

    var batteryExempt by remember { mutableStateOf(false) }
    var dndGranted by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE) }
    var oemVisited by remember { mutableStateOf(prefs.getBoolean("oem_fix_done", false)) }
    val oemLabel = oemAutostartLabel(context)

    // Poll service state + LAN address (hotspot may take a moment, and
    // the address can change when the network topology does).
    LaunchedEffect(Unit) {
        while (true) {
            enabled = BridgeService.isEnabled(context)
            val svc = BridgeServiceHolder.service
            serviceUp = svc != null
            svc?.pairingPayload()?.let { payload = it }
            batteryExempt = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
            dndGranted = context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true
            kotlinx.coroutines.delay(500)
        }
    }

    // Default dialer role → unlocks InCallService call control.
    var dialerHeld by remember { mutableStateOf(false) }
    fun checkDialerRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            dialerHeld = rm?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        }
    }

    // CDM companion link (API 33+) — the artifact Play's connected-
    // device exception expects, and the grant source for companion
    // call control + background rights. Whatever the device profile
    // doesn't auto-grant falls back to the plain runtime prompt.
    val cdmSupported = remember { CompanionLink.isSupported(context) }
    var companionLinked by remember { mutableStateOf(CompanionLink.isAssociated(context)) }
    var companionFailed by remember { mutableStateOf(false) }

    val bridgePerms = remember {
        buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun checkMissingPerms() = bridgePerms.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    var missingPerms by remember { mutableStateOf(checkMissingPerms()) }

    LaunchedEffect(Unit) {
        while (true) {
            checkDialerRole()
            companionLinked = CompanionLink.isAssociated(context)
            missingPerms = checkMissingPerms()
            kotlinx.coroutines.delay(1500)
        }
    }

    val dialerRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkDialerRole() }

    val companionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* association state picked up by the poll below */ }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { missingPerms = checkMissingPerms() }

    // Bridge off ≠ dead phone — the SIM still needs its own dialer
    // and messaging UI, backed by local GSM. Rendered standalone,
    // outside the centered bridge layout.
    if (!enabled) {
        LocalPhoneScreen(onStartBridge = {
            BridgeService.setEnabled(context, true)
            context.startForegroundService(
                android.content.Intent(context, BridgeService::class.java))
        })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.bridge_mode_title),
            style = MaterialTheme.typography.headlineSmall)


        Text(
            stringResource(R.string.bridge_scan_hint),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        val p = payload
        if (p != null) {
            Image(
                bitmap = remember(p) { qrBitmap(p.encode()) }.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pairing_qr),
                modifier = Modifier.size(240.dp),
            )
            Text(
                "${p.host}:${p.port}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            Text(
                if (serviceUp) stringResource(R.string.bridge_waiting_lan)
                else stringResource(R.string.bridge_starting)
            )
        }

        if (dialerHeld) {
            Text(
                stringResource(R.string.dialer_held),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val rm = context.getSystemService(RoleManager::class.java)
                        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                            dialerRoleLauncher.launch(
                                rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        }
                    }
                },
                modifier = Modifier.padding(top = 24.dp),
            ) { Text(stringResource(R.string.set_dialer)) }
        }

        // Companion link: one system dialog over the paired phone
        // (must be BT-discoverable) — covers call/SMS rights where the
        // OEM honors the profile, and registers the association Play
        // reviews for the connected-device exception.
        Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.companion_title),
                    style = MaterialTheme.typography.labelMedium)
                when {
                    companionLinked -> Text(
                        stringResource(R.string.companion_linked),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp))
                    !cdmSupported -> Text(
                        stringResource(R.string.companion_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp))
                    else -> {
                        Text(stringResource(R.string.companion_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                        Button(
                            onClick = {
                                companionFailed = false
                                CompanionLink.associate(
                                    context,
                                    launch = { sender ->
                                        companionLauncher.launch(
                                            IntentSenderRequest.Builder(sender).build())
                                    },
                                    onResult = { ok -> companionFailed = !ok },
                                )
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(R.string.companion_link)) }
                        if (companionFailed) Text(
                            stringResource(R.string.companion_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (missingPerms.isNotEmpty()) {
                    HealthRow(
                        stringResource(R.string.grant_perms, missingPerms.size),
                        done = false,
                    ) { permLauncher.launch(missingPerms.toTypedArray()) }
                }
            }
        }

        // Doze killers: battery exemption + MIUI autostart keep the
        // socket alive; DND access lets remote mute go truly silent.
        Card(modifier = Modifier.padding(top = 24.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.keep_alive_title),
                    style = MaterialTheme.typography.labelMedium)
                HealthRow(stringResource(R.string.health_battery), done = batteryExempt) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}")))
                    }
                }
                // OEM autostart can't be checked from code — visiting
                // the screen is the only verification, so trust that.
                if (oemLabel != null) HealthRow(oemLabel, done = oemVisited) {
                    openOemBackgroundSettings(context)
                    prefs.edit().putBoolean("oem_fix_done", true).apply()
                    oemVisited = true
                }
                HealthRow(stringResource(R.string.health_dnd), done = dndGranted) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
            }
        }

        var confirmRepair by remember { mutableStateOf(false) }
        androidx.compose.material3.OutlinedButton(
            onClick = { confirmRepair = true },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(stringResource(R.string.repair_button)) }

        androidx.compose.material3.OutlinedButton(
            onClick = {
                BridgeService.setEnabled(context, false)
                context.stopService(
                    android.content.Intent(context, BridgeService::class.java))
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(R.string.stop_bridge)) }

        // Rotating the identity kills the current pairing — confirm.
        if (confirmRepair) AlertDialog(
            onDismissRequest = { confirmRepair = false },
            title = { Text(stringResource(R.string.repair_title)) },
            text = { Text(stringResource(R.string.repair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRepair = false
                    BridgeServiceHolder.service?.rePair()
                }) { Text(stringResource(R.string.repair_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRepair = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )

        LanguagePicker()

    }
}

@Composable
private fun HealthRow(label: String, done: Boolean?, onFix: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (done == true) "✓ $label" else label,
            modifier = Modifier.weight(1f),
            color = if (done == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (done != true) TextButton(onClick = onFix) {
            Text(stringResource(R.string.action_fix))
        }
    }
}

/**
 * OEMs each bury their "don't kill this app" toggle in a different
 * manager. Returns a row label when we know this manufacturer's
 * screen, null on stock Android (battery exemption is enough there).
 */
private fun oemAutostartLabel(context: android.content.Context): String? =
    when (Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> context.getString(R.string.health_autostart_miui)
        "samsung" -> context.getString(R.string.health_samsung)
        "oppo" -> context.getString(R.string.health_oppo)
        "vivo" -> context.getString(R.string.health_vivo)
        "huawei" -> context.getString(R.string.health_huawei)
        "oneplus" -> context.getString(R.string.health_oneplus)
        else -> null
    }

/**
 * Known deep-links into each OEM's background/autostart manager.
 * They're undocumented and move between OS versions, so we try each
 * candidate and fall back to the app-details page if all miss.
 */
private fun openOemBackgroundSettings(context: android.content.Context) {
    fun comp(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
    val candidates = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> listOf(
            comp("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        "samsung" -> listOf(
            comp("com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"),
            comp("com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        "oppo" -> listOf(
            comp("com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            comp("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            comp("com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo" -> listOf(
            comp("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            comp("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        "huawei" -> listOf(
            comp("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            comp("com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "oneplus" -> listOf(
            comp("com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        else -> emptyList()
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    for (i in candidates + fallback) {
        if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return
    }
}

private fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp
}
