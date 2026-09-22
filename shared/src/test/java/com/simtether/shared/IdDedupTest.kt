package com.simtether.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdDedupTest {

    @Test
    fun `first sighting is fresh, repeat is a dup`() {
        val d = IdDedup()
        assertTrue(d.add("x"))
        assertFalse(d.add("x"))
        assertTrue(d.add("y"))
    }

    @Test
    fun `oldest ids evict past capacity`() {
        val d = IdDedup(maxSize = 4)
        repeat(4) { assertTrue(d.add("id-$it")) }
        d.add("id-4")
        // Evicted — counts as fresh again.
        assertTrue(d.add("id-0"))
        assertFalse(d.add("id-4"))
    }
}
