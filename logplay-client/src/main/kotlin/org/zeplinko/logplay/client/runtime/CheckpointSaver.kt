package org.zeplinko.logplay.client.runtime

import org.slf4j.LoggerFactory
import org.zeplinko.logplay.client.exception.CheckpointDivergenceException
import org.zeplinko.logplay.client.exception.InvalidCheckpointOrderException
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.model.Checkpoint

/** Saves checkpoints, with chain-ahead recovery when another actor wrote past us. */
internal class CheckpointSaver(private val client: JobClient) {

    fun save(
        jobId: String,
        workerId: String,
        name: String?,
        previousCheckpointId: String?,
        data: ByteArray?,
    ): Checkpoint {
        return try {
            client.saveCheckpoint(jobId, workerId, name, previousCheckpointId, data)
        } catch (e: InvalidCheckpointOrderException) {
            recover(jobId, name, previousCheckpointId, e)
        }
    }

    private fun recover(
        jobId: String,
        name: String?,
        previousCheckpointId: String?,
        cause: InvalidCheckpointOrderException,
    ): Checkpoint {
        val page = client.getCheckpoints(jobId, after = previousCheckpointId, limit = 1)
        val nextOnServer = page.checkpoints.firstOrNull()
        if (nextOnServer == null) throw cause
        if (nextOnServer.name == name) {
            log.info(
                "Chain-ahead recovery: job {} already has checkpoint name='{}' at this position; reusing server data",
                jobId,
                name,
            )
            return nextOnServer
        }
        throw CheckpointDivergenceException(
            jobId = jobId,
            cursor = -1,
            expected = name,
            actual = nextOnServer.name,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CheckpointSaver::class.java)
    }
}
