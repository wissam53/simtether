package com.simtether.shared

/**
 * Bounded insertion-ordered id set. The client feeds bridge envelope
 * ids through here: a redelivery (ack lost in transit) reads as a dup
 * and gets re-acked without re-processing.
 */
class IdDedup(private val maxSize: Int = 512) {
    private val ids = LinkedHashSet<String>()

    /** True the first time [id] is seen; evicts oldest past maxSize. */
    @Synchronized
    fun add(id: String): Boolean {
        val fresh = ids.add(id)
        while (ids.size > maxSize) ids.remove(ids.first())
        return fresh
    }
}
