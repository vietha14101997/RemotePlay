package com.reka.remoteplay.core.debug

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Tiny SharedPreferences-backed debug flags for field testing (P0 forced-path baseline).
 *
 * Synchronous by design (unlike the app's DataStore prefs) so the WebRTC PeerConnection
 * setup can read a flag inline without threading a Flow into the media path.
 *
 * Fail-closed: every flag is a hard no-op on a non-debuggable (release-signed) build —
 * the debuggable check lives here, so no release build can force anything regardless of
 * a stale persisted value. Mirrors [com.reka.remoteplay...WebRtcManager.forceRelayOnlyDebug].
 */
object DebugFlags {
    private const val PREFS = "debug_flags"
    private const val KEY_FORCE_RELAY = "force_relay_only"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** True only when forced-relay is enabled AND this is a debuggable build. */
    fun forceRelayOnly(context: Context): Boolean =
        isDebuggable(context) && prefs(context).getBoolean(KEY_FORCE_RELAY, false)

    /** Flip the flag (no-op on release builds); returns the effective new value. */
    fun toggleForceRelayOnly(context: Context): Boolean {
        if (!isDebuggable(context)) return false
        val next = !prefs(context).getBoolean(KEY_FORCE_RELAY, false)
        prefs(context).edit().putBoolean(KEY_FORCE_RELAY, next).apply()
        return next
    }
}
