package com.simtether.shared

import android.content.Context
import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CallLogEntry(
    val callId: String,
    val number: String?,
    val displayName: String?,
    val state: Protocol.CallEvent.State,
    val timestamp: Long,
    // Derived across the call's lifetime — `state` alone can't tell a
    // missed call (RINGING→DISCONNECTED) from an ended one.
    val incoming: Boolean = false,
    val answered: Boolean = false,
) {
    val missed: Boolean get() =
        state == Protocol.CallEvent.State.DISCONNECTED && incoming && !answered

    fun label(context: Context): String = when {
        state != Protocol.CallEvent.State.DISCONNECTED -> context.getString(
            when (state) {
                Protocol.CallEvent.State.RINGING -> R.string.call_ringing
                Protocol.CallEvent.State.DIALING -> R.string.call_dialing
                Protocol.CallEvent.State.ACTIVE -> R.string.call_active
                Protocol.CallEvent.State.HOLDING -> R.string.call_holding
                else -> R.string.call_ended
            })
        missed -> context.getString(R.string.call_label_missed)
        !answered -> context.getString(R.string.call_label_no_answer)
        incoming -> context.getString(R.string.call_label_incoming)
        else -> context.getString(R.string.call_label_outgoing)
    }
}

/**
 * Recent call events, persisted JSONL — same shape as
 * ConversationStore. Feeds the Recents tab and the dashboard feed.
 * Shared module: written on the client from relayed events AND on the
 * bridge from live Telecom callbacks (local-mode recents).
 */
object CallLogStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val entries: StateFlow<List<CallLogEntry>> = _entries

    private var file: File? = null

    // Single writer thread — see ConversationStore.
    private val writer = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor { r ->
            Thread(r, "calllog-writer").also { it.isDaemon = true }
        }
    private val writeLock = Any()
    private var writeTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "call_log.jsonl")
        file = f
        writer.execute {
            if (!f.exists()) return@execute
            val loaded = (SecureFile.read(f) ?: return@execute).lineSequence()
                .mapNotNull { runCatching { json.decodeFromString<CallLogEntry>(it) }.getOrNull() }
                .toList()
            // Keep anything appended before this load landed.
            _entries.value = (loaded + _entries.value).distinct()
        }
    }

    /** Record meaningful transitions; returns the updated entry. */
    fun onEvent(e: Protocol.CallEvent): CallLogEntry? = when (e.state) {
        Protocol.CallEvent.State.RINGING,
        Protocol.CallEvent.State.DIALING,
        Protocol.CallEvent.State.ACTIVE,
        Protocol.CallEvent.State.DISCONNECTED -> append(e)
        else -> null
    }

    private fun append(e: Protocol.CallEvent): CallLogEntry {
        // Update in place if this callId already logged — direction and
        // answered accumulate over the call's states.
        val existing = _entries.value.indexOfLast { it.callId == e.callId }
        val prev = _entries.value.getOrNull(existing)
        val entry = CallLogEntry(
            callId = e.callId,
            number = e.number ?: prev?.number,
            displayName = e.displayName ?: prev?.displayName,
            state = e.state,
            timestamp = System.currentTimeMillis(),
            incoming = prev?.incoming ?: (e.state == Protocol.CallEvent.State.RINGING),
            answered = prev?.answered == true ||
                e.state == Protocol.CallEvent.State.ACTIVE ||
                e.state == Protocol.CallEvent.State.HOLDING,
        )
        _entries.value = if (existing >= 0) {
            _entries.value.toMutableList().also { it[existing] = entry }
        } else {
            _entries.value + entry
        }
        rewrite()
        return entry
    }

    /** Debounced persist — a call generates several events in a row. */
    private fun rewrite() {
        val f = file ?: return
        synchronized(writeLock) {
            writeTask?.cancel(false)
            writeTask = writer.schedule(
                {
                    SecureFile.write(f, _entries.value
                        .joinToString("") { e -> json.encodeToString(e) + "\n" })
                },
                WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    private const val WRITE_DELAY_MS = 250L
}
