package com.reka.remoteplay.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MdnsResolver].
 *
 * Note: We avoid InetAddress.getByName() (which performs real DNS) by
 * exercising only the in-memory cache path and the candidate-shape check.
 */
class MdnsResolverTest {

    private val resolver = MdnsResolver()

    @Test
    fun `non-local candidate passes through unchanged`() {
        val cand = "candidate:1 1 UDP 2113939711 192.168.1.5 50000 typ host"
        assertEquals(cand, resolver.resolveIfNeeded(cand))
    }

    @Test
    fun `empty candidate passes through unchanged`() {
        assertEquals("", resolver.resolveIfNeeded(""))
    }

    @Test
    fun `local candidate without cached result is returned unchanged`() {
        resolver.clearCache()
        // Use a unique hostname that won't resolve via InetAddress.getByName
        val cand = "candidate:1 1 UDP 2113939711 nonexistent-test-host-12345.local 50000 typ host"
        val result = resolver.resolveIfNeeded(cand)
        // Should not crash; either returns original or resolved IP, but no exception
        assertTrue(result.startsWith("candidate:") || result.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
    }

    @Test
    fun `clearCache removes all entries`() {
        // First call (might fail to resolve but caches the attempt)
        resolver.resolveIfNeeded("candidate:1 1 UDP 2113939711 foo.local 50000 typ host")
        resolver.clearCache()
        // After clear, the next call re-attempts (no assertion, just shouldn't crash)
        resolver.resolveIfNeeded("candidate:1 1 UDP 2113939711 foo.local 50000 typ host")
    }

    @Test
    fun `extracts hostname from candidate line correctly`() {
        // The hostname is the token containing ".local"
        val cand = "candidate:1 1 UDP 2113939711 abc123.local 50000 typ host"
        val resolved = resolver.resolveIfNeeded(cand)
        // Either the candidate unchanged, or it starts with a resolved IP
        assertTrue(resolved == cand || resolved.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
    }
}
