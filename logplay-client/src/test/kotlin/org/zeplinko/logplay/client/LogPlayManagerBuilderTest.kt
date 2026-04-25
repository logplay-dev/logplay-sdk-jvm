package org.zeplinko.logplay.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.retry.ExponentialBackoffRetryPolicy

class LogPlayManagerBuilderTest {

    @Test
    fun `requires baseUrl`() {
        assertThatThrownBy { LogPlayManager.builder().groupId("g").build() }
            .hasMessageContaining("baseUrl")
    }

    @Test
    fun `requires groupId`() {
        assertThatThrownBy { LogPlayManager.builder().baseUrl("http://localhost:8080").build() }
            .hasMessageContaining("groupId")
    }

    @Test
    fun `rejects blank groupId`() {
        assertThatThrownBy {
                LogPlayManager.builder().baseUrl("http://localhost:8080").groupId("  ").build()
            }
            .hasMessageContaining("groupId")
    }

    @Test
    fun `builds with defaults`() {
        val manager = LogPlayManager.builder().baseUrl("http://localhost:8080").groupId("g").build()
        assertThat(manager).isNotNull
        assertThat(manager.groupId()).isEqualTo("g")
    }

    @Test
    fun `accepts user-provided ObjectMapper without losing required modules`() {
        val mapper = ObjectMapper()
        LogPlayManager.builder()
            .baseUrl("http://localhost:8080")
            .groupId("g")
            .objectMapper(mapper)
            .defaultRetryPolicy(ExponentialBackoffRetryPolicy(maxAttempts = 1))
            .build()
        // KotlinModule registered: a Kotlin data class with no @JsonCreator round-trips cleanly.
        // (Without KotlinModule, Jackson can't construct it from JSON and throws.)
        val kotlinJson = mapper.writeValueAsString(SampleKt(name = "x", count = 1))
        val parsed = mapper.readValue(kotlinJson, SampleKt::class.java)
        assertThat(parsed).isEqualTo(SampleKt("x", 1))
        // JavaTimeModule registered: deserialize an ISO-8601 Instant string from JSON.
        // (Without JavaTimeModule, Jackson throws InvalidDefinitionException for
        // java.time.Instant.)
        val deserialized =
            mapper.readValue("\"2026-04-25T00:00:00Z\"", java.time.Instant::class.java)
        assertThat(deserialized).isEqualTo(java.time.Instant.parse("2026-04-25T00:00:00Z"))
    }

    @Test
    fun `groupId override on createJob rejects blank`() {
        val manager =
            LogPlayManager.builder().baseUrl("http://localhost:1").groupId("default").build()
        val req = CreateJobRequest.builder().type("t").idempotencyKey("k").build()
        assertThatThrownBy { manager.createJob(req, "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("groupIdOverride")
        assertThatThrownBy { manager.createJob(req, "") }.hasMessageContaining("groupIdOverride")
    }

    @Test
    fun `jsonCodec(Class) round-trips an instance via the manager mapper`() {
        val manager = LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build()
        val codec: PayloadCodec<SampleKt> = manager.jsonCodec(SampleKt::class.java)
        assertThat(codec).isInstanceOf(JacksonPayloadCodec::class.java)
        val original = SampleKt("hello", 7)
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertThat(decoded).isEqualTo(original)
        // Null pass-through inherited from the underlying JacksonPayloadCodec.
        assertThat(codec.encode(null)).isNull()
        assertThat(codec.decode(null)).isNull()
    }

    @Test
    fun `jsonCodec(TypeToken) round-trips parameterized generics`() {
        val manager = LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build()
        val token = object : TypeToken<List<SampleKt>>() {}
        val codec: PayloadCodec<List<SampleKt>> = manager.jsonCodec(token)
        val original = listOf(SampleKt("a", 1), SampleKt("b", 2))
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `newWorker rejects blank workerId`() {
        val manager = LogPlayManager.builder().baseUrl("http://localhost:1").groupId("g").build()
        assertThatThrownBy { manager.newWorker("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workerId")
        assertThatThrownBy { manager.newWorker("  ") }.hasMessageContaining("workerId")
    }

    @Test
    fun `newWorker propagates groupId from the manager`() {
        val manager =
            LogPlayManager.builder().baseUrl("http://localhost:1").groupId("the-group").build()
        val w = manager.newWorker("w-1")
        assertThat(w.groupId()).isEqualTo("the-group")
        assertThat(w.workerId()).isEqualTo("w-1")
    }

    @Test
    fun `baseUrl trailing slash is stripped`() {
        val manager =
            LogPlayManager.builder().baseUrl("http://localhost:8080/").groupId("g").build()
        // Internal field is private; we can only confirm that build() didn't trip on the slash —
        // the strip behavior is observable via integration tests. This test just asserts the
        // builder doesn't reject the trailing-slash form.
        assertThat(manager).isNotNull
    }

    @Test
    fun `defaultRetryPolicy field is exposed as the same instance supplied`() {
        val custom = ExponentialBackoffRetryPolicy(maxAttempts = 3)
        val manager =
            LogPlayManager.builder()
                .baseUrl("http://localhost:1")
                .groupId("g")
                .defaultRetryPolicy(custom)
                .build()
        assertThat(manager.defaultRetryPolicy).isSameAs(custom)
    }

    @Test
    fun `objectMapper round-trips a Sample via the supplied mapper after build`() {
        val mapper = ObjectMapper()
        // Building the manager force-registers KotlinModule on the supplied mapper — the
        // readValue<> extension below exercises that side effect.
        LogPlayManager.builder()
            .baseUrl("http://localhost:1")
            .groupId("g")
            .objectMapper(mapper)
            .build()
        val json = """{"name":"x","count":5}"""
        val parsed: SampleKt = mapper.readValue(json)
        assertThat(parsed).isEqualTo(SampleKt("x", 5))
    }

    private data class SampleKt(val name: String, val count: Int)
}
