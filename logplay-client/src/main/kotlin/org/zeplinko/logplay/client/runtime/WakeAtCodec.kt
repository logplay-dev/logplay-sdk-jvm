package org.zeplinko.logplay.client.runtime

import java.nio.ByteBuffer
import org.zeplinko.logplay.client.codec.PayloadCodec

/**
 * 8-byte big-endian Long codec for sleep checkpoints. Nullable in/out: a null wake-at means "no
 * recorded deadline" (which the sleep replay logic treats as an internal invariant violation —
 * sleep checkpoints always have a non-null wake-at).
 */
internal object WakeAtCodec : PayloadCodec<Long> {
    override fun encode(value: Long?): ByteArray? =
        value?.let { ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it).array() }

    override fun decode(bytes: ByteArray?): Long? {
        if (bytes == null) return null
        require(bytes.size == Long.SIZE_BYTES) {
            "WakeAtCodec expected ${Long.SIZE_BYTES} bytes, got ${bytes.size}"
        }
        return ByteBuffer.wrap(bytes).long
    }
}
