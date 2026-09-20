package com.simtether

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.bridge.BridgeService
import com.simtether.shared.R
import com.simtether.client.ClientService
import com.simtether.ui.BridgeScreen
import com.simtether.ui.ClientScreen

enum class Role { NONE, BRIDGE, CLIENT }

class MainActivity : ComponentActivity() {

    private var role by mutableStateOf(Role.NONE)
    // Role picked but disclosure not yet accepted — Play requires an
    // in-app disclosure before the runtime permission dialogs.
    private var pendingRole by mutableStateOf<Role?>(null)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavIntent(intent)
        role = loadRole()
        if (role != Role.NONE) enterRole(role, persist = false)
        enableEdgeToEdge()
        setContent {
            com.simtether.ui.SimTetherTheme {
                // Status-bar icons follow the theme (OEM skins ignore
                // the theme attribute, so drive it from compose).
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat
                        .getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    val pending = pendingRole
                    if (pending != null) {
                        DisclosureScreen(
                            role = pending,
                            onAgree = {
                                pendingRole = null
                                enterRole(pending)
                            },
                            onDecline = { pendingRole = null },
                        )
                    } else when (role) {
                        Role.BRIDGE -> BridgeScreen()
                        Role.CLIENT -> {
                            val entitled by com.simtether.billing.Billing
                                .entitled.collectAsState()
                            val price by com.simtether.billing.Billing
                                .price.collectAsState()
                            when (entitled) {
                                null -> androidx.compose.foundation.layout.Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                                false -> com.simtether.ui.PaywallScreen(
                                    price = price,
                                    onSubscribe = {
                                        com.simtether.billing.Billing
                                            .subscribe(this@MainActivity)
                                    },
                                )
                                true -> ClientScreen(
                                    onPaired = {
                                        startForegroundService(
                                            Intent(this@MainActivity,
                                                ClientService::class.java))
                                    },
                                    onResetRole = { resetRole() },
                                )
                            }
                        }
                        Role.NONE -> RolePicker(
                            onBridge = { pendingRole = Role.BRIDGE },
                            onClient = { pendingRole = Role.CLIENT },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavIntent(intent)
    }

    private fun handleNavIntent(intent: Intent?) {
        intent?.getStringExtra(com.simtether.shared.SmsNotifier.EXTRA_OPEN_THREAD)
            ?.let { NavBus.openThread.value = it }
        if (intent?.getBooleanExtra(
                com.simtether.client.telecom.CallRouter.EXTRA_OPEN_CALLS, false) == true)
            NavBus.openCalls.value = true
    }

    private fun loadRole(): Role =
        when (getSharedPreferences("app", MODE_PRIVATE).getString("role", null)) {
            "bridge" -> Role.BRIDGE
            "client" -> Role.CLIENT
            else -> Role.NONE
        }

    private fun enterRole(r: Role, persist: Boolean = true) {
        role = r
        if (persist) {
            getSharedPreferences("app", MODE_PRIVATE).edit()
                .putString("role", r.name.lowercase()).apply()
        }
        val perms = when (r) {
            Role.BRIDGE -> buildList {
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.SEND_SMS)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.ANSWER_PHONE_CALLS)
                add(Manifest.permission.READ_CONTACTS)
            }
            Role.CLIENT -> buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.MANAGE_OWN_CALLS)
                add(Manifest.permission.READ_CONTACTS)
                // WifiNetworkSpecifier (secondary hotspot link) on 33+
                if (Build.VERSION.SDK_INT >= 33)
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            Role.NONE -> emptyList()
        } + listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        )
        permissionLauncher.launch(perms.toTypedArray())

        // Start the role's service immediately — a paired client must
        // reconnect on every app open, not only right after a scan.
        when (r) {
            Role.BRIDGE -> if (BridgeService.isEnabled(this)) {
                startForegroundService(Intent(this, BridgeService::class.java))
                requestBatteryExemption()
            }
            Role.CLIENT -> startForegroundService(Intent(this, ClientService::class.java))
            Role.NONE -> Unit
        }
    }

    /** Forget pairing + role → back to the picker; stops the service. */
    private fun resetRole() {
        stopService(Intent(this, ClientService::class.java))
        stopService(Intent(this, BridgeService::class.java))
        getSharedPreferences("app", MODE_PRIVATE).edit().remove("role").apply()
        role = Role.NONE
    }

    /** Doze/MIUI will kill the relay otherwise — one-time system prompt. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results surfaced in UI later */ }
}

@Composable
fun RolePicker(onBridge: () -> Unit, onClient: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.role_prompt),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(onClick = onBridge) { Text(stringResource(R.string.role_bridge)) }
        Button(onClick = onClient, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.role_client))
        }
    }
}

/**
 * Play's Prominent Disclosure requirement: before the system runtime
 * dialogs, the app must explain in-app what the sensitive permissions
 * are for and that data never leaves the user's own devices. Shown
 * once per role pick; declining just returns to the picker.
 */
@Composable
fun DisclosureScreen(role: Role, onAgree: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(
                if (role == Role.BRIDGE) R.string.disclosure_bridge_body
                else R.string.disclosure_client_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disclosure_agree))
        }
        TextButton(onClick = onDecline, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.disclosure_decline))
        }
    }
}
