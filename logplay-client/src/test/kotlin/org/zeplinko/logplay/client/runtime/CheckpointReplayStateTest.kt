package org.zeplinko.logplay.client.runtime

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.model.Checkpoint
import org.zeplinko.logplay.client.model.CheckpointPage

class CheckpointReplayStateTest {
    private val now: Instant = Instant.parse("2026-04-25T00:00:00Z")

    private fun cp(id: String, prev: String?, name: String?): Checkpoint =
        Checkpoint(
            id = id,
            jobId = "j-1",
            previousCheckpointId = prev,
            name = name,
            createdAt = now,
            data = ByteArray(0),
        )

    @Test
    fun `peek returns null when empty and no more pages`() {
        val state =
            CheckpointReplayState(CheckpointPage(emptyList(), false)) { error("should not fetch") }
        assertThat(state.peek()).isNull()
    }

    @Test
    fun `advance moves cursor and lastKnownId tracks last appended`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "step1")), false)
        val state = CheckpointReplayState(initial) { error("no fetch needed") }
        assertThat(state.peek()?.id).isEqualTo("c1")
        state.advance()
        state.appendNew(cp("c2", "c1", "step2"))
        assertThat(state.lastKnownId()).isEqualTo("c2")
    }

    @Test
    fun `lazy paging fetches more when cursor moves past loaded range`() {
        var fetchCalls = 0
        val initial = CheckpointPage(listOf(cp("c1", null, "step1")), true)
        val state =
            CheckpointReplayState(initial) { after ->
                fetchCalls++
                assertThat(after).isEqualTo("c1")
                CheckpointPage(listOf(cp("c2", "c1", "step2")), false)
            }
        assertThat(state.peek()?.id).isEqualTo("c1")
        state.advance()
        // cursor now at 1; loaded only has 1 item; serverHasMore=true
        assertThat(state.peek()?.id).isEqualTo("c2")
        assertThat(fetchCalls).isEqualTo(1)
    }

    @Test
    fun `nameAlreadyUsed checks only past cursor`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "alpha"), cp("c2", "c1", "beta")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        // cursor=0, nothing past it yet
        assertThat(state.nameAlreadyUsed("alpha")).isFalse()
        state.advance()
        assertThat(state.nameAlreadyUsed("alpha")).isTrue()
        assertThat(state.nameAlreadyUsed("beta")).isFalse()
    }

    @Test
    fun `lastKnownId is null when no checkpoints have been observed`() {
        val state = CheckpointReplayState(CheckpointPage(emptyList(), false)) { error("no") }
        assertThat(state.lastKnownId()).isNull()
    }

    @Test
    fun `lastKnownId reflects the last loaded checkpoint, not just appended`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "step1")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        // Even before any advance/append, the loaded set ends at c1.
        assertThat(state.lastKnownId()).isEqualTo("c1")
    }

    @Test
    fun `appendNew with null data is allowed (matches new Checkpoint nullability)`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        state.appendNew(
            Checkpoint(
                id = "c-null",
                jobId = "j-1",
                previousCheckpointId = null,
                name = "step",
                createdAt = now,
                data = null,
            )
        )
        assertThat(state.peek()?.data).isNull()
        assertThat(state.lastKnownId()).isEqualTo("c-null")
    }

    @Test
    fun `peek does not advance the cursor (idempotent)`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "a")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        assertThat(state.peek()?.id).isEqualTo("c1")
        assertThat(state.peek()?.id).isEqualTo("c1")
        assertThat(state.peek()?.id).isEqualTo("c1")
    }

    @Test
    fun `cursor returns the current position`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "a"), cp("c2", "c1", "b")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        assertThat(state.cursor()).isEqualTo(0)
        state.advance()
        assertThat(state.cursor()).isEqualTo(1)
        state.advance()
        assertThat(state.cursor()).isEqualTo(2)
    }

    @Test
    fun `peek returns null when cursor is past the loaded range and no more pages`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "a")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        state.advance()
        assertThat(state.peek()).isNull()
    }
}
