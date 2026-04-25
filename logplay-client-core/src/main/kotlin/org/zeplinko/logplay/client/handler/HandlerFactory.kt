package org.zeplinko.logplay.client.handler

/**
 * Produces a [JobHandler] instance for executing a single job invocation.
 *
 * Use this when handlers should not be shared across job executions — e.g., when integrating with a
 * DI container that materializes prototype-scoped beans, or when handlers carry per-job mutable
 * state in fields.
 *
 * [create] is invoked once per job execution. The returned handler is used to execute exactly one
 * job and then discarded (held only by the executing thread for the duration of
 * [JobHandler.execute]).
 *
 * For stateless handlers, prefer registering a [JobHandler] directly — it is reused across all jobs
 * of its type and avoids per-invocation allocation. DI-scoped example (Java):
 * ```java
 * worker.registerHandler("processOrder", Order.class, Receipt.class,
 *     () -> applicationContext.getBean(MyOrderHandler.class));
 * ```
 *
 * Declared as a [`fun interface`][fun] so Java callers can SAM-convert lambda literals into
 * `HandlerFactory` exactly as they could against the prior Java interface.
 *
 * @param I input payload type
 * @param O output payload type
 */
public fun interface HandlerFactory<I, O> {
    public fun create(): JobHandler<I, O>
}
