package org.zeplinko.logplay.client.integration

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Aborts the integration test suite at @BeforeAll if the LogPlay server is not reachable at the
 * configured base URL. Probes once per JVM (cached), so subsequent test classes don't pay the
 * round-trip.
 */
internal class ServerReachabilityExtension : BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) {
        val cached = probeResult.get()
        if (cached != null) {
            cached.failureMessage?.let { throw IllegalStateException(it) }
            return
        }
        val result = probe(IntegrationTestConfig.baseUrl, IntegrationTestConfig.httpTimeout())
        // First-writer-wins; if another thread won we still surface its result below.
        probeResult.compareAndSet(null, result)
        probeResult.get()?.failureMessage?.let { throw IllegalStateException(it) }
    }

    private fun probe(baseUrl: String, timeout: Duration): ProbeResult {
        val client = HttpClient.newBuilder().connectTimeout(timeout).build()
        val probeUri = URI.create("$baseUrl/api/v1/jobs/probe-${UUID.randomUUID()}")
        val request = HttpRequest.newBuilder().uri(probeUri).timeout(timeout).GET().build()
        return try {
            // Any HTTP response (200, 4xx, 5xx) proves the server is up and routing.
            client.send(request, HttpResponse.BodyHandlers.discarding())
            ProbeResult.reachable()
        } catch (e: IOException) {
            ProbeResult.unreachable(buildFailureMessage(baseUrl, e))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ProbeResult.unreachable(buildFailureMessage(baseUrl, e))
        }
    }

    private fun buildFailureMessage(baseUrl: String, cause: Throwable): String =
        """

        ============================================================
         LogPlay server is not reachable at $baseUrl
         (${cause.javaClass.simpleName}: ${cause.message ?: "no detail"})

         Start the server first:
           cd /Volumes/Workspace/LogPlay/logplay-server
           ./gradlew :logplay-server-h2:run

         Override the URL with:
           -P${IntegrationTestConfig.BASE_URL_PROP}=http://host:port
           ${IntegrationTestConfig.BASE_URL_ENV}=http://host:port
        ============================================================
        """
            .trimIndent()

    private data class ProbeResult(val failureMessage: String?) {
        companion object {
            fun reachable(): ProbeResult = ProbeResult(failureMessage = null)

            fun unreachable(message: String): ProbeResult = ProbeResult(failureMessage = message)
        }
    }

    private companion object {
        private val probeResult: AtomicReference<ProbeResult?> = AtomicReference(null)
    }
}
