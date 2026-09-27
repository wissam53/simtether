package com.simtether.shared

import android.content.Context
import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatMessage(
    val address: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val ref: String? = null,        // outgoing only — correlates sms.status
    val status: String? = null,     // sending | sent | delivered | failed
    // Incoming messages arrive read=false. Default true so history
    // written before this field existed doesn't flood as unread.
    val read: Boolean = true,
)

/**
 * SMS threads, keyed by address. Persisted as JSONL so history
 * survives restarts — swap for Room when threads/search land.
 * Shared module: the client records relayed SMS; the bridge records
 * local SMS when running the SIM phone's own UI (bridge off).
 */
object ConversationStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private var file: File? = null

    // All disk I/O funnels through one writer thread — the caller's
    // thread (UI, Telecom callback) never encrypts or writes.
    private val writer = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor { r ->
            Thread(r, "conv-writer").also { it.isDaemon = true }
        }
    private val writeLock = Any()
    private var writeTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Screenshot/demo mode — in-memory only: no disk reads or writes,
     * so the real history is never seen or overwritten. Restarting the
     * app clears the flag and reloads the real file.
     */
    @Volatile var demoMode = false
        private set

    fun seedDemo(messages: List<ChatMessage>) {
        demoMode = true
        _messages.value = messages
        // A debounced write queued before the flag flipped could still
        // fire and persist demo data over the real file — drop it.
        synchronized(writeLock) {
            writeTask?.cancel(false)
            writeTask = null
        }
    }

    /** Call once from a context-holding site (service onCreate). */
    fun init(context: Context) {
        if (file != null || demoMode) return
        val f = File(context.filesDir, "conversations.jsonl")
        file = f
        writer.execute {
            if (!f.exists()) return@execute
            val loaded = (SecureFile.read(f) ?: return@execute).lineSequence()
                .mapNotNull { runCatching { json.decodeFromString<ChatMessage>(it) }.getOrNull() }
                .toList()
            // A demo seed landing mid-load would get clobbered by
            // this merge — bail instead of prepending real history.
            if (demoMode) return@execute
            // Keep anything appended before this load landed.
            _messages.update { (loaded + it).distinct() }
        }
    }

    fun onIncoming(sms: Protocol.SmsReceived) =
        append(ChatMessage(sms.address, sms.body, sms.timestamp,
            outgoing = false, read = false))

    /** Viewing a thread marks its incoming messages read. */
    fun markThreadRead(address: String) {
        if (_messages.value.none { it.address == address && !it.outgoing && !it.read }) return
        _messages.update { list ->
            list.map { if (it.address == address && !it.outgoing) it.copy(read = true) else it }
        }
        rewrite()
    }

    /** Total unread incoming messages — drives nav badges. */
    fun unreadCount(): Int =
        _messages.value.count { !it.outgoing && !it.read }

    /** Returns the generated ref so the caller can echo it in sms.send. */
    fun onOutgoing(address: String, body: String): String {
        val ref = java.util.UUID.randomUUID().toString()
        append(ChatMessage(address, body, System.currentTimeMillis(),
            outgoing = true, ref = ref, status = "sending"))
        // If sms.status never arrives (frame lost on a dying link,
        // service null race, queued and never flushed), don't leave
        // the bubble on "sending" forever — mark it unconfirmed. A
        // late status still overrides via onStatus.
        scope.launch {
            kotlinx.coroutines.delay(SEND_TIMEOUT_MS)
            val idx = _messages.value.indexOfLast { it.ref == ref }
            if (_messages.value.getOrNull(idx)?.status == "sending") {
                _messages.update { list ->
                    list.mapIndexed { i, m ->
                        if (i == idx && m.status == "sending") m.copy(status = "unconfirmed") else m
                    }
                }
                rewrite()
            }
        }
        return ref
    }

    /** An SMS that left the SIM outside the sms.send pipeline —
     *  e.g. Telecom's canned reply on reject-with-message. With a ref
     *  the bridge tracks the sent-box write and a sms.status resolves
     *  it; without one (old wire) it's optimistically "sent". */
    fun onEcho(address: String, body: String, timestamp: Long, ref: String? = null) =
        append(ChatMessage(address, body, timestamp, outgoing = true,
            ref = ref, status = if (ref != null) "sending" else "sent"))

    /** sms.status event → mark the outgoing bubble. */
    fun onStatus(s: Protocol.SmsStatus) {
        val ref = s.ref ?: return
        _messages.update { list ->
            val idx = list.indexOfLast { it.ref == ref }
            if (idx < 0) return@update list
            list.toMutableList().also { it[idx] = it[idx].copy(status = s.status.name.lowercase()) }
        }
        rewrite()
    }

    private fun append(m: ChatMessage) {
        // Demo mode drops live traffic so a real SMS can't leak
        // into a screenshot while seed data is showing.
        if (demoMode) return
        _messages.update { it + m }
        rewrite()
    }

    /**
     * Debounced persist — serializing + encrypting the full history on
     * every append is O(n) per message; a burst (queued-flush, send
     * storm) collapses into a single trailing write.
     */
    private fun rewrite() {
        val f = file ?: return
        if (demoMode) return
        synchronized(writeLock) {
            writeTask?.cancel(false)
            writeTask = writer.schedule(
                {
                    SecureFile.write(f, _messages.value
                        .joinToString("") { m -> json.encodeToString(m) + "\n" })
                },
                WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Unpair = erase: drop every message in memory and delete the
     * encrypted file. The delete rides the writer thread so it can't
     * race an in-flight write, and the pending debounced write is
     * cancelled or it would rewrite the cleared state afterwards.
     */
    fun wipe() {
        _messages.value = emptyList()
        synchronized(writeLock) {
            writeTask?.cancel(false)
            writeTask = null
        }
        if (!demoMode) file?.let { f -> writer.execute { f.delete() } }
    }

    fun thread(address: String): List<ChatMessage> =
        _messages.value.filter { it.address == address }.sortedBy { it.timestamp }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default)
    private const val SEND_TIMEOUT_MS = 60_000L
    private const val WRITE_DELAY_MS = 250L
}
