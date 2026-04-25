package org.zeplinko.logplay.client.worker

/**
 * Decides which job [type]s to poll on a given acquire-loop iteration and how to distribute the
 * total available capacity ([freeSlots]) across them.
 */
public interface AcquireStrategy {
    /**
     * @param types snapshot of currently-registered job types
     * @param freeSlots number of free slots in the worker's executor (always >= 1)
     * @return list of (type, limit) pairs to acquire on this iteration. The sum of limits should
     *   typically not exceed [freeSlots]. Implementations may return an empty list to skip this
     *   iteration.
     */
    public fun plan(types: List<String>, freeSlots: Int): List<TypeAcquireRequest>

    public data class TypeAcquireRequest(val type: String, val limit: Int)
}
