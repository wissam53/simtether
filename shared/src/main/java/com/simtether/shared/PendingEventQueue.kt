package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Store-and-forward queue of envelopes awaiting a client ack. Events
 * leave only via [remove] (the client's "ack") — never on send — so a
 * link that dies mid-flush loses nothing. Persisted as JSONL (encrypted
 * via SecureFile on device; injectable codec for tests).
 */
class PendingEventQueue(
    private val file: File,
    private val maxSize: Int = 200,
    private val readFile: (File) -> String? = { SecureFile.read(it) },
    private val writeFile: (File, String) -> Unit = { f, t -> SecureFile.write(f, t) },
) {
    private val queue = ConcurrentLinkedQueue<Protocol.Envelope>()

    private val writer = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor { r ->
            Thread(r, "pending-writer").also { it.isDaemon = true }
        }
    private val writeLock = Any()
    private var writeTask: java.util.concurrent.ScheduledFuture<*>? = null

    val size: Int get() = queue.size

    /**
     * Enqueue + persist. Returns events dropped to stay under maxSize —
     * oldest first. Persistence is synchronous: an OTP must hit disk
     * before we rely on the queue surviving a process death.
     */
    fun add(env: Protocol.Envelope): List<Protocol.Envelope> {
        val dropped = mutableListOf<Protocol.Envelope>()
        while (queue.size >= maxSize) queue.poll()?.let(dropped::add)
        queue.add(env)
        persist()
        return dropped
    }

    /** Remove one event by id (client ack). Debounced persist — a lost
     *  write only means a redelivery the client dedups. */
    fun remove(id: String): Protocol.Envelope? {
        val env = queue.firstOrNull { it.id == id } ?: return null
        queue.remove(env)
        persistSoon()
        return env
    }

    /** Snapshot for flushing — send each; removal happens only on ack. */
    fun snapshot(): List<Protocol.Envelope> = queue.toList()

    /** Restore events persisted before a service restart. */
    fun load() = runCatching {
        val text = readFile(file) ?: return@runCatching
        text.lineSequence().mapNotNullTo(queue) {
            runCatching { Protocol.decode(it) }.getOrNull()
        }
    }

    fun persist() = runCatching { writeNow() }

    private fun persistSoon() {
        synchronized(writeLock) {
            writeTask?.cancel(false)
            writeTask = writer.schedule(
                { writeNow() }, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun writeNow() {
        if (queue.isEmpty()) file.delete()
        else writeFile(file, queue.joinToString("") { Protocol.encode(it) + "\n" })
    }
}
