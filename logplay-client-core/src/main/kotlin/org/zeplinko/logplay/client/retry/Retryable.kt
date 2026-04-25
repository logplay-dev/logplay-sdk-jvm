package org.zeplinko.logplay.client.retry

/**
 * Marker interface — only exceptions implementing this are eligible for retry by the SDK's internal
 * `RetryExecutor`. The built-in exception hierarchy treats `LogPlayTransportException` (network/IO
 * failures) and `JobConcurrentModificationException` (optimistic-lock conflicts) as retryable;
 * everything else (validation, not-found, forbidden, most conflicts) reflects deterministic bad
 * state and is NOT retried. Custom exceptions can opt in by implementing this marker.
 */
public interface Retryable
