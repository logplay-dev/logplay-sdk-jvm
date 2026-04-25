package org.zeplinko.logplay.client.handler

import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.codec.Codecs

class HandlerRegistrationTest {

    @Test
    fun `factory registration stores supplied factory directly`() {
        val factoryCalls = AtomicInteger(0)
        val factory =
            HandlerFactory<String, String> {
                factoryCalls.incrementAndGet()
                JobHandler<String, String> { input, _ -> input }
            }
        val reg = HandlerRegistration("t", Codecs.UTF8, Codecs.UTF8, factory)
        assertThat(reg.factory).isSameAs(factory)

        // Each create() call delegates — proves no internal caching.
        reg.factory.create()
        reg.factory.create()
        assertThat(factoryCalls.get()).isEqualTo(2)
    }

    @Test
    fun `JobHandler convenience constructor wraps in singleton-returning factory`() {
        val handler = JobHandler<String, String> { input, _ -> "echo:$input" }
        val reg = HandlerRegistration("t", Codecs.UTF8, Codecs.UTF8, handler)

        // Multiple create() calls return the SAME instance — no new allocation.
        val first = reg.factory.create()
        val second = reg.factory.create()
        assertThat(first).isSameAs(handler)
        assertThat(second).isSameAs(handler)
    }

    @Test
    fun `blank type is rejected`() {
        val handler = JobHandler<String, String> { input, _ -> input }
        assertThatThrownBy { HandlerRegistration("", Codecs.UTF8, Codecs.UTF8, handler) }
            .hasMessageContaining("type")
        assertThatThrownBy { HandlerRegistration("   ", Codecs.UTF8, Codecs.UTF8, handler) }
            .hasMessageContaining("type")
    }

    @Test
    fun `factory can return a fresh handler instance per create call`() {
        // Proves the factory contract: framework calls create() once per job execution. A factory
        // that materializes a new instance gets fresh state every time.
        val factory =
            HandlerFactory<String, String> {
                // Build via concrete object so SAM-conversion can't fold to a singleton.
                object : JobHandler<String, String> {
                    override fun execute(input: String, ctx: JobContext): String = input
                }
            }
        val reg = HandlerRegistration("t", Codecs.UTF8, Codecs.UTF8, factory)
        val first = reg.factory.create()
        val second = reg.factory.create()
        assertThat(first).isNotSameAs(second)
    }

    @Test
    fun `registration retains type and codecs verbatim`() {
        val handler = JobHandler<String, ByteArray> { input, _ -> input.toByteArray() }
        val reg = HandlerRegistration("my-type", Codecs.UTF8, Codecs.BYTES, handler)
        assertThat(reg.type).isEqualTo("my-type")
        assertThat(reg.inputCodec).isSameAs(Codecs.UTF8)
        assertThat(reg.outputCodec).isSameAs(Codecs.BYTES)
    }
}
