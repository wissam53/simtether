package com.simtether.rooted

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.DataOutputStream

/**
 * Downlink capture running as root.
 *
 * `AudioSource.VOICE_CALL` needs CAPTURE_AUDIO_OUTPUT — a signature|
 * privileged permission a normal app can't hold. But a process running
 * as uid=0 passes AudioFlinger's record-permission check outright, so
 * we spawn a second JVM via `su` + `app_process` with CLASSPATH aimed
 * at our own APK: the recorder class below lives in the same dex, no
 * extra files to ship or extract.
 *
 *   su -c 'CLASSPATH=<base.apk> app_process /system/bin \
 *          com.simtether.rooted.RootCaptureDaemon <source> <rate>'
 *
 * The daemon writes raw PCM16 mono to stdout; the parent streams it
 * into the media channel. Stderr carries diagnostics back to logcat.
 */
object RootCaptureDaemon {
    private const val TAG = "SimTether.CapDaemon"
    const val SAMPLE_RATE = 8_000
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2  // 320

    // Capture-source preference order — spike doc Test D. VOICE_CALL
    // is both directions where the HAL supports it; DOWNLINK is caller
    // voice only; VOICE_COMMUNICATION is the app-visible fallback
    // (usually mic-only, but works on some ROMs).
    private val SOURCES = intArrayOf(
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_DOWNLINK,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    )

    @Volatile private var proc: Process? = null
    @Volatile private var reader: Thread? = null

    /**
     * Spawn the root recorder and stream PCM to [onPcm] until stopped
     * or the daemon dies. Returns false when root/daemon start fails.
     */
    fun start(context: Context, onPcm: (ByteArray) -> Unit): Boolean {
        stop()
        val apk = context.packageCodePath ?: return false
        val source = SOURCES.first()
        val cmd = "CLASSPATH='$apk' app_process /system/bin " +
            "--nice-name=stcap ${javaClass.name} $source $SAMPLE_RATE"
        val p = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        }.getOrElse {
            Log.e(TAG, "spawn failed", it); return false
        }
        proc = p
        val t = Thread({
            val buf = ByteArray(FRAME_BYTES)
            val inp = p.inputStream
            try {
                while (true) {
                    // Read exactly one frame — the daemon may flush
                    // partial buffers and a short read would desync.
                    var off = 0
                    while (off < buf.size) {
                        val n = inp.read(buf, off, buf.size - off)
                        if (n < 0) throw java.io.EOFException("daemon stdout closed")
                        off += n
                    }
                    onPcm(buf.copyOf())
                }
            } catch (e: Exception) {
                Log.d(TAG, "daemon read ended: ${e.message}")
            }
        }, "stcap-read").also { it.isDaemon = true }
        reader = t
        // Daemon diagnostics → logcat, so failures aren't silent.
        Thread({
            p.errorStream.bufferedReader().forEachLine { Log.w(TAG, "daemon: $it") }
        }, "stcap-err").also { it.isDaemon = true }.start()
        t.start()
        Log.i(TAG, "root capture daemon spawned (source=$source)")
        return true
    }

    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
        reader = null
    }

    /**
     * ── Daemon side ────────────────────────────────────────────────
     * Runs under app_process as uid=0 — NOT inside the app. System
     * classes only; stdout is the PCM pipe, stderr is diagnostics.
     * Args: <audioSource> <sampleRate>
     */
    // Runs under `su` as uid=0 — app-level runtime permissions don't
    // apply to this process at all; a refused source throws and the
    // loop tries the next one.
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun main(args: Array<String>) {
        val source = args.getOrNull(0)?.toIntOrNull()
            ?: MediaRecorder.AudioSource.VOICE_CALL
        val rate = args.getOrNull(1)?.toIntOrNull() ?: SAMPLE_RATE
        val out = DataOutputStream(System.out)
        fun fail(msg: String, e: Throwable? = null): Nothing {
            System.err.println("FAIL $msg ${e?.message ?: ""}")
            kotlin.system.exitProcess(2)
        }
        // Try sources in order — the first that records wins. A
        // SecurityException means this UID can't take the source even
        // as root (SELinux/domain policy) — try the next.
        var rec: AudioRecord? = null
        for (s in SOURCES) {
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            try {
                val r = AudioRecord(
                    s, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 2)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording()
                    rec = r
                    System.err.println("capture: source=$s rate=$rate")
                    break
                }
                r.release()
            } catch (e: Exception) {
                System.err.println("source=$s refused: ${e.message}")
            }
        }
        val r = rec ?: fail("no usable capture source")
        val buf = ByteArray(FRAME_BYTES)
        try {
            while (true) {
                var off = 0
                while (off < buf.size) {
                    val n = r.read(buf, off, buf.size - off)
                    if (n <= 0) throw IllegalStateException("read=$n")
                    off += n
                }
                out.write(buf)
                out.flush()
            }
        } catch (e: Exception) {
            fail("record loop died", e)
        }
    }
}
