package org.zeplinko.logplay.client.runtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WakeAtCodecTest {
    @Test
    fun `round-trips arbitrary Long values via 8 bytes`() {
        listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1_700_000_000_000L).forEach { v ->
            val bytes = WakeAtCodec.encode(v)
            assertThat(bytes).isNotNull
            assertThat(bytes!!.size).isEqualTo(8)
            assertThat(WakeAtCodec.decode(bytes)).isEqualTo(v)
        }
    }

    @Test
    fun `null encodes to null and null decodes to null`() {
        assertThat(WakeAtCodec.encode(null)).isNull()
        assertThat(WakeAtCodec.decode(null)).isNull()
    }

    @Test
    fun `wrong-size payload is rejected`() {
        assertThatThrownBy { WakeAtCodec.decode(ByteArray(7)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
