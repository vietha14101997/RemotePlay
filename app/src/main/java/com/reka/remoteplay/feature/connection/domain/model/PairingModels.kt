package com.reka.remoteplay.feature.connection.domain.model

/**
 * Pairing handshake phase for the CURRENT session. Distinct from [ConnectionState]'s
 * `Pairing`/`Verifying` UI states — this tracks the handshake's internal progress so
 * [com.reka.remoteplay.feature.connection.data.remote.PairingSessionManager] can decide when
 * media/input must stay blocked.
 */
sealed class PairingPhase {
    /** No pairing offered this session (legacy path, or host has RequirePairing OFF) — never gates. */
    data object NotRequired : PairingPhase()

    /** QR carried `prv=1`; have a pending secret, waiting for DTLS fingerprints. */
    data object AwaitingHandshake : PairingPhase()

    /** Client proof sent (`pairing_client_proof`), waiting for the host's reply. */
    data object AwaitingHostProof : PairingPhase()

    /** Host proof verified — media/input may proceed. */
    data object Verified : PairingPhase()

    /** Handshake failed (bad MAC, expired secret, missing fingerprint, host-reported failure). */
    data class Failed(val reason: String) : PairingPhase()

    /** Host demanded pairing (`pairing_required`) but this session had no psk to answer with. */
    data object PairingRequiredByHost : PairingPhase()
}

/** One-use pairing secret parsed from a host-minted QR code (`prv=1` fields). */
data class PendingPairingSecret(
    val psk: String,
    val nonce: String,
    val sid: String,
    val expMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expMs
}

/** Outcome of the reconnect-trust check (no active psk this session) against locally stored fingerprints. */
enum class ReconnectTrust {
    /** This device has never completed a pairing — legacy/first-contact, nothing to compare. */
    NO_PRIOR_PAIRINGS,
    /** Host's fingerprint matches a previously paired entry. */
    KNOWN_HOST,
    /** Host's fingerprint does NOT match any previously paired entry — possible substitution. */
    UNKNOWN_HOST_WARNING,
    /** Host's SDP had no fingerprint to compare (malformed/pre-DTLS). */
    UNKNOWN_FINGERPRINT
}

/**
 * Pure decision logic for the reconnect-trust check — kept separate from [PairingStore][
 * com.reka.remoteplay.feature.connection.data.local.PairingStore] (DataStore/Context-backed) so
 * it's unit-testable without Robolectric/instrumentation.
 */
object PairingReconnectPolicy {
    fun decide(storedFingerprints: Set<String>, hostFingerprint: String?): ReconnectTrust = when {
        hostFingerprint.isNullOrBlank() -> ReconnectTrust.UNKNOWN_FINGERPRINT
        storedFingerprints.isEmpty() -> ReconnectTrust.NO_PRIOR_PAIRINGS
        storedFingerprints.contains(hostFingerprint) -> ReconnectTrust.KNOWN_HOST
        else -> ReconnectTrust.UNKNOWN_HOST_WARNING
    }
}
