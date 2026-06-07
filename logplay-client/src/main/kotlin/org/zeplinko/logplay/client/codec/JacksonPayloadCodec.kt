package org.zeplinko.logplay.client.codec

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.Type

/**
 * JSON [PayloadCodec] backed by a Jackson [ObjectMapper]. The default codec used by every
 * `Class<T>` / `TypeToken<T>` overload across the SDK (`Worker.registerHandler`,
 * `CreateJobRequest.builder`, `ctx.run`).
 *
 * Null in → null out: [encode] returns null bytes for null values (rather than the JSON literal
 * `null`), and [decode] returns null for null bytes. Both sides skip Jackson entirely when null.
 *
 * Most users build instances via `LogPlayManager.jsonCodec(...)` so the manager's configured mapper
 * is reused; the constructors here are public for direct use cases (custom mapper, codec
 * composition).
 */
public class JacksonPayloadCodec<T>
private constructor(private val mapper: ObjectMapper, private val javaType: JavaType) :
    PayloadCodec<T> {

    public constructor(
        mapper: ObjectMapper,
        type: Class<T>,
    ) : this(mapper, mapper.constructType(type))

    public constructor(
        mapper: ObjectMapper,
        type: TypeToken<T>,
    ) : this(mapper, mapper.constructType(type.type))

    override fun encode(value: T?): ByteArray? = value?.let { mapper.writeValueAsBytes(it) }

    override fun decode(bytes: ByteArray?): T? = bytes?.let { mapper.readValue(it, javaType) }

    public companion object {
        /** Build a codec for a non-generic [clazz] backed by [mapper]. */
        @JvmStatic
        public fun <T> of(mapper: ObjectMapper, clazz: Class<T>): JacksonPayloadCodec<T> =
            JacksonPayloadCodec(mapper, clazz)

        /** Build a codec for a parameterized generic type captured by [token]. */
        @JvmStatic
        public fun <T> of(mapper: ObjectMapper, token: TypeToken<T>): JacksonPayloadCodec<T> =
            JacksonPayloadCodec(mapper, token)

        /** Build a codec from a raw `java.lang.reflect.Type` — escape hatch for advanced cases. */
        @JvmStatic
        public fun <T> ofType(mapper: ObjectMapper, type: Type): JacksonPayloadCodec<T> =
            JacksonPayloadCodec(mapper, mapper.constructType(type))
    }
}
