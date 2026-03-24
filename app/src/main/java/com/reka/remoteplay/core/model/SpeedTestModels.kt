package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SpeedTestEndMessage(
    @Json(name = "type") val type: String = "speedtest_end",
    @Json(name = "direction") val direction: String = "download",
    @Json(name = "totalBytes") val totalBytes: Long = 0,
    @Json(name = "durationMs") val durationMs: Long = 0
)

data class SpeedTestResult(
    val bandwidthMbps: Double = 0.0,
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0
)

data class StreamConfig(
    val monitors: Int = 3,
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val bitrateKbps: Int = 20000,
    val fps: Int = 60,
    val refreshRate: Int = 60,
    val codec: String = "H264",
    val connectionType: String = "Unknown",
    val networkInfo: NetworkInfoDto? = null,
    val maxNativeHeight: Int = 1080,
    val reason: String = ""
)
