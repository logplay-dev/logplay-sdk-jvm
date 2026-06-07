package org.zeplinko.logplay.client.worker

import java.util.concurrent.atomic.AtomicInteger

/**
 * Distributes [freeSlots] evenly across registered types and rotates the start index every
 * iteration to prevent starvation. The minimum per-type limit is 1.
 */
public class RoundRobinAcquireStrategy : AcquireStrategy {
    private val rotation = AtomicInteger(0)

    override fun plan(
        types: List<String>,
        freeSlots: Int,
    ): List<AcquireStrategy.TypeAcquireRequest> {
        if (types.isEmpty() || freeSlots <= 0) return emptyList()
        val perType = (freeSlots / types.size).coerceAtLeast(1)
        val start =
            (rotation.getAndIncrement().mod(types.size)).let { if (it < 0) it + types.size else it }
        val rotated = types.drop(start) + types.take(start)
        var remaining = freeSlots
        val plan = ArrayList<AcquireStrategy.TypeAcquireRequest>(rotated.size)
        for (t in rotated) {
            if (remaining <= 0) break
            val limit = minOf(perType, remaining)
            plan.add(AcquireStrategy.TypeAcquireRequest(t, limit))
            remaining -= limit
        }
        return plan
    }
}
