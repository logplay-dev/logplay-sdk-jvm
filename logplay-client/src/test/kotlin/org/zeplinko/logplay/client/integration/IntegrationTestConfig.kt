package org.zeplinko.logplay.client.integration

import java.time.Duration

/**
 * Resolves the LogPlay server connection settings for integration tests, in this order:
 * 1. JVM system property (set by the Gradle `integrationTest` task)
 * 2. Environment variable
 * 3. Default
 */
internal object IntegrationTestConfig {
    const val BASE_URL_PROP: String = "logplay.server.baseUrl"
    const val BASE_URL_ENV: String = "LOGPLAY_SERVER_BASE_URL"
    const val HTTP_TIMEOUT_PROP: String = "logplay.server.httpTimeout"
    const val HTTP_TIMEOUT_ENV: String = "LOGPLAY_SERVER_HTTP_TIMEOUT"
    const val DEFAULT_BASE_URL: String = "http://localhost:8080"

    val baseUrl: String =
        (System.getProperty(BASE_URL_PROP) ?: System.getenv(BASE_URL_ENV) ?: DEFAULT_BASE_URL)
            .trimEnd('/')

    fun httpTimeout(): Duration {
        val raw = System.getProperty(HTTP_TIMEOUT_PROP) ?: System.getenv(HTTP_TIMEOUT_ENV)
        return raw?.toLongOrNull()?.let(Duration::ofMillis) ?: Duration.ofSeconds(5)
    }
}
