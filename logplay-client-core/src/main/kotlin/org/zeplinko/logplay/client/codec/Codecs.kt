package org.zeplinko.logplay.client.codec

/** Built-in [PayloadCodec] helpers. */
public object Codecs {
    /** Identity codec for raw [ByteArray] payloads. Null passes through unchanged. */
    @JvmField public val BYTES: PayloadCodec<ByteArray> = ByteArrayCodec

    /** UTF-8 [String] codec. Null passes through unchanged. */
    @JvmField public val UTF8: PayloadCodec<String> = Utf8StringCodec

    /**
     * Codec representing the absence of a meaningful payload. `Unit` encodes to empty bytes; null
     * encodes to null bytes. Decode is asymmetric on purpose — both null and any byte payload
     * decode to [Unit], because [Unit] has exactly one value, so handlers typed `JobHandler<Unit,
     * *>` can declare a non-null `Unit` input and still accept jobs created without `inputData`.
     */
    @JvmField public val UNIT: PayloadCodec<Unit> = UnitCodec

    private object ByteArrayCodec : PayloadCodec<ByteArray> {
        override fun encode(value: ByteArray?): ByteArray? = value

        override fun decode(bytes: ByteArray?): ByteArray? = bytes
    }

    private object Utf8StringCodec : PayloadCodec<String> {
        override fun encode(value: String?): ByteArray? = value?.toByteArray(Charsets.UTF_8)

        override fun decode(bytes: ByteArray?): String? = bytes?.let { String(it, Charsets.UTF_8) }
    }

    private object UnitCodec : PayloadCodec<Unit> {
        private val EMPTY = ByteArray(0)

        override fun encode(value: Unit?): ByteArray? = value?.let { EMPTY }

        override fun decode(bytes: ByteArray?): Unit = Unit
    }
}
