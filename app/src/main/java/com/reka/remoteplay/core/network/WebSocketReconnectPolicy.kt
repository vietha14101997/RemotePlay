package com.reka.remoteplay.core.network

import kotlin.random.Random

/**
 * Exponential-backoff-with-jitter policy used by [WebSocketClient] to schedule reconnect
 * attempts after an unplanned WebSocket drop (network blip, relay/host restart, etc.).
 *
 * Deliberately kept dependency-free (no coroutines, no OkHttp) so the delay math and attempt
 * bookkeeping can be unit tested without a live socket. [WebSocketClient] owns the actual
 * scheduling (coroutine + delay) and the decision of *whether* to reconnect at all (e.g. a
 * user-initiated disconnect never consults this policy).
 *
 * Delay sequence (jitter excluded): 1s, 2s, 4s, 8s, 16s, 30s, 30s, ... (capped at [maxDelayMs]).
 */
class WebSocketReconnectPolicy(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val jitterMs: Long = DEFAULT_JITTER_MS,
    private val random: Random = Random.Default
) {
    /** 1-based count of reconnect attempts made since the last successful open (or [reset]). */
    var attempt: Int = 0
        private set

    /**
     * Advances to the next attempt and returns its backoff delay in milliseconds
     * (exponential, capped at [maxDelayMs], plus a random jitter in `[0, jitterMs)`).
     */
    fun nextDelayMs(): Long {
        attempt += 1
        // Exponent is clamped so `baseDelayMs shl exponent` can never overflow Long even after
        // thousands of attempts on a long-lived connection that keeps failing to reconnect.
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        val capped = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
        val jitter = if (jitterMs > 0) random.nextLong(jitterMs) else 0L
        return capped + jitter
    }

    /** Call after a successful open so the next drop restarts the sequence from attempt 1. */
    fun reset() {
        attempt = 0
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        const val DEFAULT_JITTER_MS = 500L
        private const val MAX_EXPONENT = 20
    }
}
