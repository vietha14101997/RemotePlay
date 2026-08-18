package com.reka.remoteplay.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAC/SAS determinism against `pairing-protocol-contract-v1.md` — both sides (Host C#, Android)
 * must derive byte-identical values from the same inputs for the handshake to work at all.
 */
class PairingCryptoTest {

    private val psk = "dGVzdC1wc2stMzItYnl0ZXMtYmFzZTY0dXJs" // arbitrary base64url-looking psk
    private val sid = "11111111-2222-3333-4444-555555555555"
    private val nonce = "bm9uY2UtMTYtYnl0ZXM"
    private val hostFp = "sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val clientFp = "sha-256 99:88:77:66:55:44:33:22:11:00:FF:EE:DD:CC:BB:AA"

    @Test
    fun `computeMac is deterministic for identical inputs`() {
        val mac1 = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        val mac2 = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        assertEquals(mac1, mac2)
    }

    @Test
    fun `computeMac differs between client and host tags`() {
        val macC = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        val macH = PairingCrypto.computeMac(psk, "H", sid, nonce, hostFp, clientFp)
        assertFalse(macC == macH)
    }

    @Test
    fun `computeMac changes if any field changes`() {
        val base = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        assertFalse(base == PairingCrypto.computeMac(psk, "C", "different-sid", nonce, hostFp, clientFp))
        assertFalse(base == PairingCrypto.computeMac(psk, "C", sid, "different-nonce", hostFp, clientFp))
        assertFalse(base == PairingCrypto.computeMac(psk, "C", sid, nonce, "sha-256 00:00", clientFp))
        assertFalse(base == PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, "sha-256 11:11"))
        assertFalse(base == PairingCrypto.computeMac("different-psk", "C", sid, nonce, hostFp, clientFp))
    }

    @Test
    fun `computeMac produces valid base64`() {
        val mac = PairingCrypto.computeMac(psk, "C", sid, nonce, hostFp, clientFp)
        // HMAC-SHA256 digest is 32 bytes -> 44 base64 chars (with padding).
        assertEquals(44, mac.length)
        assertTrue(java.util.Base64.getDecoder().decode(mac).size == 32)
    }

    @Test
    fun `computeSas is deterministic and always 4 digits`() {
        val sas1 = PairingCrypto.computeSas(psk, sid, nonce, hostFp, clientFp)
        val sas2 = PairingCrypto.computeSas(psk, sid, nonce, hostFp, clientFp)
        assertEquals(sas1, sas2)
        assertEquals(4, sas1.length)
        assertTrue(sas1.all { it.isDigit() })
    }

    @Test
    fun `computeSas changes when inputs change`() {
        val base = PairingCrypto.computeSas(psk, sid, nonce, hostFp, clientFp)
        val changed = PairingCrypto.computeSas(psk, "other-sid", nonce, hostFp, clientFp)
        assertFalse(base == changed)
    }

    @Test
    fun `constantTimeEquals true for identical strings`() {
        assertTrue(PairingCrypto.constantTimeEquals("abc123==", "abc123=="))
    }

    @Test
    fun `constantTimeEquals false for different strings of same length`() {
        assertFalse(PairingCrypto.constantTimeEquals("abc123==", "abc124=="))
    }

    @Test
    fun `constantTimeEquals false for different lengths`() {
        assertFalse(PairingCrypto.constantTimeEquals("short", "muchlongerstring"))
    }
}
