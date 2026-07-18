package com.reka.remoteplay.feature.connection.data.remote

import com.reka.remoteplay.core.model.PairingHostProofMessage
import com.reka.remoteplay.core.security.PairingCrypto
import com.reka.remoteplay.feature.connection.data.local.PairedHost
import com.reka.remoteplay.feature.connection.data.local.PairingStore
import com.reka.remoteplay.feature.connection.domain.model.PairingPhase
import com.reka.remoteplay.feature.connection.domain.model.ReconnectTrust
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingSessionManagerTest {

    private lateinit var pairingStore: PairingStore
    private lateinit var manager: PairingSessionManager

    private val psk = "test-psk-base64url"
    private val nonce = "test-nonce"
    private val sid = "test-sid-guid"
    private val hostFpSdp = "v=0\r\na=fingerprint:sha-256 AA:AA:AA\r\n"
    private val clientFpSdp = "v=0\r\na=fingerprint:sha-256 BB:BB:BB\r\n"
    private val hostFp = "sha-256 AA:AA:AA"
    private val clientFp = "sha-256 BB:BB:BB"

    @Before
    fun setUp() {
        pairingStore = mockk(relaxed = true)
        manager = PairingSessionManager(pairingStore)
    }

    @Test
    fun `initial phase is NotRequired and not blocking (legacy path)`() {
        assertEquals(PairingPhase.NotRequired, manager.phase.value)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `offerPairing moves to AwaitingHandshake and blocks`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        assertEquals(PairingPhase.AwaitingHandshake, manager.phase.value)
        assertTrue(manager.isBlocking)
    }

    @Test
    fun `buildClientProofOrNull returns null and NotRequired stays non-blocking when no offer was made`() {
        manager.onLocalOfferSdp(clientFpSdp)
        manager.onRemoteAnswerSdp(hostFpSdp)
        assertNull(manager.buildClientProofOrNull())
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `buildClientProofOrNull computes matching macC and moves to AwaitingHostProof`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        manager.onLocalOfferSdp(clientFpSdp)
        manager.onRemoteAnswerSdp(hostFpSdp)

        val proof = manager.buildClientProofOrNull()

        requireNotNull(proof)
        assertEquals(sid, proof.sid)
        val expectedMac = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        assertEquals(expectedMac, proof.macC)
        assertEquals(PairingPhase.AwaitingHostProof, manager.phase.value)
        assertTrue(manager.isBlocking)
    }

    @Test
    fun `buildClientProofOrNull fails closed when a fingerprint is missing`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        // Neither onLocalOfferSdp nor onRemoteAnswerSdp called — fingerprints unknown.
        val proof = manager.buildClientProofOrNull()
        assertNull(proof)
        assertTrue(manager.phase.value is PairingPhase.Failed)
    }

    @Test
    fun `buildClientProofOrNull fails closed when secret already expired`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() - 1_000)
        manager.onLocalOfferSdp(clientFpSdp)
        manager.onRemoteAnswerSdp(hostFpSdp)

        assertNull(manager.buildClientProofOrNull())
        assertTrue(manager.phase.value is PairingPhase.Failed)
    }

    @Test
    fun `onHostProof verifies correct macH, persists hostFp, and marks Verified`() = runTest {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        manager.onLocalOfferSdp(clientFpSdp)
        manager.onRemoteAnswerSdp(hostFpSdp)
        manager.buildClientProofOrNull() // -> AwaitingHostProof

        val macH = PairingCrypto.computeMac(psk, "H", sid, nonce, hostFp, clientFp)
        val verified = manager.onHostProof(PairingHostProofMessage(macH = macH, sas = "1234"))

        assertTrue(verified)
        assertEquals(PairingPhase.Verified, manager.phase.value)
        assertFalse(manager.isBlocking)
        coVerify { pairingStore.recordPaired(hostFp) }
    }

    @Test
    fun `onHostProof rejects a wrong macH (fail-closed) and never persists`() = runTest {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        manager.onLocalOfferSdp(clientFpSdp)
        manager.onRemoteAnswerSdp(hostFpSdp)
        manager.buildClientProofOrNull()

        val verified = manager.onHostProof(PairingHostProofMessage(macH = "not-the-right-mac", sas = "0000"))

        assertFalse(verified)
        assertTrue(manager.phase.value is PairingPhase.Failed)
        assertTrue(manager.isBlocking)
        coVerify(exactly = 0) { pairingStore.recordPaired(any()) }
    }

    @Test
    fun `onHostProof out of sequence (no proof sent) is rejected`() = runTest {
        val verified = manager.onHostProof(PairingHostProofMessage(macH = "anything", sas = "0000"))
        assertFalse(verified)
        assertTrue(manager.phase.value is PairingPhase.Failed)
    }

    @Test
    fun `onPairingFailed and onPairingRequiredByHost set the corresponding phase`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        manager.onPairingFailed("bad mac")
        assertEquals(PairingPhase.Failed("bad mac"), manager.phase.value)

        manager.startNewSession()
        manager.onPairingRequiredByHost()
        assertEquals(PairingPhase.PairingRequiredByHost, manager.phase.value)
    }

    @Test
    fun `startNewSession resets to NotRequired from any state`() {
        manager.offerPairing(psk, nonce, sid, expMs = System.currentTimeMillis() + 60_000)
        manager.onPairingFailed("x")
        manager.startNewSession()
        assertEquals(PairingPhase.NotRequired, manager.phase.value)
        assertFalse(manager.isBlocking)
    }

    // ---------------- Reconnect-trust check ----------------

    @Test
    fun `checkReconnectTrust returns NO_PRIOR_PAIRINGS when store empty`() = runTest {
        coEvery { pairingStore.snapshot() } returns emptyList()
        manager.onRemoteAnswerSdp(hostFpSdp)
        assertEquals(ReconnectTrust.NO_PRIOR_PAIRINGS, manager.checkReconnectTrust())
    }

    @Test
    fun `checkReconnectTrust returns KNOWN_HOST when fingerprint matches stored entry`() = runTest {
        coEvery { pairingStore.snapshot() } returns listOf(PairedHost(fingerprint = hostFp, pairedAt = 1L))
        manager.onRemoteAnswerSdp(hostFpSdp)
        assertEquals(ReconnectTrust.KNOWN_HOST, manager.checkReconnectTrust())
    }

    @Test
    fun `checkReconnectTrust warns when fingerprint unrecognized but prior pairings exist`() = runTest {
        coEvery { pairingStore.snapshot() } returns listOf(PairedHost(fingerprint = "sha-256 ZZ:ZZ", pairedAt = 1L))
        manager.onRemoteAnswerSdp(hostFpSdp)
        assertEquals(ReconnectTrust.UNKNOWN_HOST_WARNING, manager.checkReconnectTrust())
    }
}
