package org.zeplinko.logplay.client.handler

/**
 * User-supplied handler that executes a job's business logic.
 *
 * Implementations may be invoked multiple times for the same job during replay. Side-effecting
 * steps must be wrapped in [JobContext.run] so their results are persisted as checkpoints and
 * replayed on resume.
 *
 * ### Input and output nullability
 *
 * Nullability lives on the type parameters. Declare `JobHandler<Foo, Bar>` when input and output
 * are always non-null for jobs of this type; declare `JobHandler<Foo?, Bar?>` (Kotlin) or hold the
 * `@Nullable` hint yourself (Java) when either may be null.
 * - The wire protocol's `inputData` is optional — a job created without `.input(...)` arrives at
 *   the handler with `null` input. A handler typed `JobHandler<Foo, Bar>` that receives such a job
 *   fails with a runtime null check at method entry; this is intentional, treat it as a programmer
 *   error.
 * - A `null` return value transmits no `outputData` field on the wire (rather than an encoded JSON
 *   `null`).
 *
 * Resources held by the handler must be cleaned up inside `execute(...)` via try-finally — the
 * framework does not invoke any disposal method on returned handlers.
 *
 * Declared as a [`fun interface`][fun] so Java callers can SAM-convert lambda literals into
 * `JobHandler` exactly as they could against the prior Java interface.
 *
 * @param I input payload type, decoded from `job.inputData` via the input codec.
 * @param O output payload type, encoded to `job.outputData` via the output codec.
 */
public fun interface JobHandler<I, O> {
    @Throws(Exception::class) public fun execute(input: I, ctx: JobContext): O
}
