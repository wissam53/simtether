package com.simtether.companion

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * CDM companion association (API 33+) — the "connected device
 * companion" artifact Play's SMS/call permission exception expects,
 * and the grant source for companion privileges (MANAGE_ONGOING_CALLS
 * → the bridge InCallService, run-in-background keep-alive rights).
 *
 * DEVICE_PROFILE_WATCH is the closest precedent: wearable companions
 * relay SMS/calls to a paired device — exactly what the bridge does.
 * If an OEM's grant set disappoints, COMPUTER/GLASSES are the
 * alternates (each needs its own REQUEST_COMPANION_PROFILE_* manifest
 * permission). Grants vary by OEM and Android version, so the UI still
 * surfaces whatever the bundle didn't cover via the normal prompt.
 */
object CompanionLink {
    private const val TAG = "SimTether.CDM"

    // API-31 constant inlined at compile time — safe on lower SDKs.
    @SuppressLint("InlinedApi")
    const val PROFILE = AssociationRequest.DEVICE_PROFILE_WATCH

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    fun isAssociated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            cdm.myAssociations.any { it.deviceProfile == PROFILE }
        }.getOrDefault(false)
    }

    /**
     * Shows the system device picker — the other phone must be
     * Bluetooth-discoverable. [launch] fires with the picker's
     * IntentSender; hand it to a StartIntentSenderForResult launcher.
     * [onResult] runs on the main thread.
     */
    fun associate(
        context: Context,
        launch: (IntentSender) -> Unit,
        onResult: (ok: Boolean) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < 33) { onResult(false); return }
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) { onResult(false); return }
        val request = AssociationRequest.Builder()
            .setDeviceProfile(PROFILE)
            .setSingleDevice(true)
            .build()
        runCatching {
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(sender: IntentSender) = launch(sender)
                override fun onAssociationCreated(info: AssociationInfo) = onResult(true)
                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "association failed: $error")
                    onResult(false)
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure {
            Log.w(TAG, "associate() threw", it)
            onResult(false)
        }
    }
}
