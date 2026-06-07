package org.zeplinko.logplay.client.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.codec.Codecs
import org.zeplinko.logplay.client.codec.TypeToken

class CreateJobRequestBuilderTest {

    @Test
    fun `no-input builder builds with required fields`() {
        val req = CreateJobRequest.builder().type("t").idempotencyKey("k").build()
        assertThat(req.type).isEqualTo("t")
        assertThat(req.idempotencyKey).isEqualTo("k")
        assertThat(req.name).isNull()
        assertThat(req.maxRetries).isNull()
        assertThat(req.input as Any?).isNull()
        assertThat(req.source).isInstanceOf(InputSource.None::class.java)
    }

    @Test
    fun `requires type and idempotencyKey`() {
        assertThatThrownBy { CreateJobRequest.builder().idempotencyKey("k").build() }
            .hasMessageContaining("type")
        assertThatThrownBy { CreateJobRequest.builder().type("t").build() }
            .hasMessageContaining("idempotencyKey")
    }

    @Test
    fun `Class-based builder captures Class source and binds value`() {
        val req =
            CreateJobRequest.builder(String::class.java)
                .type("t")
                .idempotencyKey("k")
                .input("hello")
                .build()
        assertThat(req.input).isEqualTo("hello")
        val source = req.source
        assertThat(source).isInstanceOf(InputSource.TypedClass::class.java)
        assertThat((source as InputSource.TypedClass<*>).type).isEqualTo(String::class.java)
    }

    @Test
    fun `TypeToken-based builder captures TypeToken source`() {
        val token = object : TypeToken<List<String>>() {}
        val req =
            CreateJobRequest.builder(token)
                .type("t")
                .idempotencyKey("k")
                .input(listOf("a", "b"))
                .build()
        assertThat(req.input).isEqualTo(listOf("a", "b"))
        assertThat(req.source).isInstanceOf(InputSource.TypedToken::class.java)
    }

    @Test
    fun `Codec-based builder captures custom codec`() {
        val req =
            CreateJobRequest.builder(Codecs.UTF8)
                .type("t")
                .idempotencyKey("k")
                .input("hello")
                .build()
        assertThat(req.input).isEqualTo("hello")
        val source = req.source
        assertThat(source).isInstanceOf(InputSource.Coded::class.java)
        assertThat((source as InputSource.Coded<*>).codec).isSameAs(Codecs.UTF8)
    }

    @Test
    fun `typed builder allows omitting input value`() {
        // Mirrors the wire protocol: inputData is nullable, so a typed source without an input
        // value produces a request that emits no inputData on the wire.
        val req = CreateJobRequest.builder(String::class.java).type("t").idempotencyKey("k").build()
        assertThat(req.input as Any?).isNull()
        assertThat(req.source).isInstanceOf(InputSource.TypedClass::class.java)
    }

