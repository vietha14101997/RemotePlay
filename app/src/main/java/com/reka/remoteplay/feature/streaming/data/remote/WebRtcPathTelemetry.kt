package com.reka.remoteplay.feature.streaming.data.remote

/**
 * Pure, PeerConnection-free helpers for classifying/deriving the WAN P2P telemetry snapshot
 * contract fields (see
 * `plans/260715-2315-wan-p2p-turn-quality-hardening/telemetry-snapshot-contract-v1.md`).
 * Kept separate from [WebRtcManager] so enum-folding, path-class derivation and
 * transition-detection are unit-testable without a live PeerConnection/getStats round trip.
 */
object WebRtcPathTelemetry {

    /**
     * Bounded enum fold for libwebrtc's RTCIceCandidateType stats field ("host"/"srflx"/
     * "prflx"/"relay"). Anything else (missing field, unexpected/future libwebrtc value) folds
     * to "unknown" per the contract's bounded-cardinality rule — never let an arbitrary string
     * (which could carry more detail than intended) flow through to the relay unfiltered.
     */
    fun candidateTypeOf(raw: String?): String = when (raw) {
        "host", "srflx", "prflx", "relay" -> raw
        else -> "unknown"
    }

    /** relay if either side of the nominated pair is a relay candidate, else direct — matches
     *  the contract's derivation rule exactly. "unknown" only when both types are unresolved. */
    fun pathClassOf(localCandidateType: String, remoteCandidateType: String): String = when {
        localCandidateType == "relay" || remoteCandidateType == "relay" -> "relay"
        localCandidateType == "unknown" && remoteCandidateType == "unknown" -> "unknown"
        else -> "direct"
    }

    /** Transport of the nominated pair (candidate-pair/candidate stats "protocol" field). */
    fun protocolOf(raw: String?): String = when (raw?.lowercase()) {
        "udp" -> "udp"
        "tcp" -> "tcp"
        else -> "unknown"
    }

    /**
     * TURN relay transport (the local relay candidate's "relayProtocol" stats field).
     * "none" when the selected pair isn't using a relay candidate at all — the contract
     * distinguishes "no relay in use" from "relay in use but transport unreadable".
     */
    fun relayProtocolOf(localCandidateType: String, rawRelayProtocol: String?): String {
        if (localCandidateType != "relay") return "none"
        return when (rawRelayProtocol?.lowercase()) {
            "udp" -> "udp"
            "tcp" -> "tcp"
            "tls" -> "tls"
            else -> "unknown"
        }
    }

    /** Identity of a nominated candidate pair, used to detect `path_transition` events. Two
     *  snapshots with an equal key represent the same selected path. */
    data class SelectedPathKey(
        val localCandidateType: String,
        val remoteCandidateType: String,
        val addressFamily: String,
        val protocol: String,
        val relayProtocol: String
    )

    /**
     * true when [current] differs from a known [previous] state. A null [previous] means no
     * prior snapshot exists yet for this PC/generation — that case is a `snapshot` event, not a
     * transition, so callers must check for null separately (this function intentionally
     * returns false for it rather than conflating "no history" with "no change").
     */
    fun isTransition(previous: SelectedPathKey?, current: SelectedPathKey): Boolean =
        previous != null && previous != current
}
