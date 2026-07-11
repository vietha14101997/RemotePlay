package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Orchestrates WHEN to attempt an ICE restart: adaptive debounce (F14) on the first signal of
 * an incident, exponential backoff between retries, and a hard attempt cap that falls back to a
 * full `restart_phase2` renegotiation. Knows nothing about PeerConnection/WebRTC itself —
 * [WebRtcManager] feeds it health signals and supplies the actual restart / fallback side
 * effects via constructor callbacks, which keeps this class dependency-free (besides
 * coroutines) and fully unit-testable without a live PeerConnection — mirrors
 * [com.reka.remoteplay.core.network.WebSocketReconnectPolicy].
 *
 * Single-flight by design: only ONE scheduled wait or in-flight attempt exists at a time; a new
 * trigger while one is already pending/in-flight is ignored (the pending one wins, matching the
 * phase's "cancel if it resolves quickly" requirement without a separate debounce layer).
 *
 * Thread-safety (M1): the entry points are called from BOTH the WebRTC signaling thread
 * (via [WebRtcManager]'s iceConnectionState handler / NetworkCallback) AND [scope] coroutines
 * (the scheduled wait + watchdog). All mutable state ([restartInFlight], [hasAttemptedOnce],
 * [scheduledJob], [watchdogJob], [policy]) is therefore mutated only inside `synchronized(lock)`
 * so the check-then-act scheduling decision is atomic; the suspending/side-effecting callbacks
 * ([performRestart], [fallbackToPhase2]) run OUTSIDE the lock.
 */
class IceRestartCoordinator(
    private val scope: CoroutineScope,
    private val policy: IceRestartPolicy = IceRestartPolicy(),
    /** True if ICE has already recovered — checked right before firing so an attempt that
     *  self-resolved during the wait is skipped instead of restarting needlessly. */
    private val isHealthyNow: () -> Boolean,
    /** Performs the actual `restartIce()` + `createOffer()` round trip. */
    private val performRestart: suspend (IceRestartTrigger) -> Unit,
    /** Attempt cap reached, or the gate upstream already decided ICE restart isn't usable —
     *  fall back to a full `restart_phase2`. */
    private val fallbackToPhase2: () -> Unit,
    private val watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS
) {
    private val lock = Any()

    /** True from the moment [performRestart] is invoked until ICE reaches CONNECTED/COMPLETED,
     *  the attempt is known to have failed, or the watchdog times out. */
    @Volatile
    var restartInFlight: Boolean = false
        private set

    private var hasAttemptedOnce = false
    private var scheduledJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * A signal that ICE/network looks unhealthy — schedules a restart attempt after the
     * appropriate debounce/backoff wait. Ignored (single-flight) while an attempt is already
     * pending OR in flight; that pending attempt's outcome is what re-consults this next.
     */
    fun onTrigger(trigger: IceRestartTrigger) {
        var giveUp = false
        synchronized(lock) {
            if (restartInFlight || scheduledJob?.isActive == true) {
                Log.d(TAG, "onTrigger($trigger): a restart is already pending/in-flight, ignoring")
                return
            }
            val waitMs = nextWaitMsOrNull(trigger)
            if (waitMs == null) {
                // Retry budget exhausted — reset for a future incident and fall back below.
                hasAttemptedOnce = false
                policy.reset()
                giveUp = true
            } else {
                scheduledJob = scope.launch {
                    delay(waitMs)
                    maybeFireRestart(trigger)
                }
            }
        }
        if (giveUp) {
            Log.w(TAG, "ICE restart attempts exhausted (${policy.attempt}) — falling back to restart_phase2")
            fallbackToPhase2()
        }
    }

    /**
     * ICE reached CONNECTED/COMPLETED — cancel anything pending/in-flight and reset bookkeeping
     * so the next incident starts a fresh debounce + backoff sequence.
     */
    fun onIceHealthy() {
        synchronized(lock) {
            scheduledJob?.cancel()
            scheduledJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            restartInFlight = false
            hasAttemptedOnce = false
            policy.reset()
        }
    }

    /**
     * The just-fired attempt is known to have failed immediately (e.g. `createOffer` error) —
     * don't wait for the watchdog, re-consult backoff/cap right away.
     */
    fun onAttemptFailed(trigger: IceRestartTrigger) {
        synchronized(lock) {
            watchdogJob?.cancel()
            watchdogJob = null
            restartInFlight = false
        }
        onTrigger(trigger)
    }

    /** Runs after the scheduled wait elapses; decides (under lock) whether to actually fire, then
     *  performs the restart + arms the watchdog OUTSIDE the lock. */
    private suspend fun maybeFireRestart(trigger: IceRestartTrigger) {
        val fire = synchronized(lock) {
            when {
                restartInFlight -> false // another path already fired
                isHealthyNow() -> false  // self-resolved during the wait
                else -> {
                    hasAttemptedOnce = true
                    restartInFlight = true
                    true
                }
            }
        }
        if (!fire) {
            Log.d(TAG, "ICE recovered / already in flight during the wait — skipping restart ($trigger)")
            return
        }

        performRestart(trigger) // suspend, side-effecting — outside the lock

        synchronized(lock) {
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                delay(watchdogTimeoutMs)
                val retry = synchronized(lock) {
                    if (restartInFlight) {
                        restartInFlight = false
                        true
                    } else {
                        false
                    }
                }
                if (retry) {
                    Log.w(TAG, "ICE restart attempt timed out without reaching CONNECTED ($trigger)")
                    onTrigger(trigger)
                }
            }
        }
    }

    /** Must be called with [lock] held. */
    private fun nextWaitMsOrNull(trigger: IceRestartTrigger): Long? =
        if (!hasAttemptedOnce) IceRestartDebounce.debounceMsFor(trigger) else policy.nextDelayMsOrNull()

    companion object {
        private const val TAG = "IceRestartCoordinator"

        /** Grace period for a fired attempt to reach CONNECTED before it's treated as failed
         *  and retried/backed-off, independent of whatever iceConnectionState reports next.
         *  Sized for TURN on hostile mobile networks: field logs (2026-07-11, Viettel 4G)
         *  showed the coturn allocation alone taking ~16s when the carrier/VPN throttles
         *  UDP 3478 — a 5s watchdog aborted every attempt just before it could complete. */
        const val DEFAULT_WATCHDOG_TIMEOUT_MS = 20_000L
    }
}
