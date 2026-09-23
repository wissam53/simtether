package com.simtether.shared

/**
 * Intent extras shared across modules. EXTRA_OPEN_CALLS was defined
 * twice (client CallRouter + bridge BridgeCallUi) — one source now.
 */
object IntentKeys {
    const val EXTRA_OPEN_CALLS = "com.simtether.OPEN_CALLS"
}
