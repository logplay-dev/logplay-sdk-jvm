package org.zeplinko.logplay.client.handler

import org.zeplinko.logplay.client.codec.PayloadCodec

/**
 * Pairs a job [type] with its codecs and a [HandlerFactory] for registration on a Worker.
 *
 * For stateless handlers, prefer the [JobHandler]-based secondary constructor — it wraps a single
 * shared instance in a trivial factory so the same instance is reused across invocations (no
 * per-invocation allocation). For DI-scoped or per-invocation-stateful handlers, use the
 * [HandlerFactory] form directly.
 */
public class HandlerRegistration<I, O>(
    public val type: String,
    public val inputCodec: PayloadCodec<I>,
    public val outputCodec: PayloadCodec<O>,
    public val factory: HandlerFactory<I, O>,
) {
    init {
        require(type.isNotBlank()) { "type must not be blank" }
    }

    /** Convenience constructor: wraps a single shared [handler] in a singleton factory. */
    public constructor(
        type: String,
        inputCodec: PayloadCodec<I>,
        outputCodec: PayloadCodec<O>,
        handler: JobHandler<I, O>,
    ) : this(type, inputCodec, outputCodec, HandlerFactory { handler })
}
