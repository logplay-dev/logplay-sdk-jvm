package org.zeplinko.logplay.client.http

import com.fasterxml.jackson.core.type.TypeReference
import java.net.URI

/**
 * Internal abstraction over the actual HTTP client. Allows the rest of the SDK to be tested without
 * a real network.
 */
internal interface HttpTransport {
    fun <T> post(uri: URI, body: Any?, responseType: Class<T>): T

    fun <T> post(uri: URI, body: Any?, responseType: TypeReference<T>): T

    fun postNoContent(uri: URI, body: Any?): Unit

    fun <T> get(uri: URI, responseType: Class<T>): T

    fun <T> get(uri: URI, responseType: TypeReference<T>): T

    fun delete(uri: URI)
}
