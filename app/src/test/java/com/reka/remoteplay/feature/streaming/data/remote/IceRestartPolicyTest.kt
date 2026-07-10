package com.reka.remoteplay.feature.streaming.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure Phase 5 (ICE Restart on Network Change) building blocks: adaptive debounce
 * selection (F14), backoff sequence + attempt cap (between-retries spacing), and the F8
 * capability/session gate. None of these touch a live PeerConnection.
 */
class IceRestartPolicyTest {

    // ---------------- IceRestartDebounce (F14 adaptive debounce selection) ----------------

    @Test
    fun `hard network loss uses a short debounce`() {
        assertEquals(750L, IceRestartDebounce.debounceMsFor(IceRestartTrigger.NETWORK_HARD_LOST))
    }

    @Test
    fun `soft capabilities change uses a longer debounce`() {
        assertEquals(3_000L, IceRestartDebounce.debounceMsFor(IceRestartTrigger.NETWORK_SOFT_CAPABILITIES_CHANGED))
    }

    @Test
    fun `ice disconnected uses a medium debounce, ice failed uses a short one`() {
        assertEquals(2_000L, IceRestartDebounce.debounceMsFor(IceRestartTrigger.ICE_DISCONNECTED))
        assertEquals(500L, IceRestartDebounce.debounceMsFor(IceRestartTrigger.ICE_FAILED))
    }

    @Test
    fun `hard signals debounce shorter than soft signals`() {
        val hard = IceRestartDebounce.debounceMsFor(IceRestartTrigger.NETWORK_HARD_LOST)
        val soft = IceRestartDebounce.debounceMsFor(IceRestartTrigger.NETWORK_SOFT_CAPABILITIES_CHANGED)
        assertTrue("hard ($hard) should debounce shorter than soft ($soft)", hard < soft)

        val iceFailed = IceRestartDebounce.debounceMsFor(IceRestartTrigger.ICE_FAILED)
        val iceDisconnected = IceRestartDebounce.debounceMsFor(IceRestartTrigger.ICE_DISCONNECTED)
        assertTrue("FAILED ($iceFailed) should debounce shorter than DISCONNECTED ($iceDisconnected)", iceFailed < iceDisconnected)
    }

    @Test
    fun `host-requested restart acts immediately`() {
        assertEquals(0L, IceRestartDebounce.debounceMsFor(IceRestartTrigger.HOST_REQUESTED))
    }

    // ---------------- IceRestartPolicy (backoff sequence + attempt cap) ----------------

    @Test
    fun `backoff sequence follows 2-4-8s then exhausts at the default cap of 3`() {
        val policy = IceRestartPolicy()

        assertEquals(2_000L, policy.nextDelayMsOrNull())
        assertEquals(4_000L, policy.nextDelayMsOrNull())
        assertEquals(8_000L, policy.nextDelayMsOrNull())
        assertNull("a 4th retry beyond the default cap of 3 must fall back", policy.nextDelayMsOrNull())
    }

    @Test
    fun `attempt count increments by one per call`() {
        val policy = IceRestartPolicy()
        assertEquals(0, policy.attempt)
        policy.nextDelayMsOrNull()
        assertEquals(1, policy.attempt)
        policy.nextDelayMsOrNull()
        assertEquals(2, policy.attempt)
    }

    @Test
    fun `hasExceededMax flips true once the cap is reached, not before`() {
        val policy = IceRestartPolicy(maxAttempts = 2, delaysMs = listOf(1_000L, 2_000L))
        assertTrue(!policy.hasExceededMax)
        policy.nextDelayMsOrNull()
        assertTrue(!policy.hasExceededMax)
        policy.nextDelayMsOrNull()
        assertTrue(policy.hasExceededMax)
        assertNull(policy.nextDelayMsOrNull())
    }

    @Test
    fun `reset restarts the sequence from the first delay`() {
        val policy = IceRestartPolicy()
        policy.nextDelayMsOrNull() // 2s
        policy.nextDelayMsOrNull() // 4s
        assertEquals(2, policy.attempt)

        policy.reset()
        assertEquals(0, policy.attempt)
        assertEquals(2_000L, policy.nextDelayMsOrNull())
    }

    @Test
    fun `custom maxAttempts and delays are respected`() {
        val policy = IceRestartPolicy(maxAttempts = 1, delaysMs = listOf(500L))
        assertEquals(500L, policy.nextDelayMsOrNull())
        assertNull(policy.nextDelayMsOrNull())
    }

    // ---------------- IceRestartGate (F8 capability + session-liveness gate) ----------------

    @Test
    fun `no live session is ignored regardless of capability`() {
        assertEquals(
            IceRestartDecision.IGNORE_NO_SESSION,
            IceRestartGate.decide(hasLiveSession = false, hostSupportsIceRestart = true)
        )
        assertEquals(
            IceRestartDecision.IGNORE_NO_SESSION,
            IceRestartGate.decide(hasLiveSession = false, hostSupportsIceRestart = false)
        )
    }

    @Test
    fun `capability false falls back to restart_phase2`() {
        assertEquals(
            IceRestartDecision.FALLBACK_RESTART_PHASE2,
            IceRestartGate.decide(hasLiveSession = true, hostSupportsIceRestart = false)
        )
    }

    @Test
    fun `capability true with a live session attempts the ICE restart (leads to ice_restart_offer)`() {
        assertEquals(
            IceRestartDecision.ATTEMPT_ICE_RESTART,
            IceRestartGate.decide(hasLiveSession = true, hostSupportsIceRestart = true)
        )
    }
}
