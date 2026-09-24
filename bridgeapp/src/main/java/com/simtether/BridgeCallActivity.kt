package com.simtether

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.simtether.bridge.CallController
import com.simtether.bridge.telecom.BridgeCallBus
import com.simtether.shared.CallStateBus
import com.simtether.ui.InCallScreen

/**
 * Bridge-local fallback in-call UI. We're the default dialer on the
 * SIM phone, so when no client is linked a GSM call still needs a
 * local screen — same UI as the client, actions dispatched directly
 * through CallController instead of over the wire.
 */
class BridgeCallActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Call content + party number — out of screenshots/recents.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            MaterialTheme(colorScheme = com.simtether.ui.SimTetherDarkColors) {
                val event by BridgeCallBus.call.collectAsState()
                var seen by remember { mutableStateOf(false) }
                if (event != null) seen = true
                Surface(
                    // targetSdk 35 enforces edge-to-edge — keep the
                    // call actions clear of the nav bar.
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = com.simtether.ui.CallColors.Surface,
                ) {
                    when {
                        event != null -> InCallScreen(
                            event!!.let {
                                CallStateBus.Ui(
                                    callId = it.callId,
                                    number = it.number,
                                    name = it.displayName,
                                    state = it.state,
                                    incoming = it.incoming,
                                    audioRoute = it.audioRoute,
                                    availableRoutes = it.availableRoutes,
                                )
                            },
                            onDone = { finish() },
                            send = { cmd ->
                                CallController.dispatch(applicationContext, cmd)
                            },
                        )
                        seen -> finish()
                        else -> Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                stringResource(com.simtether.shared.R.string.call_connecting),
                                color = com.simtether.ui.CallColors.Secondary)
                        }
                    }
                }
            }
        }
    }
}
