package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.PairingHostProofMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.data.remote.PairingSessionManager
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.model.PairingPhase
import com.reka.remoteplay.feature.connection.domain.model.ReconnectTrust
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Phase-1 pairing handshake orchestration on behalf of [PhaseTwoHandler] — split into
 * its own class so PhaseTwoHandler stays within the project's file-size guideline. Watches for
 * DTLS/ICE connect on the main PC, sends/verifies the `pairing_*` proof messages, and gates the
 * "ready to stream" signal until pairing (if the host offered one via QR `prv=1`) is resolved.
 * See pairing-protocol-contract-v1.md for the wire contract this implements.
 */
@Singleton
class PairingHandshakeCoordinator @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val webRtcManager: WebRtcManager,
    private val pairingSessionManager: PairingSessionManager
) {
    private var watchJob: Job? = null
    private var iceReadyReceived = false
    private var onReadyToStream: (() -> Unit)? = null

    /** Exposes pairing progress for optional UI (state text / SAS display). */
    val phase: StateFlow<PairingPhase> get() = pairingSessionManager.phase

    /** True while media/input must stay blocked on the pairing handshake. */
    fun isBlocking(): Boolean = pairingSessionManager.isBlocking

    /** Call once per session from [PhaseTwoHandler.startListening]. [onReadyToStream] is invoked
     *  once BOTH the host's readiness signal arrived AND pairing (if any) is resolved. */
    fun start(scope: CoroutineScope, onReadyToStream: () -> Unit) {
        this.onReadyToStream = onReadyToStream
        watchJob?.cancel()
        watchJob = scope.launch {
            // "AFTER DTLS connected on main PC" — ICE reaching CONNECTED/COMPLETED is the closest
            // Android signal to that (DataChannels can't open without DTLS also being done).
            webRtcManager.iceConnectionState
                .filter { it == PeerConnection.IceConnectionState.CONNECTED || it == PeerConnection.IceConnectionState.COMPLETED }
                .first()
            maybeStartHandshake(scope)
        }
    }

    /** Records our own DTLS fingerprint from the main PC offer SDP we just created. */
    fun onLocalOfferSdp(sdp: String) = pairingSessionManager.onLocalOfferSdp(sdp)

    /** Records the host's DTLS fingerprint from its answer SDP + fires the (non-blocking)
     *  reconnect-trust warning check. */
    fun onAnswerSdp(sdp: String, scope: CoroutineScope) {
        pairingSessionManager.onRemoteAnswerSdp(sdp)
        scope.launch {
            if (pairingSessionManager.checkReconnectTrust() == ReconnectTrust.UNKNOWN_HOST_WARNING) {
                Log.w(TAG, "Host DTLS fingerprint does not match any previously paired host — possible substitution, not auto-trusting")
            }
        }
    }

    /** Call when the host's `ice_ready` (or relay-media fallback) readiness signal arrives. */
    fun onIceReadySignal() {
        iceReadyReceived = true
        finalizeIfPossible()
    }

    /** Call on `pairing_host_proof`. Verification is async (suspend); state settles on [scope]. */
    fun handleHostProof(msg: PairingHostProofMessage, scope: CoroutineScope) {
        connectionStateRepo.tryTransition(ConnectionState.Verifying)
        scope.launch {
            val verified = pairingSessionManager.onHostProof(msg)
            if (verified) {
                finalizeIfPossible()
            } else {
                Log.e(TAG, "Pairing verification failed — closing connection (fail-closed)")
                connectionStateRepo.forceTransition(ConnectionState.Error("Pairing verification failed", phase = 2))
                webSocketClient.disconnect()
            }
        }
    }

    /** Call on `pairing_failed`. */
    fun handleFailed(reason: String) {
        Log.e(TAG, "Host reported pairing_failed: $reason")
        pairingSessionManager.onPairingFailed(reason)
        connectionStateRepo.forceTransition(ConnectionState.Error("Pairing failed: $reason", phase = 2))
        webSocketClient.disconnect()
    }

    /** Call on `pairing_required` (reconnect path, no psk to answer with). */
    fun handleRequiredByHost() {
        Log.w(TAG, "Host requires pairing — re-scan its QR code to pair")
        pairingSessionManager.onPairingRequiredByHost()
        connectionStateRepo.forceTransition(ConnectionState.Error("This host requires pairing — scan its QR code", phase = 2))
        webSocketClient.disconnect()
    }

    fun reset() {
        watchJob?.cancel()
        watchJob = null
        iceReadyReceived = false
        onReadyToStream = null
        pairingSessionManager.startNewSession()
    }

    private fun maybeStartHandshake(scope: CoroutineScope) {
        if (pairingSessionManager.phase.value !is PairingPhase.AwaitingHandshake) return

        val proof = pairingSessionManager.buildClientProofOrNull()
        if (proof == null) {
            // Only a genuine problem (missing fp / expired secret) sets Failed; anything else
            // (no secret / wrong phase) is legitimately nothing-to-do.
            if (pairingSessionManager.phase.value is PairingPhase.Failed) {
                connectionStateRepo.forceTransition(ConnectionState.Error("Pairing setup failed", phase = 2))
                webSocketClient.disconnect()
            }
            return
        }

        connectionStateRepo.tryTransition(ConnectionState.Pairing)
        webSocketClient.sendText(MessageParser.serialize(proof))
        Log.i(TAG, "Sent pairing_client_proof")
    }

    private fun finalizeIfPossible() {
        if (!iceReadyReceived) return
        if (pairingSessionManager.isBlocking) return
        onReadyToStream?.invoke()
    }

    private companion object {
        const val TAG = "PairingHandshake"
    }
}
