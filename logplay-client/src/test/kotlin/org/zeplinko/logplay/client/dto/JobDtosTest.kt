package org.zeplinko.logplay.client.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobStatus
import org.zeplinko.logplay.client.model.inputData

class JobDtosTest {
    private val mapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `CreateJobRequest serializes raw inputData as Base64 and omits nulls`() {
        val req =
            CreateJobRequest.builder()
                .type("t")
                .idempotencyKey("k")
                .inputData(byteArrayOf(0x01, 0x02, 0x03))
                .build()
        val json = mapper.writeValueAsString(req.toDto("g", mapper))
        assertThat(json).contains("\"groupId\":\"g\"")
        assertThat(json).contains("\"inputData\":\"AQID\"")
        assertThat(json).doesNotContain("\"name\"")
        assertThat(json).doesNotContain("\"maxRetries\"")
    }

    @Test
    fun `CreateJobRequest with Class source JSON-encodes the input via mapper`() {
        val req =
            CreateJobRequest.builder(String::class.java)
                .type("t")
                .idempotencyKey("k")
                .input("hi")
                .build()
        val json = mapper.writeValueAsString(req.toDto("g", mapper))
        // "hi" JSON-encoded is the bytes for the literal string `"hi"` (5 bytes including quotes),
        // base64 = `Imhp` — assert the field is present and non-null.
        assertThat(json).contains("\"inputData\":")
        assertThat(json).doesNotContain("\"inputData\":null")
    }

    @Test
    fun `CreateJobRequest with no input omits inputData`() {
        val req = CreateJobRequest.builder().type("t").idempotencyKey("k").build()
        val json = mapper.writeValueAsString(req.toDto("g", mapper))
        assertThat(json).doesNotContain("\"inputData\"")
    }

    @Test
    fun `Class source with no input value omits inputData on wire`() {
        // Typed builder + no .input(...) -> nullable wire input.
        val req = CreateJobRequest.builder(String::class.java).type("t").idempotencyKey("k").build()
        val json = mapper.writeValueAsString(req.toDto("g", mapper))
        assertThat(json).doesNotContain("\"inputData\"")
    }

    @Test
    fun `Class source JSON-encodes input value bytes correctly on the wire`() {
        // Pin down the exact wire form, not just presence.
        val req =
            CreateJobRequest.builder(String::class.java)
                .type("t")
                .idempotencyKey("k")
                .input("hi")
                .build()
        val dto = req.toDto("g", mapper)
        // Jackson encodes "hi" as the JSON literal `"hi"` (4 bytes including quotes).
        val expectedBytes = "\"hi\"".toByteArray()
        val expectedB64 = java.util.Base64.getEncoder().encodeToString(expectedBytes)
        assertThat(dto.inputData).isEqualTo(expectedB64)
    }

    @Test
    fun `Codec source uses the supplied codec for encoding`() {
        val req =
            CreateJobRequest.builder(org.zeplinko.logplay.client.codec.Codecs.UTF8)
                .type("t")
                .idempotencyKey("k")
                .input("hi")
                .build()
        val dto = req.toDto("g", mapper)
        // Codecs.UTF8 produces raw UTF-8 bytes (no JSON quoting).
        val expectedB64 = java.util.Base64.getEncoder().encodeToString("hi".toByteArray())
        assertThat(dto.inputData).isEqualTo(expectedB64)
    }

    @Test
    fun `Bytes source bypasses codec and sends raw bytes verbatim`() {
        val raw = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val req = CreateJobRequest.builder().type("t").idempotencyKey("k").inputData(raw).build()
        val dto = req.toDto("g", mapper)
        val expectedB64 = java.util.Base64.getEncoder().encodeToString(raw)
        assertThat(dto.inputData).isEqualTo(expectedB64)
    }

    @Test
    fun `optional name and maxRetries are propagated to wire DTO`() {
        val req =
            CreateJobRequest.builder()
                .type("t")
                .idempotencyKey("k")
                .name("the-name")
                .maxRetries(3)
                .build()
        val dto = req.toDto("g", mapper)
        assertThat(dto.name).isEqualTo("the-name")
        assertThat(dto.maxRetries).isEqualTo(3)
    }

    @Test
    fun `JobResponseDto with null input and output round-trips`() {
        val json =
            """
            {
              "id": "job-2",
              "groupId": "g",
              "name": "n",
              "type": "t",
              "status": "PENDING",
              "retries": 0,
              "maxRetries": null,
              "createdAt": "2026-04-25T10:15:30Z",
              "updatedAt": "2026-04-25T10:15:30Z",
              "lastAcquiredAt": null,
              "acquiredByWorkerId": null,
              "inputData": null,
              "outputData": null,
              "version": 1
            }
            """
                .trimIndent()
        val dto = mapper.readValue(json, JobResponseDto::class.java)
        val domain = dto.toDomain()
        assertThat(domain.inputData).isNull()
        assertThat(domain.outputData).isNull()
        assertThat(domain.lastAcquiredAt).isNull()
        assertThat(domain.acquiredByWorkerId).isNull()
        assertThat(domain.maxRetries).isNull()
    }

    @Test
    fun `JobResponseDto deserializes Instant + Base64 + enum`() {
        val payloadBytes = byteArrayOf(0x10, 0x20, 0x30)
        val payloadB64 = Base64.getEncoder().encodeToString(payloadBytes)
        val json =
            """
            {
              "id": "job-1",
              "groupId": "g",
              "name": "n",
              "type": "t",
              "status": "ACQUIRED",
              "retries": 1,
              "maxRetries": 5,
              "createdAt": "2026-04-25T10:15:30Z",
              "updatedAt": "2026-04-25T10:16:00Z",
              "lastAcquiredAt": "2026-04-25T10:16:00Z",
              "acquiredByWorkerId": "w-1",
              "inputData": "$payloadB64",
              "outputData": null,
              "version": 7
            }
            """
                .trimIndent()
        val dto = mapper.readValue(json, JobResponseDto::class.java)
        val domain = dto.toDomain()
        assertThat(domain.status).isEqualTo(JobStatus.ACQUIRED)
        assertThat(domain.inputData).isEqualTo(payloadBytes)
        assertThat(domain.outputData).isNull()
        assertThat(domain.version).isEqualTo(7L)
        assertThat(domain.acquiredByWorkerId).isEqualTo("w-1")
    }
}
