package org.zeplinko.logplay.client.model

/**
 * One page of a job's checkpoint stream. If [hasMore] is `true`, fetch the next page by passing the
 * last item's `id` as the `after` cursor to `LogPlayManager.getCheckpoints(jobId, after, limit)`.
 */
public data class CheckpointPage(val checkpoints: List<Checkpoint>, val hasMore: Boolean)
