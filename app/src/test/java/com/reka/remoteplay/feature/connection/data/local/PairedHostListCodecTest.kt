package com.reka.remoteplay.feature.connection.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure serialize/merge logic — the persistence round trip DataStore itself provides. */
class PairedHostListCodecTest {

    private val hostA = PairedHost(fingerprint = "sha-256 AA:AA", label = "Gaming PC", pairedAt = 1000L)
    private val hostB = PairedHost(fingerprint = "sha-256 BB:BB", label = "Laptop", pairedAt = 2000L)

    @Test
    fun `encode then decode round-trips exactly`() {
        val encoded = PairedHostListCodec.encode(listOf(hostA, hostB))
        val decoded = PairedHostListCodec.decode(encoded)
        assertEquals(listOf(hostA, hostB), decoded)
    }

    @Test
    fun `decode of null or blank returns empty list`() {
        assertTrue(PairedHostListCodec.decode(null).isEmpty())
        assertTrue(PairedHostListCodec.decode("").isEmpty())
        assertTrue(PairedHostListCodec.decode("   ").isEmpty())
    }

    @Test
    fun `decode of malformed json returns empty list (fail-closed, never throws)`() {
        assertTrue(PairedHostListCodec.decode("not json").isEmpty())
        assertTrue(PairedHostListCodec.decode("{\"broken\":").isEmpty())
    }

    @Test
    fun `upsert adds a new fingerprint`() {
        val updated = PairedHostListCodec.upsert(listOf(hostA), hostB)
        assertEquals(2, updated.size)
        assertTrue(updated.contains(hostA))
        assertTrue(updated.contains(hostB))
    }

    @Test
    fun `upsert replaces an existing fingerprint's entry (newest first)`() {
        val refreshed = hostA.copy(label = "Renamed PC", pairedAt = 5000L)
        val updated = PairedHostListCodec.upsert(listOf(hostA, hostB), refreshed)
        assertEquals(2, updated.size)
        assertEquals(refreshed, updated.first())
        assertTrue(updated.none { it.fingerprint == hostA.fingerprint && it.label == hostA.label })
    }

    @Test
    fun `remove drops the matching fingerprint only`() {
        val updated = PairedHostListCodec.remove(listOf(hostA, hostB), hostA.fingerprint)
        assertEquals(listOf(hostB), updated)
    }

    @Test
    fun `isKnown reflects membership by fingerprint`() {
        assertTrue(PairedHostListCodec.isKnown(listOf(hostA), hostA.fingerprint))
        assertTrue(!PairedHostListCodec.isKnown(listOf(hostA), hostB.fingerprint))
        assertTrue(!PairedHostListCodec.isKnown(emptyList(), hostA.fingerprint))
    }
}
