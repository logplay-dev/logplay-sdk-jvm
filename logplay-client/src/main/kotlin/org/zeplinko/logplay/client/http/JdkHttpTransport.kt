package org.zeplinko.logplay.client.http

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import org.zeplinko.logplay.client.exception.LogPlayTransportException

internal class JdkHttpTransport(
    private val client: HttpClient,
    private val mapper: ObjectMapper,
    private val errorMapper: ErrorMapper,
    private val requestTimeout: Duration,
    private val defaultHeaders: Map<String, String>,
) : HttpTransport {

    override fun <T> post(uri: URI, body: Any?, responseType: Class<T>): T {
        val resp = send("POST", uri, body)
        return mapper.readValue(resp, responseType)
    }

    override fun <T> post(uri: URI, body: Any?, responseType: TypeReference<T>): T {
        val resp = send("POST", uri, body)
        return mapper.readValue(resp, responseType)
    }

    override fun postNoContent(uri: URI, body: Any?) {
        send("POST", uri, body)
    }

    override fun <T> get(uri: URI, responseType: Class<T>): T {
        val resp = send("GET", uri, null)
        return mapper.readValue(resp, responseType)
    }

    override fun <T> get(uri: URI, responseType: TypeReference<T>): T {
        val resp = send("GET", uri, null)
        return mapper.readValue(resp, responseType)
    }

    override fun delete(uri: URI) {
        send("DELETE", uri, null)
    }

    private fun send(method: String, uri: URI, body: Any?): ByteArray {
        val builder =
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
        defaultHeaders.forEach { (k, v) -> builder.header(k, v) }

        when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            "POST" -> {
                val publisher =
                    if (body == null) BodyPublishers.noBody()
                    else BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))
                if (body != null) builder.header("Content-Type", "application/json")
                builder.POST(publisher)
            }
            else -> error("Unsupported method: $method")
        }

        val response: HttpResponse<ByteArray> =
            try {
                client.send(builder.build(), BodyHandlers.ofByteArray())
            } catch (e: IOException) {
                throw LogPlayTransportException("Network error calling $method $uri", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LogPlayTransportException("Interrupted calling $method $uri", e)
            }

        val status = response.statusCode()
        if (status in 200..299) return response.body() ?: ByteArray(0)
        throw errorMapper.translate(status, response.body() ?: ByteArray(0))
    }
}
