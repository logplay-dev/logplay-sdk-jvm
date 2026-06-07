package org.zeplinko.logplay.client.retry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExponentialBackoffRetryPolicyTest {

    private val cause: Throwable = RuntimeException("boom")

    @Test
    fun `returns negative when attempt reaches max`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 3,
                baseDelayMs = 10,
                maxDelayMs = 100,
                jitter = false,
            )
        assertThat(policy.nextDelayMs(3, cause)).isLessThan(0)
    }

    @Test
    fun `delays grow exponentially when jitter disabled`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 5,
                baseDelayMs = 10,
                maxDelayMs = 1000,
                jitter = false,
            )
        assertThat(policy.nextDelayMs(1, cause)).isEqualTo(10)
        assertThat(policy.nextDelayMs(2, cause)).isEqualTo(20)
        assertThat(policy.nextDelayMs(3, cause)).isEqualTo(40)
    }

    @Test
    fun `delays are capped by maxDelayMs`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 30,
                baseDelayMs = 100,
                maxDelayMs = 500,
                jitter = false,
            )
        assertThat(policy.nextDelayMs(20, cause)).isEqualTo(500)
    }

    @Test
    fun `with jitter returns value within zero and cap`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 5,
                baseDelayMs = 100,
                maxDelayMs = 100,
                jitter = true,
            )
        repeat(50) {
            val delay = policy.nextDelayMs(1, cause)
            assertThat(delay).isBetween(0L, 100L)
        }
    }

    @Test
    fun `attempt past max returns negative regardless of jitter`() {
        val withJitter =
            ExponentialBackoffRetryPolicy(maxAttempts = 2, baseDelayMs = 10, maxDelayMs = 100)
        val withoutJitter =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 2,
                baseDelayMs = 10,
                maxDelayMs = 100,
                jitter = false,
            )
        assertThat(withJitter.nextDelayMs(2, cause)).isLessThan(0)
        assertThat(withJitter.nextDelayMs(99, cause)).isLessThan(0)
        assertThat(withoutJitter.nextDelayMs(2, cause)).isLessThan(0)
    }

    @Test
    fun `large attempt number does not overflow`() {
        // Internal `baseDelayMs shl shift` would overflow Long for shift > 63 — the impl coerces
        // shift to [0, 30] to prevent that. Verify a huge attempt still returns the cap.
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 1_000,
                baseDelayMs = 1,
                maxDelayMs = 5_000,
                jitter = false,
            )
        assertThat(policy.nextDelayMs(500, cause)).isEqualTo(5_000)
    }

    @Test
    fun `baseDelayMs of zero produces zero delay`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 5,
                baseDelayMs = 0,
                maxDelayMs = 1_000,
                jitter = false,
            )
        // 0 << anything = 0 — no growth, capped only on the way down (already 0).
        assertThat(policy.nextDelayMs(1, cause)).isEqualTo(0)
        assertThat(policy.nextDelayMs(3, cause)).isEqualTo(0)
    }

    @Test
    fun `jitter with zero cap returns zero (no random call)`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                maxAttempts = 5,
                baseDelayMs = 0,
                maxDelayMs = 0,
                jitter = true,
            )
        assertThat(policy.nextDelayMs(1, cause)).isEqualTo(0)
    }

    @Test
    fun `invalid construction args are rejected`() {
        assertThatThrownBy { ExponentialBackoffRetryPolicy(maxAttempts = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxAttempts")

        assertThatThrownBy { ExponentialBackoffRetryPolicy(baseDelayMs = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("baseDelayMs")

        assertThatThrownBy { ExponentialBackoffRetryPolicy(baseDelayMs = 100, maxDelayMs = 10) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxDelayMs")
    }
}
