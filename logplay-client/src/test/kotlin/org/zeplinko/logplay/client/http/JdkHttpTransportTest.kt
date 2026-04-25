package org.zeplinko.logplay.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.exception.JobNotFoundException

class JdkHttpTransportTest {
    private lateinit var server: HttpServer
    private lateinit var transport: JdkHttpTransport
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class Echo(val value: String)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        transport =
            JdkHttpTransport(
                client = HttpClient.newHttpClient(),
                mapper = mapper,
                errorMapper = ErrorMapper(mapper),
                requestTimeout = Duration.ofSeconds(5),
                defaultHeaders = mapOf("X-Test" to "1"),
            )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUri(path: String): URI =
        URI.create("http://127.0.0.1:${server.address.port}$path")

    @Test
    fun `POST with body sends Content-Type and JSON, returns deserialized body`() {
        var receivedMethod: String? = null
        var receivedContentType: String? = null
        var receivedBody: String? = null
        var receivedUserAgent: String? = null
        server.createContext(
            "/echo",
            HttpHandler { ex: HttpExchange ->
                receivedMethod = ex.requestMethod
                receivedContentType = ex.requestHeaders.getFirst("Content-Type")
                receivedUserAgent = ex.requestHeaders.getFirst("X-Test")
                receivedBody = ex.requestBody.bufferedReader().readText()
                val resp = """{"value":"pong"}""".toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            },
        )

        val result = transport.post(baseUri("/echo"), Echo("ping"), Echo::class.java)

        assertThat(result.value).isEqualTo("pong")
        assertThat(receivedMethod).isEqualTo("POST")
        assertThat(receivedContentType).isEqualTo("application/json")
        assertThat(receivedBody).isEqualTo("""{"value":"ping"}""")
        assertThat(receivedUserAgent).isEqualTo("1")
    }

    @Test
    fun `GET returns deserialized body`() {
        server.createContext(
            "/get",
            HttpHandler { ex ->
                val resp = """{"value":"hello"}""".toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            },
        )
        val result = transport.get(baseUri("/get"), Echo::class.java)
        assertThat(result.value).isEqualTo("hello")
    }

    @Test
    fun `DELETE sends no body and accepts 204`() {
        var seenMethod: String? = null
        server.createContext(
            "/del",
            HttpHandler { ex ->
                seenMethod = ex.requestMethod
                ex.sendResponseHeaders(204, -1)
                ex.responseBody.close()
            },
        )
        transport.delete(baseUri("/del"))
        assertThat(seenMethod).isEqualTo("DELETE")
    }

    @Test
    fun `non-2xx response throws typed exception`() {
        server.createContext(
            "/missing",
            HttpHandler { ex ->
                val body = """{"error":"Job not found: job-1"}""".toByteArray()
                ex.sendResponseHeaders(404, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            },
        )
        assertThatThrownBy { transport.get(baseUri("/missing"), Echo::class.java) }
            .isInstanceOf(JobNotFoundException::class.java)
    }
}
