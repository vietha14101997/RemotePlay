package com.reka.remoteplay.feature.connection.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.PairingClientProofMessage
import com.reka.remoteplay.core.model.PairingHostProofMessage
import com.reka.remoteplay.core.security.PairingCrypto
import com.reka.remoteplay.core.util.SdpFingerprintExtractor
import com.reka.remoteplay.feature.connection.data.local.PairingStore
import com.reka.remoteplay.feature.connection.domain.model.PairingPhase
import com.reka.remoteplay.feature.connection.domain.model.PairingReconnectPolicy
import com.reka.remoteplay.feature.connection.domain.model.PendingPairingSecret
import com.reka.remoteplay.feature.connection.domain.model.ReconnectTrust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the Phase-1 pairing handshake state for the CURRENT session
 * (pairing-protocol-contract-v1.md). One instance per app (Hilt @Singleton) so the same pending
 * secret set by [ConnectionViewModel][com.reka.remoteplay.feature.connection.presentation.ConnectionViewModel]
 * from a scanned QR is visible to [PhaseTwoHandler] once it starts listening.
 *
 * Security spine: [isBlocking] is fail-closed by construction — every phase except
 * [PairingPhase.NotRequired] (legacy, no pairing offered) and [PairingPhase.Verified] blocks.
 */
@Singleton
class PairingSessionManager @Inject constructor(
    private val pairingStore: PairingStore
) {
    private val _phase = MutableStateFlow<PairingPhase>(PairingPhase.NotRequired)
    val phase: StateFlow<PairingPhase> = _phase.asStateFlow()

    private var pendingSecret: PendingPairingSecret? = null
    private var clientFingerprint: String? = null
    private var hostFingerprint: String? = null

    /** True while media/input MUST stay blocked — see contract "Gate points". */
    val isBlocking: Boolean
        get() = when (_phase.value) {
            is PairingPhase.NotRequired, is PairingPhase.Verified -> false
            else -> true
        }

    /** Reset for a brand new connection attempt (called from [PhaseTwoHandler.reset]). */
    fun startNewSession() {
        pendingSecret = null
        clientFingerprint = null
        hostFingerprint = null
        _phase.value = PairingPhase.NotRequired
    }

    /** Called by ConnectionViewModel right after parsing a QR that carries a pairing offer (`prv=1`). */
    fun offerPairing(psk: String, nonce: String, sid: String, expMs: Long) {
        pendingSecret = PendingPairingSecret(psk, nonce, sid, expMs)
        _phase.value = PairingPhase.AwaitingHandshake
    }

    /** Extracts + records our own DTLS fingerprint from the main PC offer SDP we just created. */
    fun onLocalOfferSdp(sdp: String) {
        clientFingerprint = SdpFingerprintExtractor.extract(sdp)
    }

    /** Extracts + records the host's DTLS fingerprint from its answer SDP. */
    fun onRemoteAnswerSdp(sdp: String) {
        hostFingerprint = SdpFingerprintExtractor.extract(sdp)
    }

    /**
     * Builds the `pairing_client_proof` message once DTLS is connected, or null if there's
     * nothing to send this session. A null return while a secret WAS offered means a hard,
     * fail-closed failure (see [phase] — will be [PairingPhase.Failed]); callers must treat that
     * distinctly from "no pairing was ever offered" ([PairingPhase.NotRequired]).
     */
    fun buildClientProofOrNull(): PairingClientProofMessage? {
        val secret = pendingSecret ?: return null
        if (_phase.value != PairingPhase.AwaitingHandshake) return null

        val hostFp = hostFingerprint
        val clientFp = clientFingerprint
        if (hostFp == null || clientFp == null) {
            Log.e(TAG, "Cannot build pairing proof: missing DTLS fingerprint (host=${hostFp != null}, client=${clientFp != null})")
            _phase.value = PairingPhase.Failed("missing DTLS fingerprint")
            return null
        }
        if (secret.isExpired()) {
            _phase.value = PairingPhase.Failed("pairing secret expired")
            return null
        }

        val macC = PairingCrypto.computeMac(secret.psk, TAG_CLIENT, secret.sid, secret.nonce, hostFp, clientFp)
        _phase.value = PairingPhase.AwaitingHostProof
        return PairingClientProofMessage(sid = secret.sid, macC = macC)
    }

    /** Verifies the host's `pairing_host_proof`; on success persists hostFp + marks [PairingPhase.Verified]. */
    suspend fun onHostProof(msg: PairingHostProofMessage): Boolean {
        val secret = pendingSecret
        val hostFp = hostFingerprint
        val clientFp = clientFingerprint
        if (secret == null || hostFp == null || clientFp == null || _phase.value != PairingPhase.AwaitingHostProof) {
            Log.e(TAG, "Unexpected pairing_host_proof (phase=${_phase.value})")
            _phase.value = PairingPhase.Failed("unexpected pairing_host_proof")
            return false
        }

        val expectedMac = PairingCrypto.computeMac(secret.psk, TAG_HOST, secret.sid, secret.nonce, hostFp, clientFp)
        if (!PairingCrypto.constantTimeEquals(expectedMac, msg.macH)) {
            Log.e(TAG, "Pairing host proof MAC mismatch — rejecting (fail-closed)")
            _phase.value = PairingPhase.Failed("MAC verification failed")
            return false
        }

        pairingStore.recordPaired(hostFp)
        _phase.value = PairingPhase.Verified
        Log.i(TAG, "Pairing verified, SAS=${msg.sas}")
        return true
    }

    fun onPairingFailed(reason: String) {
        Log.e(TAG, "Host reported pairing_failed: $reason")
        _phase.value = PairingPhase.Failed(reason)
    }

    /** Host demands pairing but we had no psk to answer with — this session cannot proceed. */
    fun onPairingRequiredByHost() {
        Log.w(TAG, "Host requires pairing (pairing_required) — no pairing secret available this session")
        _phase.value = PairingPhase.PairingRequiredByHost
    }

    /**
     * Reconnect-trust check (no active psk this session): warns (never auto-trusts) when this
     * host's fingerprint doesn't match any previously paired host while we DO have prior
     * pairings recorded — a possible substitution. The host itself is the actual enforcement
     * point (its own PeerAuthGate); this is a client-side UX signal only.
     */
    suspend fun checkReconnectTrust(): ReconnectTrust {
        val stored = pairingStore.snapshot().map { it.fingerprint }.toSet()
        return PairingReconnectPolicy.decide(stored, hostFingerprint)
    }

    private companion object {
        const val TAG = "PairingSessionManager"
        const val TAG_CLIENT = "C"
        const val TAG_HOST = "H"
    }
}
