package com.reka.remoteplay.core.security

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure MAC/SAS computation matching `pairing-protocol-contract-v1.md` EXACTLY. No Android
 * framework dependency — fully unit-testable on the plain JVM, and must produce byte-identical
 * results to the Host's (C#) implementation for the handshake to work.
 *
 * MAC input string (exact, UTF-8): `RS-PAIR-v1|<TAG>|<sid>|<nonce>|<hostFp>|<clientFp>`
 * macX = base64(HMAC-SHA256(key = utf8(psk-as-string), msg)).
 */
object PairingCrypto {
    private const val HMAC_ALGO = "HmacSHA256"
    private const val PROTOCOL_TAG = "RS-PAIR-v1"

    private fun macInput(tag: String, sid: String, nonce: String, hostFp: String, clientFp: String): ByteArray =
        "$PROTOCOL_TAG|$tag|$sid|$nonce|$hostFp|$clientFp".toByteArray(Charsets.UTF_8)

    private fun hmacSha256(pskUtf8Key: String, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(pskUtf8Key.toByteArray(Charsets.UTF_8), HMAC_ALGO))
        return mac.doFinal(message)
    }

    /** `macX` for TAG = "C" (client) or "H" (host); base64-encoded HMAC-SHA256 digest. */
    fun computeMac(psk: String, tag: String, sid: String, nonce: String, hostFp: String, clientFp: String): String {
        val digest = hmacSha256(psk, macInput(tag, sid, nonce, hostFp, clientFp))
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * `sas` = first 4 decimal digits of HMAC-SHA256(psk, "RS-PAIR-v1|SAS|sid|nonce|hostFp|clientFp"),
     * interpreted as a big-endian uint32 over the digest's first 4 bytes, `% 10000`, zero-padded.
     */
    fun computeSas(psk: String, sid: String, nonce: String, hostFp: String, clientFp: String): String {
        val digest = hmacSha256(psk, macInput("SAS", sid, nonce, hostFp, clientFp))
        val value = ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)
        return (value % 10000).toString().padStart(4, '0')
    }

    /**
     * Constant-time string comparison for MAC verification — never short-circuits on the first
     * mismatched byte, so verification timing doesn't leak how many leading bytes matched.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        if (aBytes.size != bBytes.size) return false
        var result = 0
        for (i in aBytes.indices) {
            result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return result == 0
    }
}
