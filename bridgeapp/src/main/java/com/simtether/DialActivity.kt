package com.simtether

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/**
 * Required component for ROLE_DIALER: an activity handling ACTION_DIAL.
 * Any zero-permission app can fire that intent, so the call is NEVER
 * placed automatically — the user confirms on the bridge screen first
 * (the bridge is unattended, so this is the only thing standing
 * between a random app and premium-rate calls / MMI forwarding codes).
 */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data?.takeIf { it.scheme == "tel" }
        if (uri == null ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) !=
                PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        val number = uri.schemeSpecificPart
        AlertDialog.Builder(this)
            .setTitle(getString(com.simtether.shared.R.string.dial_confirm, number))
            .setPositiveButton(com.simtether.shared.R.string.dial_call) { _, _ ->
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
