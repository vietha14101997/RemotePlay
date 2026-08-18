package com.reka.remoteplay.feature.connection.presentation

/**
 * Snapshot of WebRTC connection diagnostics for UI display.
 * Source: [com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager].
 */
data class ConnectionDiagnostics(
    val connectionType: String,
    val hostCount: Int,
    val srflxCount: Int,
    val relayCount: Int,
    val prflxCount: Int,
    val gatherDurationMs: Long
) {
    companion object {
        val Empty = ConnectionDiagnostics(
            connectionType = "unknown",
            hostCount = 0,
            srflxCount = 0,
            relayCount = 0,
            prflxCount = 0,
            gatherDurationMs = 0L
        )
    }
}
