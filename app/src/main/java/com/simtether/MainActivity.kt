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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.simtether.client.ClientService
import com.simtether.shared.R
import com.simtether.ui.ClientScreen
import com.simtether.ui.DisclosureScreen

class MainActivity : ComponentActivity() {

    private var disclosed by mutableStateOf(false)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SMS threads + call state on screen — keep them out of
        // screenshots and the recents preview (bridge does the same).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        handleNavIntent(intent)
        disclosed = getSharedPreferences("app", MODE_PRIVATE)
            .getBoolean("disclosed", false)
        if (disclosed) enter()
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
                // edge-to-edge is enforced — pad the whole tree for
                // status/nav bars so nothing lands under them.
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (!disclosed) {
                        DisclosureScreen(
                            bodyRes = R.string.disclosure_client_body,
                            onAgree = {
                                getSharedPreferences("app", MODE_PRIVATE).edit()
                                    .putBoolean("disclosed", true).apply()
                                disclosed = true
                                enter()
                            },
                            onDecline = { finish() },
                        )
                    } else {
                        val entitled by com.simtether.billing.Billing
                            .entitled.collectAsState()
                        val price by com.simtether.billing.Billing
                            .price.collectAsState()
                        when (entitled) {
                            null -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                            false -> com.simtether.ui.PaywallScreen(
                                price = price,
                                onSubscribe = {
                                    com.simtether.billing.Billing
                                        .subscribe(this@MainActivity)
                                },
                                onRestore = {
                                    com.simtether.billing.Billing.refresh()
                                },
                            )
                            true -> ClientScreen(
                                onPaired = {
                                    startForegroundService(
                                        Intent(this@MainActivity,
                                            ClientService::class.java))
                                },
                                onResetRole = { resetClient() },
                            )
                        }
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
                com.simtether.shared.IntentKeys.EXTRA_OPEN_CALLS, false) == true)
            NavBus.openCalls.value = true
    }

    /** Permissions + service start — post-disclosure and every open. */
    private fun enter() {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.MANAGE_OWN_CALLS)
            add(Manifest.permission.READ_CONTACTS)
            // Mic uplink for the rooted bridge's call-audio relay —
            // sessions degrade to listen-only if the user declines.
            add(Manifest.permission.RECORD_AUDIO)
            // WifiNetworkSpecifier (secondary hotspot link) on 33+
            if (Build.VERSION.SDK_INT >= 33)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } + listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        )
        permissionLauncher.launch(perms.toTypedArray())

        // A paired client must reconnect on every app open, not only
        // right after a scan — the service no-ops when unpaired.
        startForegroundService(Intent(this, ClientService::class.java))
        // The client holds a persistent link — doze defers its
        // reconnect timers and stalls pings without this.
        requestBatteryExemption()
    }

    /** Forget pairing → back to the unpaired home; stops the service. */
    private fun resetClient() {
        stopService(Intent(this, ClientService::class.java))
        recreate()
    }

    /** Doze/MIUI will kill the link otherwise — one-time system prompt. */
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
    ) {
        // A client that started before NEARBY_WIFI_DEVICES was granted
        // is stuck on the 6h/24h-capped dataSync FGS type — bounce it
        // into connectedDevice now that the grant holds.
        if (com.simtether.client.ClientServiceHolder.service
                ?.fgsDegraded() == true) {
            stopService(Intent(this, ClientService::class.java))
            startForegroundService(Intent(this, ClientService::class.java))
        }
    }
}
