package org.zeplinko.logplay.client.codec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonPayloadCodecTest {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class Sample(val name: String, val count: Int)

    @Test
    fun `round trips a simple object`() {
        val codec: PayloadCodec<Sample> = JacksonPayloadCodec.of(mapper, Sample::class.java)
        val original = Sample("hello", 42)
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `round trips a generic List via TypeToken`() {
        val token = object : TypeToken<List<Sample>>() {}
        val codec = JacksonPayloadCodec.of(mapper, token)
        val original = listOf(Sample("a", 1), Sample("b", 2))
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `null encode and decode pass through without invoking Jackson`() {
        val codec: PayloadCodec<Sample> = JacksonPayloadCodec.of(mapper, Sample::class.java)
        assertThat(codec.encode(null)).isNull()
        assertThat(codec.decode(null)).isNull()
    }

    @Test
    fun `null pass-through also works for TypeToken-form codec`() {
        val token = object : TypeToken<List<Sample>>() {}
        val codec = JacksonPayloadCodec.of(mapper, token)
        assertThat(codec.encode(null)).isNull()
        assertThat(codec.decode(null)).isNull()
    }

    @Test
    fun `ofType factory accepts a raw java reflect Type`() {
        val codec: PayloadCodec<Sample> =
            JacksonPayloadCodec.ofType(mapper, Sample::class.java as java.lang.reflect.Type)
        val original = Sample("via-Type", 99)
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decode of malformed bytes throws (not silently null)`() {
        val codec: PayloadCodec<Sample> = JacksonPayloadCodec.of(mapper, Sample::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
                codec.decode("not-valid-json".toByteArray())
            }
            .isInstanceOf(com.fasterxml.jackson.core.JacksonException::class.java)
    }

    @Test
    fun `encode produces round-trippable JSON for empty collections`() {
        val token = object : TypeToken<List<Sample>>() {}
        val codec = JacksonPayloadCodec.of(mapper, token)
        val bytes = codec.encode(emptyList())
        assertThat(bytes).isNotNull
        // Empty list is `[]` (2 bytes), distinct from null bytes (which would mean "no payload").
        assertThat(bytes!!.size).isEqualTo(2)
        assertThat(codec.decode(bytes)).isEmpty()
    }
}
