package com.simtether.shared

import android.content.Context
import com.simtether.shared.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Call once from a context-holding site (service onCreate). */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "conversations.jsonl")
        file = f
        if (!f.exists()) return
        _messages.value = f.useLines { lines ->
            lines.mapNotNull { runCatching { json.decodeFromString<ChatMessage>(it) }.getOrNull() }
                .toList()
        }
    }

    fun onIncoming(sms: Protocol.SmsReceived) =
        append(ChatMessage(sms.address, sms.body, sms.timestamp, outgoing = false))

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
            if (idx >= 0 && _messages.value[idx].status == "sending") {
                _messages.value = _messages.value.toMutableList().also {
                    it[idx] = it[idx].copy(status = "unconfirmed")
                }
                rewrite()
            }
        }
        return ref
    }

    /** sms.status event → mark the outgoing bubble. */
    fun onStatus(s: Protocol.SmsStatus) {
        val ref = s.ref ?: return
        val idx = _messages.value.indexOfLast { it.ref == ref }
        if (idx < 0) return
        _messages.value = _messages.value.toMutableList().also {
            it[idx] = it[idx].copy(status = s.status.name.lowercase())
        }
        rewrite()
    }

    private fun append(m: ChatMessage) {
        _messages.value = _messages.value + m
        rewrite()
    }

    private fun rewrite() {
        runCatching {
            file?.writeText(
                _messages.value.joinToString("") { json.encodeToString(it) + "\n" }
            )
        }
    }

    fun thread(address: String): List<ChatMessage> =
        _messages.value.filter { it.address == address }.sortedBy { it.timestamp }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default)
    private const val SEND_TIMEOUT_MS = 60_000L
}
