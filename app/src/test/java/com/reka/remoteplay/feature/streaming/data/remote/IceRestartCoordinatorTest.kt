package com.reka.remoteplay.feature.streaming.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [IceRestartCoordinator]: the scheduling logic (debounce -> attempt -> backoff ->
 * fallback) extracted from [WebRtcManager] so it can be verified with virtual time and fake
 * callbacks, without a live PeerConnection.
 *
 * NOTE on timing: most tests use bounded [advanceTimeBy] (with a `+1` margin) rather than
 * [advanceUntilIdle], because a fired attempt schedules its own watchdog, which — if left
 * unhealthy — re-triggers itself. `advanceUntilIdle` would run that whole self-rescheduling
 * chain to completion in one shot, which is only what we want for the "left alone" tests below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IceRestartCoordinatorTest {

    private class Recorder {
        var restartCount = 0
        var fallbackCount = 0
        var healthy = false
    }

    @Test
    fun `second trigger (ICE_DISCONNECTED) fires performRestart after its debounce elapses (would produce an ice_restart_offer)`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { rec.healthy },
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ }
        )

        coordinator.onTrigger(IceRestartTrigger.ICE_DISCONNECTED)
        assertEquals("must not fire before the debounce elapses", 0, rec.restartCount)

        advanceTimeBy(IceRestartDebounce.ICE_DISCONNECTED_MS + 1)

        assertEquals("second trigger should have produced one restart attempt (i.e. an ice_restart_offer)", 1, rec.restartCount)
        assertEquals(0, rec.fallbackCount)
    }

    @Test
    fun `recovering during the debounce wait skips the restart entirely`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { rec.healthy },
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ }
        )

        coordinator.onTrigger(IceRestartTrigger.NETWORK_HARD_LOST)
        rec.healthy = true // recovers before the debounce elapses
        // Safe to drain fully here: if isHealthyNow() is true when the wait elapses,
        // fireRestart() never runs and nothing further gets scheduled.
        advanceUntilIdle()

        assertEquals(0, rec.restartCount)
        assertEquals(0, rec.fallbackCount)
    }

    @Test
    fun `a trigger while a restart is already in flight is ignored`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { rec.healthy },
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ }
        )

        coordinator.onTrigger(IceRestartTrigger.ICE_FAILED)
        advanceTimeBy(IceRestartDebounce.ICE_FAILED_MS + 1)
        assertEquals(1, rec.restartCount)
        assertTrue(coordinator.restartInFlight)

        // A second, unrelated trigger arrives synchronously while the first attempt is still
        // unresolved — the in-flight guard must ignore it immediately (no time advance needed).
        coordinator.onTrigger(IceRestartTrigger.NETWORK_HARD_LOST)
        assertEquals("in-flight attempt must not be duplicated", 1, rec.restartCount)
    }

    @Test
    fun `onIceHealthy cancels a pending wait and resets bookkeeping`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { rec.healthy },
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ }
        )

        coordinator.onTrigger(IceRestartTrigger.NETWORK_SOFT_CAPABILITIES_CHANGED)
        coordinator.onIceHealthy()
        // Safe to drain fully: the scheduled wait was cancelled, so nothing is left to run.
        advanceUntilIdle()

        assertEquals(0, rec.restartCount)
        assertFalse(coordinator.restartInFlight)
    }

    @Test
    fun `onAttemptFailed re-consults backoff immediately and follows the 2-4-8s sequence before falling back`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { false }, // never recovers on its own
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ },
            // Deliberately huge so the watchdog never fires within this test's time budget —
            // isolates onAttemptFailed's own immediate re-scheduling from the watchdog path
            // (covered separately below).
            watchdogTimeoutMs = 1_000_000L
        )

        coordinator.onTrigger(IceRestartTrigger.ICE_DISCONNECTED) // attempt 1: F14 debounce (2s)
        advanceTimeBy(2_001L)
        assertEquals(1, rec.restartCount)

        coordinator.onAttemptFailed(IceRestartTrigger.ICE_DISCONNECTED) // -> retry 1: 2s backoff
        advanceTimeBy(2_001L)
        assertEquals(2, rec.restartCount)

        coordinator.onAttemptFailed(IceRestartTrigger.ICE_DISCONNECTED) // -> retry 2: 4s backoff
        advanceTimeBy(4_001L)
        assertEquals(3, rec.restartCount)

        coordinator.onAttemptFailed(IceRestartTrigger.ICE_DISCONNECTED) // -> retry 3: 8s backoff
        advanceTimeBy(8_001L)
        assertEquals(4, rec.restartCount)
        assertEquals("cap not yet reached (1 initial + 3 retries consumed)", 0, rec.fallbackCount)

        // A 4th failure: the retry budget (maxAttempts=3) is exhausted -> fall back, no 5th attempt.
        coordinator.onAttemptFailed(IceRestartTrigger.ICE_DISCONNECTED)
        assertEquals(4, rec.restartCount)
        assertEquals(1, rec.fallbackCount)
        assertFalse(coordinator.restartInFlight)
    }

    @Test
    fun `left alone, a persistently unhealthy connection retries via the watchdog then falls back once`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { false }, // never recovers - the watchdog must drive every retry
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ }
            // default watchdog timeout (5s) - nothing else pokes the coordinator after this.
        )

        coordinator.onTrigger(IceRestartTrigger.ICE_DISCONNECTED)
        advanceUntilIdle() // drains the whole debounce -> attempt -> watchdog -> retry -> ... chain

        assertEquals("1 initial + 3 retries, all watchdog-driven", 4, rec.restartCount)
        assertEquals(1, rec.fallbackCount)
        assertFalse(coordinator.restartInFlight)
    }

    @Test
    fun `watchdog timeout clears restartInFlight and schedules a retry without firing it immediately`() = runTest {
        val rec = Recorder()
        val coordinator = IceRestartCoordinator(
            scope = this,
            isHealthyNow = { false },
            performRestart = { rec.restartCount++ },
            fallbackToPhase2 = { rec.fallbackCount++ },
            watchdogTimeoutMs = 1_000L
        )

        coordinator.onTrigger(IceRestartTrigger.HOST_REQUESTED) // 0ms debounce -> fires right away
        advanceTimeBy(1L)
        assertEquals(1, rec.restartCount)
        assertTrue("in flight until the watchdog or a health signal clears it", coordinator.restartInFlight)

        advanceTimeBy(1_001L) // past the 1s watchdog
        assertFalse("watchdog should have cleared restartInFlight", coordinator.restartInFlight)
        assertEquals("the retry it schedules next uses backoff, so it hasn't fired yet", 1, rec.restartCount)
    }
}
