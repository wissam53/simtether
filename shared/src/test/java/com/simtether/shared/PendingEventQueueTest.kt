package com.simtether.shared

import com.simtether.shared.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingEventQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun env(id: String, type: String = "sms.received") =
        Protocol.Envelope(id, type, id.hashCode().toLong(), "{}")

    // Plaintext codec — exercises the queue without AndroidKeyStore.
    private fun queue(file: java.io.File, max: Int = 200) = PendingEventQueue(
        file, max,
        readFile = { f -> if (f.exists()) f.readText() else null },
        writeFile = { f, t -> f.writeText(t) },
    )

    @Test
    fun `events survive a simulated restart`() {
        val f = tmp.newFile()
        val q = queue(f)
        q.add(env("a"))
        q.add(env("b"))

        val restored = queue(f).also { it.load() }
        assertEquals(listOf("a", "b"), restored.snapshot().map { it.id })
    }

    @Test
    fun `remove drops only the acked event`() {
        val f = tmp.newFile()
        val q = queue(f)
        q.add(env("a")); q.add(env("b")); q.add(env("c"))

        assertEquals("b", q.remove("b")?.id)
        assertNull(q.remove("nope"))
        assertEquals(listOf("a", "c"), q.snapshot().map { it.id })
    }

    @Test
    fun `acked events stay gone after reload`() {
        val f = tmp.newFile()
        val q = queue(f)
        q.add(env("a")); q.add(env("b"))
        q.remove("a")
        Thread.sleep(400) // debounced persist

        val restored = queue(f).also { it.load() }
        assertEquals(listOf("b"), restored.snapshot().map { it.id })
    }

    @Test
    fun `add past capacity drops oldest and reports it`() {
        val f = tmp.newFile()
        val q = queue(f, max = 3)
        q.add(env("1")); q.add(env("2")); q.add(env("3"))

        val dropped = q.add(env("4"))
        assertEquals(listOf("1"), dropped.map { it.id })
        assertEquals(listOf("2", "3", "4"), q.snapshot().map { it.id })
    }

    @Test
    fun `snapshot is safe to iterate during flush`() {
        val f = tmp.newFile()
        val q = queue(f)
        q.add(env("a")); q.add(env("b"))

        // Flush sends from the snapshot; acks land mid-iteration.
        var sent = 0
        for (e in q.snapshot()) { sent++; q.remove(e.id) }
        assertEquals(2, sent)
        assertTrue(q.snapshot().isEmpty())
    }

    @Test
    fun `empty queue deletes the file`() {
        val f = tmp.newFile()
        val q = queue(f)
        q.add(env("a"))
        assertTrue(f.exists())
        q.remove("a")
        Thread.sleep(400)
        assertTrue(!f.exists())
    }
}
