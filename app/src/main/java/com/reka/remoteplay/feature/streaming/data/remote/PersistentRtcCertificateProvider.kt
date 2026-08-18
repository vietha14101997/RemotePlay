package com.reka.remoteplay.feature.streaming.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.PeerConnection
import org.webrtc.RtcCertificatePem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates ONE persistent self-signed DTLS certificate (ECDSA) for this install and reuses it
 * for every PeerConnection (main + video PCs), so this device's DTLS fingerprint is stable across
 * sessions/restarts — the identity anchor required by pairing-protocol-contract-v1.md.
 *
 * Storage: app-private file (`Context.filesDir`, sandboxed to this app by the OS — Android's
 * equivalent of the Host's owner-only-ACL PFX file). Fingerprints derived from this certificate
 * are not secret (public in SDP); only the private key needs the app-sandbox protection.
 */
@Singleton
class PersistentRtcCertificateProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Volatile private var cached: RtcCertificatePem? = null

    private val certFile: File get() = File(context.filesDir, CERT_FILE_NAME)

    /** Returns the persisted certificate, generating + persisting one on first call. Thread-safe. */
    @Synchronized
    fun getOrCreate(): RtcCertificatePem {
        cached?.let { return it }
        val pem = loadFromDisk() ?: generateAndPersist()
        cached = pem
        return pem
    }

    private fun loadFromDisk(): RtcCertificatePem? {
        return try {
            val file = certFile
            if (!file.exists()) return null
            val parts = file.readText(Charsets.UTF_8).split(SEPARATOR)
            if (parts.size != 2) {
                Log.w(TAG, "Persisted DTLS certificate file malformed, regenerating")
                return null
            }
            RtcCertificatePem(parts[0], parts[1])
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted DTLS certificate, will regenerate: ${e.message}")
            null
        }
    }

    private fun generateAndPersist(): RtcCertificatePem {
        // 10-year validity — effectively "for the life of the install"; a fresh cert (and thus a
        // new fingerprint requiring re-pairing) is only generated past this window or if the
        // persisted file is lost/corrupted.
        val pem = RtcCertificatePem.generateCertificate(PeerConnection.KeyType.ECDSA, CERT_VALIDITY_SECONDS)
        try {
            certFile.writeText(pem.privateKey + SEPARATOR + pem.certificate, Charsets.UTF_8)
        } catch (e: Exception) {
            // Non-fatal: streaming still works with this session's cert, but the fingerprint
            // won't survive a process restart (breaks pairing/reconnect-trust continuity only).
            Log.e(TAG, "Failed to persist DTLS certificate — fingerprint will NOT be stable across restarts: ${e.message}")
        }
        return pem
    }

    companion object {
        private const val TAG = "RtcCertProvider"
        private const val CERT_FILE_NAME = "client-dtls-cert.pem"
        private const val SEPARATOR = "\n-----SPLIT-----\n"
        private const val CERT_VALIDITY_SECONDS = 10L * 365 * 24 * 60 * 60
    }
}
