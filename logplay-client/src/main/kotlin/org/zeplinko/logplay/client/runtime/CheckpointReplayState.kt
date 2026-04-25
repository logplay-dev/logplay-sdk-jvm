package org.zeplinko.logplay.client.runtime

import org.zeplinko.logplay.client.model.Checkpoint
import org.zeplinko.logplay.client.model.CheckpointPage

/**
 * Lazily-paged cursor over a job's checkpoints. Pages are fetched on demand as the cursor advances
 * past the currently-loaded set.
 */
internal class CheckpointReplayState(
    initialPage: CheckpointPage,
    private val pageFetcher: (after: String?) -> CheckpointPage,
) {
    private val loaded: MutableList<Checkpoint> = ArrayList(initialPage.checkpoints)
    private var cursor: Int = 0
    private var serverHasMore: Boolean = initialPage.hasMore

    /**
     * Returns the checkpoint at the current cursor without advancing, or null if none available.
     */
    fun peek(): Checkpoint? {
        ensureCursorLoaded()
        return loaded.getOrNull(cursor)
    }

    fun advance() {
        cursor++
    }

    fun cursor(): Int = cursor

    fun lastKnownId(): String? = loaded.lastOrNull()?.id

    fun appendNew(checkpoint: Checkpoint) {
        loaded.add(checkpoint)
    }

    fun nameAlreadyUsed(name: String?): Boolean {
        if (name == null) return false
        for (i in 0 until cursor) if (loaded[i].name == name) return true
        return false
    }

    /**
     * Fetch additional pages until either the cursor is in range or the server has nothing more.
     */
    private fun ensureCursorLoaded() {
        while (cursor >= loaded.size && serverHasMore) {
            val page = pageFetcher(loaded.lastOrNull()?.id)
            loaded.addAll(page.checkpoints)
            serverHasMore = page.hasMore
            if (page.checkpoints.isEmpty()) return
        }
    }
}
