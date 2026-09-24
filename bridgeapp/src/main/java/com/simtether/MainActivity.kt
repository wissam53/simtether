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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.simtether.bridge.BridgeService
import com.simtether.shared.R
import com.simtether.ui.BridgeScreen
import com.simtether.ui.DisclosureScreen

class MainActivity : ComponentActivity() {

    private var disclosed by mutableStateOf(false)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This app displays live credentials (pairing QR, relay token) —
        // keep every screen out of screenshots and the recents preview.
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
                            bodyRes = R.string.disclosure_bridge_body,
                            onAgree = {
                                getSharedPreferences("app", MODE_PRIVATE).edit()
                                    .putBoolean("disclosed", true).apply()
                                disclosed = true
                                enter()
                            },
                            onDecline = { finish() },
                        )
                    } else {
                        BridgeScreen()
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
                com.simtether.shared.IntentKeys.EXTRA_OPEN_CALLS,
                false) == true)
            NavBus.openCalls.value = true
    }

    /** Permissions + service start — post-disclosure and every open. */
    private fun enter() {
        val perms = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.READ_CONTACTS)
            // Rooted flavor only: the in-app capture path needs mic
            // access (the root-daemon path doesn't). Absent from the
            // store manifest — the request is a no-op there.
            if (com.simtether.RootFeatures.HAS_ROOT_FEATURES)
                add(Manifest.permission.RECORD_AUDIO)
            // connectedDevice FGS prerequisite on 33+ (the CDM
            // association also qualifies once pairing completes).
            if (Build.VERSION.SDK_INT >= 33)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } + listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        )
        permissionLauncher.launch(perms.toTypedArray())

        // The bridge is an appliance — if it was configured on, it
        // must come back every time the app opens, not only after
        // the user flips the switch again.
        if (BridgeService.isEnabled(this)) {
            startForegroundService(Intent(this, BridgeService::class.java))
            requestBatteryExemption()
        }
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
    ) {
        // A bridge that started before NEARBY_WIFI_DEVICES/CDM was
        // granted is stuck on the 6h/24h-capped dataSync FGS type —
        // bounce it into connectedDevice now that the grant holds.
        if (com.simtether.bridge.sms.BridgeServiceHolder.service
                ?.fgsDegraded() == true) {
            stopService(Intent(this, BridgeService::class.java))
            startForegroundService(Intent(this, BridgeService::class.java))
        }
    }
}
