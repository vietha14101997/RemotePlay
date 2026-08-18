package com.reka.remoteplay.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdpFingerprintExtractorTest {

    @Test
    fun `extracts fingerprint including sha-256 prefix`() {
        val sdp = """
            v=0
            o=- 123 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
            a=setup:actpass
        """.trimIndent()

        assertEquals("sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99", SdpFingerprintExtractor.extract(sdp))
    }

    @Test
    fun `returns null when no fingerprint line present`() {
        val sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
        assertNull(SdpFingerprintExtractor.extract(sdp))
    }

    @Test
    fun `returns null for empty sdp`() {
        assertNull(SdpFingerprintExtractor.extract(""))
    }

    @Test
    fun `handles CRLF line endings`() {
        val sdp = "v=0\r\na=fingerprint:sha-256 11:22:33\r\na=setup:active\r\n"
        assertEquals("sha-256 11:22:33", SdpFingerprintExtractor.extract(sdp))
    }

    @Test
    fun `extracts first fingerprint when multiple m-lines each declare one`() {
        val sdp = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=fingerprint:sha-256 11:11:11
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=fingerprint:sha-256 22:22:22
        """.trimIndent()

        assertEquals("sha-256 11:11:11", SdpFingerprintExtractor.extract(sdp))
    }
}
