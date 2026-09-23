package com.simtether.rooted

import android.os.Build

/**
 * Per-device mixer pokes for GSM uplink injection — the table the
 * redmi-root-spike results populate (docs/redmi-root-spike.md, Test A).
 *
 * Qualcomm devices typically expose an `Incall_Music`-family mixer
 * control that feeds playback PCM into the call uplink. Names vary by
 * OEM/HAL generation, so each profile is an ordered candidate list:
 * the first control that exists on `tinymix` output wins. Empty list =
 * "no known path yet — API injection only".
 */
object DeviceAudioProfiles {

    data class Profile(
        /** tinymix control-name candidates, in preference order. */
        val incallMusicControls: List<String> = emptyList(),
        /** tinymix pokes to run when a call's audio session ends. */
        val cleanupControls: List<String> = emptyList(),
    )

    private val GENERIC_QUALCOMM = Profile(
        incallMusicControls = listOf(
            "Incall_Music",
            "Incall Music",
            "INCALL_MUSIC",
            "Voice_Music",
            "Voice Music",
            "Incall_Music_2",
        ),
    )

    private val BY_DEVICE = mapOf(
        // Redmi Note 8 — designated spike device (docs/redmi-root-spike.md).
        // Entries get filled with the real control names from Test A's
        // tinymix dump; generic Qualcomm candidates cover the usual case.
        "ginkgo" to GENERIC_QUALCOMM,
    )

    fun forThisDevice(): Profile =
        BY_DEVICE[Build.DEVICE] ?: GENERIC_QUALCOMM
}
