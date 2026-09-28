package com.simtether.rooted

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.simtether.RootFeatures
import com.simtether.bridge.audio.CallAudioRelay

/**
 * The rooted build's CallAudioRelay: downlink capture → client,
 * client mic → uplink injection.
 *
 * Capture preference order (spike doc Test D):
 *  1. In-app `AudioRecord(VOICE_CALL)` — works when the app holds
 *     CAPTURE_AUDIO_OUTPUT (e.g. installed as a Magisk priv-app).
 *     Cheapest path: no second process.
 *  2. `RootCaptureDaemon` — the same AudioRecord running under `su`
 *     as uid=0, which bypasses the privileged-permission check
 *     entirely. This is the expected production path.
 *
 * Uplink is always best-effort (see UplinkInjector) — the spike
 * decides whether it reaches the far end on a given device.
 */
class RootedAudioRelay : CallAudioRelay {
    private companion object {
        const val TAG = "SimTether.AudioRelay"
    }

    @Volatile private var injector: UplinkInjector? = null
    @Volatile private var inAppCapture: InAppCapture? = null
    @Volatile private var daemonUsed = false

    override fun available(context: Context): Boolean =
        RootFeatures.audioRelayEnabled(context) && RootFeatures.rootAvailable()

    override fun start(context: Context, onPcm: (ByteArray) -> Unit): Boolean {
        stop()
        val inj = UplinkInjector(context).also { it.start() }
        injector = inj
        if (tryInAppCapture(context, onPcm)) return true
        daemonUsed = RootCaptureDaemon.start(context, onPcm)
        return daemonUsed
    }

    override fun inject(pcm: ByteArray) {
        injector?.inject(pcm)
    }

    override fun stop() {
        inAppCapture?.stop()
        inAppCapture = null
        if (daemonUsed) RootCaptureDaemon.stop()
        daemonUsed = false
        injector?.stop()
        injector = null
    }

    /**
     * In-app capture attempt — succeeds only when CAPTURE_AUDIO_OUTPUT
     * was granted (priv-app placement). Needs runtime RECORD_AUDIO.
     */
    private fun tryInAppCapture(context: Context, onPcm: (ByteArray) -> Unit): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "RECORD_AUDIO not granted — skipping in-app capture")
            return false
        }
        val cap = InAppCapture()
        return if (cap.start(onPcm)) {
            inAppCapture = cap
            Log.i(TAG, "in-app VOICE_CALL capture running")
            true
        } else false
    }

    /** AudioRecord(VOICE_CALL) on the app thread — privileged-perm path. */
    private class InAppCapture {
        @Volatile private var rec: AudioRecord? = null
        @Volatile private var thread: Thread? = null

        // Only reached when CAPTURE_AUDIO_OUTPUT was granted (the
        // priv-app path) — runCatching covers a refused record anyway.
        @SuppressLint("MissingPermission")
        fun start(onPcm: (ByteArray) -> Unit): Boolean {
            val rate = RootCaptureDaemon.SAMPLE_RATE
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    min * 2)
            }.getOrNull() ?: return false
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release(); return false
            }
            val ok = runCatching { r.startRecording(); true }.getOrElse {
                r.release(); false
            }
            if (!ok) return false
            rec = r
            thread = Thread({
                val buf = ByteArray(RootCaptureDaemon.FRAME_BYTES)
                try {
                    while (rec != null) {
                        var off = 0
                        while (off < buf.size) {
                            val n = r.read(buf, off, buf.size - off)
                            if (n <= 0) throw IllegalStateException("read=$n")
                            off += n
                        }
                        onPcm(buf.copyOf())
                    }
                } catch (_: Exception) {}
            }, "stcap-app").also { it.isDaemon = true; it.start() }
            return true
        }

        fun stop() {
            val r = rec ?: return
            rec = null
            // Release wakes the reader thread's blocking read() — the
            // recorder can't linger holding the mic.
            runCatching { r.stop() }
            runCatching { r.release() }
            thread = null
        }
    }
}
