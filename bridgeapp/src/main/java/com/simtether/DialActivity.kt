package com.simtether

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/**
 * Required component for ROLE_DIALER: an activity handling ACTION_DIAL.
 * The bridge doesn't need a dial pad UI — a DIAL intent just places
 * the call via Telecom (which surfaces in our InCallService).
 */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED) {
            intent?.data?.takeIf { it.scheme == "tel" }?.let { uri: Uri ->
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            }
        }
        finish()
    }
}
