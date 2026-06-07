package org.zeplinko.logplay.client.codec

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodecsTest {

    @Test
    fun `BYTES is identity codec`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertThat(Codecs.BYTES.encode(data)).isEqualTo(data)
        assertThat(Codecs.BYTES.decode(data)).isEqualTo(data)
    }

    @Test
    fun `BYTES round-trips empty bytes as empty bytes (distinct from null)`() {
        val empty = ByteArray(0)
        val encoded = Codecs.BYTES.encode(empty)
        assertThat(encoded).isNotNull
        assertThat(encoded).isEmpty()
        assertThat(Codecs.BYTES.decode(encoded)).isNotNull
        assertThat(Codecs.BYTES.decode(encoded)).isEmpty()
    }

    @Test
    fun `BYTES encode returns the same instance (true identity)`() {
        val data = byteArrayOf(7)
        // The contract says "identity" — assert it's not just equal but the same reference.
        assertThat(Codecs.BYTES.encode(data)).isSameAs(data)
        assertThat(Codecs.BYTES.decode(data)).isSameAs(data)
    }

    @Test
    fun `BYTES passes null through unchanged`() {
        assertThat(Codecs.BYTES.encode(null)).isNull()
        assertThat(Codecs.BYTES.decode(null)).isNull()
    }

    @Test
    fun `UTF8 round trips`() {
        val original = "Hello, café 🚀"
        val encoded = Codecs.UTF8.encode(original)
        val decoded = Codecs.UTF8.decode(encoded)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `UTF8 round-trips empty string as zero-byte payload`() {
        val encoded = Codecs.UTF8.encode("")
        assertThat(encoded).isNotNull
        assertThat(encoded).isEmpty()
        assertThat(Codecs.UTF8.decode(encoded)).isEqualTo("")
    }

    @Test
    fun `UTF8 passes null through unchanged`() {
        assertThat(Codecs.UTF8.encode(null)).isNull()
        assertThat(Codecs.UTF8.decode(null)).isNull()
    }

    @Test
    fun `UNIT encodes Unit to empty bytes and null to null, decodes everything to Unit`() {
        assertThat(Codecs.UNIT.encode(Unit)).isEmpty()
        assertThat(Codecs.UNIT.encode(null)).isNull()
        // Decode is asymmetric on purpose — both null and any byte payload decode to Unit
        // (Unit is a singleton, so the codec collapses the null wire case into "the Unit value").
        // This lets JobHandler<Unit, *> handlers declare a non-null Unit input and still accept
        // jobs created without inputData.
        assertThat(Codecs.UNIT.decode(byteArrayOf())).isEqualTo(Unit)
        assertThat(Codecs.UNIT.decode(null)).isEqualTo(Unit)
    }

    @Test
    fun `UNIT decode of non-empty bytes still returns Unit (Unit ignores payload content)`() {
        // The codec doesn't validate the bytes — Unit is a singleton, decode just signals
        // "there was a payload here." This pins down current behavior.
        assertThat(Codecs.UNIT.decode(byteArrayOf(1, 2, 3))).isEqualTo(Unit)
    }
}
