package com.simtether.client.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

/**
 * Client-side call audio for the rooted-bridge relay.
 *
 * Downlink: bridge PCM frames (8kHz mono PCM16) → an AudioTrack on
 * voice-communication usage, played through the earpiece/BT like a
 * normal call (MODE_IN_COMMUNICATION).
 *
 * Uplink: the mic → 20ms PCM frames → [sendPcm] → bridge injection.
 * VOICE_COMMUNICATION source gets the hardware AEC where the device
 * has one; software AEC/NS are attached when available. Runs only
 * when the bridge says the call wants uplink AND RECORD_AUDIO is
 * granted — otherwise the session is listen-only.
 *
 * Transport is TCP-ordered, so frames play as they arrive — the only
 * buffering needed is AudioTrack's own.
 */
class ClientAudioSession(
    private val context: Context,
    private val uplinkWanted: Boolean,
    private val sendPcm: (ByteArray) -> Unit,
) {
    private val TAG = "SimTether.AudioSession"

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var focusReq: AudioFocusRequest? = null

    @Volatile var uplinkLive = false
        private set

    fun start() {
        if (running) return
        running = true
        val am = context.getSystemService(AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus(am)

        val rate = RATE
        val outMin = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (outMin <= 0) { Log.w(TAG, "no out buffer"); running = false; return }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(outMin * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        startUplink()
        Log.i(TAG, "audio session started (uplink=$uplinkLive)")
    }

    /** Bridge downlink frame → speaker. Called on the WS thread. */
    fun onDownlink(pcm: ByteArray) {
        val t = track ?: return
        runCatching { t.write(pcm, 0, pcm.size) }
    }

    /** Local playback route — the in-call UI's speaker toggle. */
    fun setSpeakerphone(on: Boolean) {
        context.getSystemService(AudioManager::class.java)
            ?.isSpeakerphoneOn = on
    }

    private fun startUplink() {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!uplinkWanted || !granted) {
            if (uplinkWanted) Log.w(TAG, "uplink wanted but RECORD_AUDIO missing")
            return
        }
        val rate = RATE
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        // VOICE_COMMUNICATION enables the platform's HW echo path where
        // present — important since the far end's voice plays locally.
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                min * 2)
        }.getOrElse {
            Log.e(TAG, "mic open failed", it); return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return
        }
        recorder = r
        aec = runCatching {
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
            else null
        }.getOrNull()
        ns = runCatching {
            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
            else null
        }.getOrNull()
        runCatching { r.startRecording() }.getOrElse {
            Log.e(TAG, "mic start failed", it)
            r.release(); recorder = null; return
        }
        uplinkLive = true
        micThread = Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (running) {
                var off = 0
                while (off < buf.size && running) {
                    val n = r.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (!running) break
                if (off == buf.size) sendPcm(buf.copyOf())
            }
        }, "st-mic").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        uplinkLive = false
        micThread = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        aec = runCatching { aec?.release(); null }.getOrNull()
        ns = runCatching { ns?.release(); null }.getOrNull()
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        val am = context.getSystemService(AudioManager::class.java)
        abandonFocus(am)
        am?.mode = AudioManager.MODE_NORMAL
    }

    private fun requestFocus(am: AudioManager?) {
        am ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .build()
            am.requestAudioFocus(req)
            focusReq = req
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus(am: AudioManager?) {
        am ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusReq?.let { am.abandonAudioFocusRequest(it) }
            focusReq = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    companion object {
        const val RATE = 8_000
        const val FRAME_BYTES = RATE * 20 / 1000 * 2   // 20ms, 16-bit mono
    }
}
