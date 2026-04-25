package org.zeplinko.logplay.client.codec

/**
 * Serializes and deserializes a value of type [T] to and from raw bytes for transport over the
 * LogPlay wire protocol. Implementations must be thread-safe.
 *
 * Both [encode] and [decode] are nullable on both sides — the wire-level payload fields
 * (`inputData`, `outputData`, checkpoint `data`) are all nullable, so a codec must explicitly
 * handle null in either direction. The conventional implementation is `value?.let { ... }` for
 * encode and `bytes?.let { ... }` for decode; null in → null out, no special-casing needed.
 */
public interface PayloadCodec<T> {
    public fun encode(value: T?): ByteArray?

    public fun decode(bytes: ByteArray?): T?
}
