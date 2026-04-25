package org.zeplinko.logplay.client.integration

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.zeplinko.logplay.client.LogPlayManager
import org.zeplinko.logplay.client.worker.Worker
import org.zeplinko.logplay.client.worker.WorkerConfig

/**
 * Base class for SDK integration tests. Brings up a shared [LogPlayManager], aborts the suite if
 * the server is unreachable, and tracks any [Worker]s created via [trackedWorker] so they're
 * stopped after each test (avoiding thread/state leaks across tests on a shared server).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(ServerReachabilityExtension::class)
abstract class AbstractIntegrationTest {

    protected lateinit var manager: LogPlayManager
    protected lateinit var groupId: String

    @BeforeAll
    fun setUpManager() {
        groupId = uniqueGroupId()
        manager =
            LogPlayManager.builder()
                .baseUrl(IntegrationTestConfig.baseUrl)
                .groupId(groupId)
                .httpTimeout(IntegrationTestConfig.httpTimeout())
                .build()
    }

    private val activeWorkers = CopyOnWriteArrayList<Worker>()

    protected fun trackedWorker(
        workerId: String,
        config: WorkerConfig = WorkerConfig.defaults(),
    ): Worker = manager.newWorker(workerId, config).also { activeWorkers += it }

    @AfterEach
    fun stopWorkers() {
        activeWorkers.forEach { runCatching { it.stop() } }
        activeWorkers.clear()
    }

    // --- Unique ID helpers -----------------------------------------------------------------------
    // groupId/workerId/idempotencyKey max length is 64 chars on the server; type is 512.

    protected fun uniqueGroupId(prefix: String = "g"): String = "$prefix-${shortId()}"

    protected fun uniqueWorkerId(prefix: String = "w"): String = "$prefix-${shortId()}"

    protected fun uniqueType(prefix: String = "t"): String = "$prefix-${shortId()}"

    protected fun uniqueKey(prefix: String = "k"): String = "$prefix-${shortId()}"

    private fun shortId(): String = UUID.randomUUID().toString().substring(0, 12)
}
