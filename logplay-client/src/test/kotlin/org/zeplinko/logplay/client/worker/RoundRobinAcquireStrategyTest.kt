package org.zeplinko.logplay.client.worker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoundRobinAcquireStrategyTest {
    @Test
    fun `returns empty when no types or no slots`() {
        val s = RoundRobinAcquireStrategy()
        assertThat(s.plan(emptyList(), 4)).isEmpty()
        assertThat(s.plan(listOf("a"), 0)).isEmpty()
    }

    @Test
    fun `distributes slots evenly across types`() {
        val s = RoundRobinAcquireStrategy()
        val plan = s.plan(listOf("a", "b"), 4)
        assertThat(plan).hasSize(2)
        assertThat(plan.sumOf { it.limit }).isEqualTo(4)
        assertThat(plan.map { it.limit }).allMatch { it == 2 }
    }

    @Test
    fun `gives at least 1 per type`() {
        val s = RoundRobinAcquireStrategy()
        val plan = s.plan(listOf("a", "b", "c", "d"), 2)
        assertThat(plan.first().limit).isGreaterThanOrEqualTo(1)
        // At most 2 entries fit before we run out of slots
        assertThat(plan.sumOf { it.limit }).isLessThanOrEqualTo(2)
    }

    @Test
    fun `rotates start type across iterations`() {
        val s = RoundRobinAcquireStrategy()
        val firstStarts = (1..6).map { s.plan(listOf("a", "b", "c"), 3).first().type }.toSet()
        // Should hit all three types as starting position over several iterations.
        assertThat(firstStarts).containsExactlyInAnyOrder("a", "b", "c")
    }

    @Test
    fun `single type gets all available slots`() {
        val s = RoundRobinAcquireStrategy()
        val plan = s.plan(listOf("a"), 5)
        assertThat(plan).hasSize(1)
        assertThat(plan[0].type).isEqualTo("a")
        assertThat(plan[0].limit).isEqualTo(5)
    }

    @Test
    fun `total plan limit never exceeds freeSlots`() {
        val s = RoundRobinAcquireStrategy()
        for (slots in 1..10) {
            for (typeCount in 1..6) {
                val types = (1..typeCount).map { "t$it" }
                val plan = s.plan(types, slots)
                assertThat(plan.sumOf { it.limit }).isLessThanOrEqualTo(slots)
                // Every entry has positive limit (no wasted entries).
                assertThat(plan.map { it.limit }).allMatch { it >= 1 }
            }
        }
    }

    @Test
    fun `negative freeSlots returns empty`() {
        val s = RoundRobinAcquireStrategy()
        assertThat(s.plan(listOf("a"), -1)).isEmpty()
    }

    @Test
    fun `plan covers all types when freeSlots is at least the type count`() {
        val s = RoundRobinAcquireStrategy()
        val plan = s.plan(listOf("a", "b", "c"), 3)
        assertThat(plan.map { it.type }).containsExactlyInAnyOrder("a", "b", "c")
    }
}
