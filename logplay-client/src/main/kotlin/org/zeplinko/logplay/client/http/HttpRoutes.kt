package org.zeplinko.logplay.client.http

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object HttpRoutes {
    const val BASE: String = "/api/v1"

    fun jobs(baseUrl: String): URI = URI.create("$baseUrl$BASE/jobs")

    fun acquireJobs(baseUrl: String): URI = URI.create("$baseUrl$BASE/jobs/acquire")

    fun job(baseUrl: String, jobId: String): URI = URI.create("$baseUrl$BASE/jobs/${enc(jobId)}")

    fun complete(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/complete")

    fun release(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/release")

    fun reportError(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/error")

    fun abort(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/abort")

    fun checkpoints(baseUrl: String, jobId: String, after: String?, limit: Int?): URI {
        val qs = buildList {
            if (after != null) add("after=${enc(after)}")
            if (limit != null) add("limit=$limit")
        }
        val tail = if (qs.isEmpty()) "" else "?${qs.joinToString("&")}"
        return URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/checkpoints$tail")
    }

    fun saveCheckpoint(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/checkpoints")

    fun events(baseUrl: String, jobId: String): URI =
        URI.create("$baseUrl$BASE/jobs/${enc(jobId)}/events")

    fun workers(baseUrl: String): URI = URI.create("$baseUrl$BASE/workers")

    fun heartbeat(baseUrl: String, workerId: String): URI =
        URI.create("$baseUrl$BASE/workers/${enc(workerId)}/heartbeat")

    fun worker(baseUrl: String, workerId: String): URI =
        URI.create("$baseUrl$BASE/workers/${enc(workerId)}")

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
