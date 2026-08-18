package com.reka.remoteplay.feature.connection.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingReconnectPolicyTest {

    @Test
    fun `no prior pairings when store is empty`() {
        assertEquals(
            ReconnectTrust.NO_PRIOR_PAIRINGS,
            PairingReconnectPolicy.decide(emptySet(), "sha-256 AA:AA")
        )
    }

    @Test
    fun `known host when fingerprint matches a stored entry`() {
        assertEquals(
            ReconnectTrust.KNOWN_HOST,
            PairingReconnectPolicy.decide(setOf("sha-256 AA:AA", "sha-256 BB:BB"), "sha-256 AA:AA")
        )
    }

    @Test
    fun `unknown host warning when store non-empty but fingerprint not found (possible substitution)`() {
        assertEquals(
            ReconnectTrust.UNKNOWN_HOST_WARNING,
            PairingReconnectPolicy.decide(setOf("sha-256 BB:BB"), "sha-256 AA:AA")
        )
    }

    @Test
    fun `unknown fingerprint when hostFingerprint is null or blank`() {
        assertEquals(ReconnectTrust.UNKNOWN_FINGERPRINT, PairingReconnectPolicy.decide(setOf("sha-256 AA:AA"), null))
        assertEquals(ReconnectTrust.UNKNOWN_FINGERPRINT, PairingReconnectPolicy.decide(emptySet(), ""))
    }
}
