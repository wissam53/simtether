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
import com.simtether.shared.R
import com.simtether.client.ClientServiceHolder
import com.simtether.shared.CallStateBus
import com.simtether.ui.CallColors
import com.simtether.ui.InCallScreen

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
        // Call content + remote-party number — out of screenshots and
        // the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                    // targetSdk 35 enforces edge-to-edge — keep the
                    // call actions clear of the nav bar.
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
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
