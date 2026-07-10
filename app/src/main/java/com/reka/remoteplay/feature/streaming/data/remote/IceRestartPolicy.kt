package com.reka.remoteplay.feature.streaming.data.remote

/**
 * Phase 5 — ICE Restart on Network Change: pure, dependency-free building blocks for deciding
 * WHEN and WHETHER to attempt an ICE restart. Kept separate from [WebRtcManager] /
 * [IceRestartCoordinator] so the delay math and gating decisions can be unit tested without a
 * live PeerConnection — mirrors [com.reka.remoteplay.core.network.WebSocketReconnectPolicy].
 */

/** What caused a restart attempt to be considered. Used only for the debounce lookup below and
 *  for logging — no other behavioral branching depends on the specific source (Android is
 *  always the offerer regardless of trigger, see the phase's glare-avoidance requirement). */
enum class IceRestartTrigger {
    /** Android `ConnectivityManager.onLost` for the active network — hard signal, act fast. */
    NETWORK_HARD_LOST,

    /** Android `onCapabilitiesChanged` reporting a transport change — soft signal, may still
     *  self-resolve, wait longer before acting. */
    NETWORK_SOFT_CAPABILITIES_CHANGED,

    /** [WebRtcManager]'s own iceConnectionState monitor saw DISCONNECTED — may still recover
     *  via the ICE agent's own consent checks. */
    ICE_DISCONNECTED,

    /** iceConnectionState monitor saw FAILED — the ICE agent already gave up, act fast. */
    ICE_FAILED,

    /** Host sent `request_ice_restart` — it already decided a restart is needed. */
    HOST_REQUESTED
}

/**
 * Adaptive debounce durations (F14): short on signals that already confirm a loss, longer on
 * signals that might still self-resolve without any action.
 */
object IceRestartDebounce {
    const val NETWORK_HARD_LOST_MS = 750L
    const val NETWORK_SOFT_CHANGE_MS = 3_000L
    const val ICE_DISCONNECTED_MS = 2_000L
    const val ICE_FAILED_MS = 500L
    const val HOST_REQUESTED_MS = 0L

    fun debounceMsFor(trigger: IceRestartTrigger): Long = when (trigger) {
        IceRestartTrigger.NETWORK_HARD_LOST -> NETWORK_HARD_LOST_MS
        IceRestartTrigger.NETWORK_SOFT_CAPABILITIES_CHANGED -> NETWORK_SOFT_CHANGE_MS
        IceRestartTrigger.ICE_DISCONNECTED -> ICE_DISCONNECTED_MS
        IceRestartTrigger.ICE_FAILED -> ICE_FAILED_MS
        IceRestartTrigger.HOST_REQUESTED -> HOST_REQUESTED_MS
    }
}

/**
 * Exponential backoff + attempt cap between ICE-restart RETRIES. The very first attempt after
 * a trigger fires is gated by [IceRestartDebounce] instead of this policy (see
 * [IceRestartCoordinator]) — this class only governs the spacing between attempt 2, 3, ... after
 * an earlier attempt has already failed to bring ICE back to CONNECTED.
 *
 * Delay sequence: 2s, 4s, 8s. After [maxAttempts] retries have been consumed,
 * [nextDelayMsOrNull] returns null and the caller must fall back to a full `restart_phase2`.
 */
class IceRestartPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val delaysMs: List<Long> = DEFAULT_DELAYS_MS
) {
    /** Number of retries consumed since the last [reset]. */
    var attempt: Int = 0
        private set

    val hasExceededMax: Boolean get() = attempt >= maxAttempts

    /** Advances to the next retry and returns its backoff delay in ms, or null once the
     *  attempt cap has already been reached (caller must fall back to `restart_phase2`). */
    fun nextDelayMsOrNull(): Long? {
        if (hasExceededMax) return null
        val delay = delaysMs.getOrElse(attempt) { delaysMs.last() }
        attempt += 1
        return delay
    }

    /** Call after a restart succeeds, or once an incident is abandoned (fallback fired), so
     *  the next incident starts a fresh sequence. */
    fun reset() {
        attempt = 0
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        val DEFAULT_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)
    }
}

/** Outcome of gating a restart trigger against F8 (host capability) + session liveness. */
enum class IceRestartDecision {
    /** Attempt the fast ICE-restart path (debounce -> restartIce -> ice_restart_offer). */
    ATTEMPT_ICE_RESTART,

    /** Host never advertised `supports_ice_restart` — go straight to `restart_phase2`. */
    FALLBACK_RESTART_PHASE2,

    /** No live main PeerConnection — nothing to restart, the trigger is ignored outright. */
    IGNORE_NO_SESSION
}

/**
 * Pure F8 gate: should a trigger attempt an ICE restart, fall back to `restart_phase2`, or be
 * ignored outright? Kept as a standalone function (no PeerConnection/WebSocket dependency) so
 * it is directly unit testable — see [WebRtcManager.triggerIceRestart].
 */
object IceRestartGate {
    fun decide(hasLiveSession: Boolean, hostSupportsIceRestart: Boolean): IceRestartDecision = when {
        !hasLiveSession -> IceRestartDecision.IGNORE_NO_SESSION
        !hostSupportsIceRestart -> IceRestartDecision.FALLBACK_RESTART_PHASE2
        else -> IceRestartDecision.ATTEMPT_ICE_RESTART
    }
}
