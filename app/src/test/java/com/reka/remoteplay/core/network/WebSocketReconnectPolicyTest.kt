package com.reka.remoteplay.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for [WebSocketReconnectPolicy]: the pure backoff-delay math extracted from
 * [WebSocketClient] so it can be verified without a live socket.
 */
class WebSocketReconnectPolicyTest {

    @Test
    fun `delay sequence follows 1-2-4-8-16-30s capped, jitter disabled`() {
        val policy = WebSocketReconnectPolicy(jitterMs = 0)

        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(16_000L, policy.nextDelayMs())
        assertEquals(30_000L, policy.nextDelayMs()) // 32s would be uncapped; capped to 30s
        assertEquals(30_000L, policy.nextDelayMs()) // stays capped on further attempts
    }

    @Test
    fun `delay stays capped at 30s even after hundreds of attempts (no overflow)`() {
        val policy = WebSocketReconnectPolicy(jitterMs = 0)
        var last = 0L
        repeat(500) { last = policy.nextDelayMs() }
        assertEquals(30_000L, last)
        assertTrue("attempt counter should keep growing", policy.attempt == 500)
    }

    @Test
    fun `attempt count increments by one per call and is 1-based`() {
        val policy = WebSocketReconnectPolicy(jitterMs = 0)
        assertEquals(0, policy.attempt)
        policy.nextDelayMs()
        assertEquals(1, policy.attempt)
        policy.nextDelayMs()
        assertEquals(2, policy.attempt)
    }

    @Test
    fun `reset restarts the sequence from attempt 1`() {
        val policy = WebSocketReconnectPolicy(jitterMs = 0)
        policy.nextDelayMs() // 1s
        policy.nextDelayMs() // 2s
        policy.nextDelayMs() // 4s
        assertEquals(3, policy.attempt)

        policy.reset()
        assertEquals(0, policy.attempt)

        // Next delay after reset should be back to the first-attempt value, not continuing
        // the exponential sequence from where it left off.
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(1, policy.attempt)
    }

    @Test
    fun `jitter adds a bounded random component on top of the base delay`() {
        val seed = 42L
        val policy = WebSocketReconnectPolicy(random = Random(seed))
        val expectedJitter = Random(seed).nextLong(WebSocketReconnectPolicy.DEFAULT_JITTER_MS)

        val delay = policy.nextDelayMs()

        assertEquals(1_000L + expectedJitter, delay)
        assertTrue(delay in 1_000L until (1_000L + WebSocketReconnectPolicy.DEFAULT_JITTER_MS))
    }

    @Test
    fun `jitter never pushes the capped delay below the base cap`() {
        val policy = WebSocketReconnectPolicy()
        repeat(50) {
            val delay = policy.nextDelayMs()
            assertTrue("delay $delay must be >= 30000 base once capped", delay >= 1_000L)
            assertTrue(
                "delay $delay must stay within cap + jitter bound",
                delay < WebSocketReconnectPolicy.DEFAULT_MAX_DELAY_MS + WebSocketReconnectPolicy.DEFAULT_JITTER_MS
            )
        }
    }
}
