package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import com.reka.remoteplay.core.network.relay.ConnectionTelemetryRequest
import com.reka.remoteplay.core.network.relay.RelayApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Owns WAN P2P selected-path telemetry bookkeeping — session/generation/sequence counters,
 * snapshot-vs-path_transition change detection, and send-bitrate delta math — and posts
 * snapshots to the relay, fire-and-forget. Kept separate from [WebRtcManager] so this
 * state-machine logic is unit-testable without a live PeerConnection/getStats round trip.
 * See `plans/260715-2315-wan-p2p-turn-quality-hardening/telemetry-snapshot-contract-v1.md`.
 *
 * A failed POST is swallowed here and MUST NEVER propagate into the connection path — that
 * invariant is enforced by [runCatching] in [send], not by the caller.
 */
class WebRtcConnectionTelemetryReporter(
    private val relayApi: RelayApi,
    private val scope: CoroutineScope
) {
    private data class PcKey(val role: String, val monitorIndex: Int)

    private class PcState {
        var generation: Int = 0
        var sequence: Int = 0
        var lastPathKey: WebRtcPathTelemetry.SelectedPathKey? = null
        var lastBytesSent: Long? = null
        var lastBytesSentAtMs: Long = 0L
    }

    @Volatile private var sessionId: String = "unknown"
    private val state = mutableMapOf<PcKey, PcState>()

    /** Call once per genuinely new session (first connect, or a restart_phase2 recreate that
     *  disposes and rebuilds every PC) — a fresh opaque grouping key, all per-PC
     *  generation/sequence counters restart at 0. */
    @Synchronized
    fun startNewSession() {
        sessionId = UUID.randomUUID().toString().take(12)
        state.clear()
    }

    /** Call when an EXISTING PC renegotiates within the same session (e.g. main-PC ICE
     *  restart) — session_id stays stable, only this PC's generation/epoch advances. Forces
     *  the next poll to emit a fresh `snapshot` (not a `path_transition`) for the new epoch,
     *  satisfying "every active PC produces one snapshot per generation". */
    @Synchronized
    fun bumpGeneration(role: String, monitorIndex: Int) {
        val pcState = state.getOrPut(PcKey(role, monitorIndex)) { PcState() }
        pcState.generation += 1
        pcState.lastPathKey = null
    }

    /**
     * Feed one getStats poll's raw (unfolded) candidate-pair/candidate fields for a PC. Only
     * sends a telemetry event when this is the PC's first-ever snapshot this generation, or
     * the nominated pair identity changed since the last poll (`path_transition`) — bounded
     * cardinality, no per-poll spam while the path is stable.
     *
     * @return the folded local candidate type (host/srflx/prflx/relay/unknown) so callers can
     *   keep pre-existing state (e.g. [WebRtcManager]'s main-PC `connectionType` StateFlow) in
     *   sync without re-deriving it themselves.
     */
    @Synchronized
    fun recordSelectedPair(
        role: String,
        monitorIndex: Int,
        rawLocalCandidateType: String?,
        rawRemoteCandidateType: String?,
        address: String?,
        rawProtocol: String?,
        rawRelayProtocol: String?,
        rttMs: Int?,
        availableBitrateKbps: Int?,
        bytesSent: Long?
    ): String {
        val localType = WebRtcPathTelemetry.candidateTypeOf(rawLocalCandidateType)
        val remoteType = WebRtcPathTelemetry.candidateTypeOf(rawRemoteCandidateType)
        val family = WebRtcManager.addressFamilyOf(address)
        val protocol = WebRtcPathTelemetry.protocolOf(rawProtocol)
        val relayProtocol = WebRtcPathTelemetry.relayProtocolOf(localType, rawRelayProtocol)
        val pathClass = WebRtcPathTelemetry.pathClassOf(localType, remoteType)

        val pcState = state.getOrPut(PcKey(role, monitorIndex)) { PcState() }
        val sendBitrateKbps = computeSendBitrateKbps(pcState, bytesSent)

        val pathKey = WebRtcPathTelemetry.SelectedPathKey(localType, remoteType, family, protocol, relayProtocol)
        val isFirst = pcState.lastPathKey == null
        val transitioned = WebRtcPathTelemetry.isTransition(pcState.lastPathKey, pathKey)

        if (isFirst || transitioned) {
            pcState.lastPathKey = pathKey
            pcState.sequence += 1
            send(
                event = if (isFirst) "snapshot" else "path_transition",
                role = role,
                monitorIndex = monitorIndex,
                generation = pcState.generation,
                sequence = pcState.sequence,
                localType = localType,
                remoteType = remoteType,
                family = family,
                protocol = protocol,
                relayProtocol = relayProtocol,
                pathClass = pathClass,
                rttMs = rttMs,
                sendBitrateKbps = sendBitrateKbps,
                availableBitrateKbps = availableBitrateKbps
            )
        }
        return localType
    }

    /** kbps computed from the bytesSent delta on the nominated candidate-pair stats since the
     *  last poll for this PC. Null on the first sample, a clock/counter anomaly (skew, or a
     *  counter reset from an underlying PC swap), or when the pair doesn't report bytesSent. */
    private fun computeSendBitrateKbps(pcState: PcState, bytesSent: Long?): Int? {
        val nowMs = System.currentTimeMillis()
        val prevBytes = pcState.lastBytesSent
        val prevAtMs = pcState.lastBytesSentAtMs
        pcState.lastBytesSent = bytesSent
        pcState.lastBytesSentAtMs = nowMs

        if (bytesSent == null || prevBytes == null || prevAtMs == 0L) return null
        val deltaMs = nowMs - prevAtMs
        val deltaBytes = bytesSent - prevBytes
        if (deltaMs <= 0 || deltaBytes < 0) return null
        return ((deltaBytes * 8.0) / deltaMs).roundToInt() // (bytes*8 bits)/ms == kbit/s numerically
    }

    private fun send(
        event: String,
        role: String,
        monitorIndex: Int,
        generation: Int,
        sequence: Int,
        localType: String,
        remoteType: String,
        family: String,
        protocol: String,
        relayProtocol: String,
        pathClass: String,
        rttMs: Int?,
        sendBitrateKbps: Int?,
        availableBitrateKbps: Int?
    ) {
        val request = ConnectionTelemetryRequest(
            event = event,
            sessionId = sessionId,
            pcRole = role,
            monitorIndex = monitorIndex,
            generation = generation,
            sequence = sequence,
            localCandidateType = localType,
            remoteCandidateType = remoteType,
            addressFamily = family,
            protocol = protocol,
            relayProtocol = relayProtocol,
            pathClass = pathClass,
            rttMs = rttMs,
            sendBitrateKbps = sendBitrateKbps,
            availableBitrateKbps = availableBitrateKbps
        )
        scope.launch {
            runCatching { relayApi.reportConnectionTelemetry(request) }
                .onFailure { Log.d(TAG, "telemetry report skipped: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "WebRtcTelemetry"
    }
}
