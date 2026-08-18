package com.reka.remoteplay.core.util

/**
 * Extracts the DTLS certificate fingerprint from an SDP body — the identity anchor used by the
 * pairing protocol (`pairing-protocol-contract-v1.md`). Equivalent to the Host's
 * `SdpFingerprintExtractor` (C#).
 */
object SdpFingerprintExtractor {
    // Matches "a=fingerprint:sha-256 AB:CD:...". Captures the algorithm + hex-colon value as one
    // group so callers get the full contract-required string ("sha-256 AB:CD:...", prefix included).
    private val FINGERPRINT_REGEX = Regex("""a=fingerprint:\s*(\S+\s+\S+)""")

    /**
     * Returns the full `a=fingerprint:` value (e.g. `"sha-256 AB:CD:EF:..."`), trimmed, or null
     * if the SDP has no fingerprint line (malformed/pre-DTLS SDP — callers must treat this as a
     * fail-closed condition wherever pairing is in play).
     */
    fun extract(sdp: String): String? = FINGERPRINT_REGEX.find(sdp)?.groupValues?.get(1)?.trim()
}
