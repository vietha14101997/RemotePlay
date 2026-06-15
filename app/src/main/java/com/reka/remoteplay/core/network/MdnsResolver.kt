package com.reka.remoteplay.core.network

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves mDNS host candidate addresses (e.g. "abc123.local") to IPv4.
 *
 * Background: per RFC 8829, modern WebRTC implementations hide host candidates
 * behind mDNS names to prevent IP leaks. The remote peer must resolve these
 * names via mDNS to actually reach the candidate. Some Android libwebrtc
 * builds do not implement mDNS resolution for incoming SDP candidates, which
 * causes cross-VLAN/LAN handshakes to fail with "host candidate not reachable".
 *
 * This class caches resolved names for 60 seconds and falls back to
 * [InetAddress.getByName] which on Android may not resolve .local — the
 * caller logs a warning so the user knows to enable mDNS on the server.
 *
 * Thread-safe: candidates can arrive on any WebRTC thread.
 */
@Singleton
class MdnsResolver @Inject constructor() {

    private val cache = ConcurrentHashMap<String, String?>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()

    /**
     * Returns the candidate unchanged if it doesn't contain ".local".
     * Otherwise, attempts to resolve via mDNS / DNS and returns the resolved
     * IPv4 string, or the original candidate if resolution fails.
     */
    fun resolveIfNeeded(candidate: String): String {
        if (candidate.isEmpty() || !candidate.contains(".local")) return candidate

        val now = System.currentTimeMillis()
        cacheTimestamps[hostnameOf(candidate)]?.let { ts ->
            if (now - ts < CACHE_TTL_MS) {
                return cache[hostnameOf(candidate)] ?: candidate
            }
        }

        val resolved = tryResolve(candidate)
        val hostname = hostnameOf(candidate)
        cache[hostname] = resolved
        cacheTimestamps[hostname] = now
        return resolved ?: candidate
    }

    private fun hostnameOf(candidate: String): String {
        // candidate line format: "candidate:foundation component id priority address port typ ..."
        // Find the token containing ".local" (typically the 5th token = address)
        return candidate.split(" ").firstOrNull { it.contains(".local") } ?: candidate
    }

    private fun tryResolve(candidate: String): String? {
        val hostname = hostnameOf(candidate)
        return try {
            // Standard JVM DNS lookup. On Android, .local is usually only
            // resolvable via NsdManager — fall back to this InetAddress which
            // may return the mDNS link-local IPv4 in newer API levels.
            val addresses = InetAddress.getAllByName(hostname)
            addresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { ip ->
                Log.d(TAG, "mDNS resolved $hostname -> $ip")
                ip
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS resolution failed for $hostname: ${e.message}")
            null
        }
    }

    fun clearCache() {
        cache.clear()
        cacheTimestamps.clear()
    }

    companion object {
        private const val TAG = "MdnsResolver"
        private const val CACHE_TTL_MS = 60_000L
    }
}
