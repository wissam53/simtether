package com.simtether.rooted

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Client mic audio → GSM uplink. Two complementary paths (spike doc
 * Test C), both attempted:
 *
 * 1. **API path** — an AudioTrack on STREAM_VOICE_CALL while Telecom
 *    holds MODE_IN_CALL. On some devices the voice-call stream mixes
 *    into the uplink; on most it only plays locally (the caller hears
 *    nothing — we can't detect that from here, which is why the
 *    product copy stays honest about "experimental").
 * 2. **Mixer path** — per-device `tinymix` pokes enabling the HAL's
 *    incall-music route, after which the same AudioTrack PCM reaches
 *    the far end. Which controls work is device-specific — see
 *    DeviceAudioProfiles.
 *
 * Neither path can confirm the far end actually heard anything; that
 * verification is the spike's job. This class reports "attempted",
 * never "confirmed".
 */
class UplinkInjector(private val context: Context) {
    private companion object {
        const val TAG = "SimTether.Injector"
    }

    @Volatile private var track: AudioTrack? = null
    @Volatile private var mixerPokes: List<String> = emptyList()

    /**
     * Prepare the injection path for a live call. Runs the device
     * profile's mixer pokes (root) and opens the voice-call track.
     * Returns true if the API track at least opened.
     */
    fun start(): Boolean {
        stop()
        val profile = DeviceAudioProfiles.forThisDevice()
        val tinymix = RootShell.which("tinymix")
        if (tinymix != null && profile.incallMusicControls.isNotEmpty()) {
            // Pick the first control that exists on this device's mixer.
            val dump = RootShell.run("$tinymix 2>/dev/null | head -400", 8_000)
            val ctl = profile.incallMusicControls.firstOrNull { name ->
                dump?.contains(name, ignoreCase = true) == true
            }
            if (ctl != null) {
                Log.i(TAG, "incall-music control: $ctl — enabling")
                RootShell.poke("$tinymix '$ctl' 1")
                mixerPokes = listOf("$tinymix '$ctl' 0") + profile.cleanupControls
            } else {
                Log.w(TAG, "no incall-music control found in tinymix dump")
            }
        } else if (profile.incallMusicControls.isNotEmpty()) {
            Log.w(TAG, "tinymix not present — mixer path unavailable")
        }
        val min = AudioTrack.getMinBufferSize(
            RootCaptureDaemon.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { Log.w(TAG, "min buffer <= 0"); return false }
        val t = runCatching {
            @Suppress("DEPRECATION") // legacy ctor forces STREAM_VOICE_CALL
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                RootCaptureDaemon.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 4,
                AudioTrack.MODE_STREAM,
            )
        }.getOrElse {
            Log.e(TAG, "voice-call track failed", it); return false
        }
        runCatching { t.play() }.getOrElse {
            Log.e(TAG, "track play failed", it); t.release(); return false
        }
        track = t
        Log.i(TAG, "uplink injector ready (api path)")
        return true
    }

    /** One PCM frame from the client mic → the call uplink (best effort). */
    fun inject(pcm: ByteArray) {
        val t = track ?: return
        runCatching { t.write(pcm, 0, pcm.size) }
    }

    fun stop() {
        mixerPokes.forEach { RootShell.poke(it) }
        mixerPokes = emptyList()
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
