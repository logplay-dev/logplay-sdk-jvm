package org.zeplinko.logplay.client.model

import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken

/**
 * Immutable request to create a new job, parameterized by the input value type [I].
 *
 * `groupId` is intentionally not part of this type — it is taken from the `LogPlayManager` at
 * submit time. For the rare cross-group case, use the `groupIdOverride` overload on
 * `LogPlayManager.createJob`.
 *
 * Build via one of the four [CreateJobRequest.builder] factories. The factory commits the encoding
 * source up front; subsequent setters then bind the input value via [Builder.input]:
 *
 * |Factory                   |Builder type      |Use case                                                     |
 * |--------------------------|------------------|-------------------------------------------------------------|
 * |`builder(Class<I>)`       |`Builder<I>`      |JSON input via the manager's mapper                          |
 * |`builder(TypeToken<I>)`   |`Builder<I>`      |JSON input with parameterized generics                       |
 * |`builder(PayloadCodec<I>)`|`Builder<I>`      |Custom codec (Protobuf, msgpack, etc.)                       |
 * |`builder()`               |`Builder<Nothing>`|No input — or pre-encoded bytes via the [inputData] extension|
 *
 * `input` may be null even on a typed source; the wire-level payload is nullable, and the codec is
 * given the chance to encode null however it sees fit (the built-in codecs simply pass null
 * through, producing wire `inputData = null`).
 */
public class CreateJobRequest<I>
internal constructor(
    public val type: String,
    public val idempotencyKey: String,
    public val name: String?,
    public val maxRetries: Int?,
    public val input: I?,
    public val source: InputSource<I>,
) {
    public fun toBuilder(): Builder<I> {
        val b = Builder<I>(source)
        b.type(type).idempotencyKey(idempotencyKey).name(name).maxRetries(maxRetries)
        if (input != null) b.input(input)
        return b
    }

    /**
     * Builder for [CreateJobRequest]. Constructed via one of the four [CreateJobRequest.builder]
     * factories — never directly. The encoding strategy ([InputSource]) is bound at construction
     * and immutable thereafter; setters only fill in the value and unrelated fields.
     */
    public class Builder<I> internal constructor(internal var source: InputSource<I>) {
        private var type: String? = null
        private var idempotencyKey: String? = null
        private var name: String? = null
        private var maxRetries: Int? = null
        private var input: I? = null

        /** **Required.** Job type — used to route this job to a registered handler. */
        public fun type(value: String): Builder<I> = apply { this.type = value }

        /**
         * **Required.** Idempotency key — must be unique within the manager's `groupId`.
         * Re-submitting the same `(groupId, idempotencyKey)` pair throws
         * `DuplicateIdempotencyKeyException`.
         */
        public fun idempotencyKey(value: String): Builder<I> = apply { this.idempotencyKey = value }

        /** Optional human-readable name for logs/UI. Server auto-generates one if omitted. */
        public fun name(value: String?): Builder<I> = apply { this.name = value }

        /** Optional retry budget; if omitted, the server's default applies. */
        public fun maxRetries(value: Int?): Builder<I> = apply { this.maxRetries = value }

        /**
         * Set the input value. Encoding is performed at submit time using the source committed by
         * the factory. If never called, the request submits with no `inputData` on the wire.
         */
        public fun input(value: I): Builder<I> = apply { this.input = value }

        public fun build(): CreateJobRequest<I> {
            val type = requireNotNull(type) { "type is required" }
            val idempotencyKey = requireNotNull(idempotencyKey) { "idempotencyKey is required" }
            // input may be null for any source — typed sources that omit .input(value) result in
            // a request with no inputData on the wire (mirrors the protocol's nullable shape).
            return CreateJobRequest(type, idempotencyKey, name, maxRetries, input, source)
        }
    }

    public companion object {
        /**
         * Build a request whose input will be JSON-encoded by the manager using the supplied
         * runtime [type] token.
         */
        @JvmStatic
        public fun <I> builder(type: Class<I>): Builder<I> = Builder(InputSource.TypedClass(type))

        /**
         * Build a request whose input will be JSON-encoded by the manager using the supplied
         * generic [type] token.
         */
        @JvmStatic
        public fun <I> builder(type: TypeToken<I>): Builder<I> =
            Builder(InputSource.TypedToken(type))

        /** Build a request whose input will be encoded with the supplied custom [codec]. */
        @JvmStatic
        public fun <I> builder(codec: PayloadCodec<I>): Builder<I> =
            Builder(InputSource.Coded(codec))

        /**
         * Build a request with no input payload. Use [Builder.inputData] on the returned builder if
         * pre-encoded raw bytes need to be supplied.
         */
        @JvmStatic public fun builder(): Builder<Nothing> = Builder(InputSource.None)
    }
}

/**
 * Discriminator for the chosen encoding strategy. The class is sealed and its constructors are
 * internal so callers cannot introduce new variants — instances are produced exclusively by the
 * [CreateJobRequest.builder] factories.
 */
public sealed class InputSource<out I> {
    public object None : InputSource<Nothing>()

    public class Bytes internal constructor(public val data: ByteArray) : InputSource<Nothing>()

    public class TypedClass<I> internal constructor(public val type: Class<I>) : InputSource<I>()

    public class TypedToken<I> internal constructor(public val type: TypeToken<I>) :
        InputSource<I>()

    public class Coded<I> internal constructor(public val codec: PayloadCodec<I>) : InputSource<I>()
}

/**
 * Pre-encoded bytes escape hatch — only available on builders constructed via the no-input
 * [CreateJobRequest.builder] factory. Constrains the raw-bytes path so it cannot accidentally be
 * mixed with a typed encoding source.
 */
public fun CreateJobRequest.Builder<Nothing>.inputData(
    bytes: ByteArray
): CreateJobRequest.Builder<Nothing> = apply { source = InputSource.Bytes(bytes) }