    @Test
    fun `inputData extension only applies to no-input builder and switches source to Bytes`() {
        val req =
            CreateJobRequest.builder()
                .type("t")
                .idempotencyKey("k")
                .inputData(byteArrayOf(1, 2, 3))
                .build()
        val source = req.source
        assertThat(source).isInstanceOf(InputSource.Bytes::class.java)
        assertThat((source as InputSource.Bytes).data).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `toBuilder round-trips Class-based request`() {
        val original =
            CreateJobRequest.builder(String::class.java)
                .type("t")
                .idempotencyKey("k")
                .name("n")
                .maxRetries(3)
                .input("hello")
                .build()
        val rebuilt = original.toBuilder().build()
        assertThat(rebuilt.type).isEqualTo(original.type)
        assertThat(rebuilt.idempotencyKey).isEqualTo(original.idempotencyKey)
        assertThat(rebuilt.name).isEqualTo(original.name)
        assertThat(rebuilt.maxRetries).isEqualTo(original.maxRetries)
        assertThat(rebuilt.input).isEqualTo(original.input)
        assertThat(rebuilt.source).isInstanceOf(InputSource.TypedClass::class.java)
    }

    @Test
    fun `toBuilder round-trips no-input request with raw bytes`() {
        val original =
            CreateJobRequest.builder()
                .type("t")
                .idempotencyKey("k")
                .inputData(byteArrayOf(9, 9))
                .build()
        val rebuilt = original.toBuilder().build()
        val source = rebuilt.source
        assertThat(source).isInstanceOf(InputSource.Bytes::class.java)
        assertThat((source as InputSource.Bytes).data).isEqualTo(byteArrayOf(9, 9))
    }

    @Test
    fun `toBuilder round-trips Codec-based request including the codec instance`() {
        val original =
            CreateJobRequest.builder(Codecs.UTF8)
                .type("t")
                .idempotencyKey("k")
                .input("hello")
                .build()
        val rebuilt = original.toBuilder().build()
        assertThat(rebuilt.input).isEqualTo("hello")
        val source = rebuilt.source
        assertThat(source).isInstanceOf(InputSource.Coded::class.java)
        // Codec instance must round-trip — re-wrapping it in a new builder mustn't drop it.
        assertThat((source as InputSource.Coded<*>).codec).isSameAs(Codecs.UTF8)
    }

    @Test
    fun `toBuilder round-trips TypeToken-based request`() {
        val token = object : TypeToken<List<String>>() {}
        val original =
            CreateJobRequest.builder(token)
                .type("t")
                .idempotencyKey("k")
                .input(listOf("a", "b"))
                .build()
        val rebuilt = original.toBuilder().build()
        assertThat(rebuilt.input).isEqualTo(listOf("a", "b"))
        val source = rebuilt.source
        assertThat(source).isInstanceOf(InputSource.TypedToken::class.java)
        assertThat((source as InputSource.TypedToken<*>).type).isSameAs(token)
    }

    @Test
    fun `toBuilder preserves all optional fields`() {
        val original =
            CreateJobRequest.builder(String::class.java)
                .type("t")
                .idempotencyKey("k")
                .name("the-name")
                .maxRetries(7)
                .input("v")
                .build()
        val rebuilt = original.toBuilder().build()
        assertThat(rebuilt.name).isEqualTo("the-name")
        assertThat(rebuilt.maxRetries).isEqualTo(7)
    }

    @Test
    fun `toBuilder allows overriding fields after copy`() {
        val original =
            CreateJobRequest.builder(String::class.java)
                .type("t-1")
                .idempotencyKey("k-1")
                .input("v-1")
                .build()
        val rebuilt = original.toBuilder().type("t-2").idempotencyKey("k-2").input("v-2").build()
        assertThat(rebuilt.type).isEqualTo("t-2")
        assertThat(rebuilt.idempotencyKey).isEqualTo("k-2")
        assertThat(rebuilt.input).isEqualTo("v-2")
    }

    @Test
    fun `inputData extension last wins on Builder of Nothing`() {
        val req =
            CreateJobRequest.builder()
                .type("t")
                .idempotencyKey("k")
                .inputData(byteArrayOf(1))
                .inputData(byteArrayOf(2, 3))
                .build()
        assertThat((req.source as InputSource.Bytes).data).isEqualTo(byteArrayOf(2, 3))
    }

    @Test
    fun `typed builder allows null input value with TypeToken source`() {
        val token = object : TypeToken<List<String>>() {}
        val req = CreateJobRequest.builder(token).type("t").idempotencyKey("k").build()
        assertThat(req.input as Any?).isNull()
        assertThat(req.source).isInstanceOf(InputSource.TypedToken::class.java)
    }

    @Test
    fun `typed builder allows null input value with PayloadCodec source`() {
        val req = CreateJobRequest.builder(Codecs.UTF8).type("t").idempotencyKey("k").build()
        assertThat(req.input as Any?).isNull()
        assertThat(req.source).isInstanceOf(InputSource.Coded::class.java)
    }
}
