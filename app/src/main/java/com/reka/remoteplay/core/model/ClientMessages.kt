package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Phase 1: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class HardwareInfoAckMessage(
    @Json(name = "type") val type: String = "hardware_info_ack",
    @Json(name = "clientCodecs") val clientCodecs: ClientCodecCapability? = null,
    @Json(name = "perTrackPc") val perTrackPc: Boolean = true
)

@JsonClass(generateAdapter = true)
data class SpeedTestRequestMessage(
    @Json(name = "type") val type: String = "speedtest_request",
    @Json(name = "direction") val direction: String = "download",
    @Json(name = "durationMs") val durationMs: Int = 2000
)

@JsonClass(generateAdapter = true)
data class SpeedTestResultMessage(
    @Json(name = "type") val type: String = "speedtest_result",
    @Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @Json(name = "pingMs") val pingMs: Double = 0.0,
    @Json(name = "jitterMs") val jitterMs: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class ProceedMessage(
    @Json(name = "type") val type: String = "proceed",
    @Json(name = "phase") val phase: Int = 2
)

// ==================== Phase 2: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class DisplayConfigMessage(
    @Json(name = "type") val type: String = "display_config",
    @Json(name = "monitors") val monitors: Int = 3,
    @Json(name = "resolution") val resolution: ResolutionDto = ResolutionDto(),
    @Json(name = "refreshRate") val refreshRate: Int = 60,
    @Json(name = "bitrateKbps") val bitrateKbps: Int = 20000,
    @Json(name = "fps") val fps: Int = 60,
    @Json(name = "preferGpu") val preferGpu: String? = null,
    @Json(name = "monitorType") val monitorType: String = "standard",
    @Json(name = "isUsbMode") val isUsbMode: Boolean = false,
    @Json(name = "windowsScale") val windowsScale: Int = 125
)

// ==================== Phase 3: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class StartStreamingMessage(
    @Json(name = "type") val type: String = "start_streaming"
)

@JsonClass(generateAdapter = true)
data class StopStreamingMessage(
    @Json(name = "type") val type: String = "stop_streaming"
)

@JsonClass(generateAdapter = true)
data class PauseStreamingMessage(
    @Json(name = "type") val type: String = "pause_streaming"
)

@JsonClass(generateAdapter = true)
data class ResumeStreamingMessage(
    @Json(name = "type") val type: String = "resume_streaming"
)

@JsonClass(generateAdapter = true)
data class PauseMonitorMessage(
    @Json(name = "type") val type: String = "pause_monitor",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class ResumeMonitorMessage(
    @Json(name = "type") val type: String = "resume_monitor",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class DecoderReadyMessage(
    @Json(name = "type") val type: String = "decoder_ready",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class UpdateConfigMessage(
    @Json(name = "type") val type: String = "update_config",
    @Json(name = "fps") val fps: Int? = null,
    @Json(name = "qualityPreset") val qualityPreset: String? = null,
    @Json(name = "screenWidth") val screenWidth: Int? = null,
    @Json(name = "screenHeight") val screenHeight: Int? = null,
)

// ==================== Feedback: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class FpsFeedbackMessage(
    @Json(name = "type") val type: String = "fps_feedback",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "effectiveFps") val effectiveFps: Float = 0f,
    @Json(name = "renderedFrames") val renderedFrames: Int = 0,
    @Json(name = "totalFrames") val totalFrames: Long = 0,
    @Json(name = "droppedFrames") val droppedFrames: Int = 0
)

@JsonClass(generateAdapter = true)
data class QualityFeedbackMessage(
    @Json(name = "type") val type: String = "quality_feedback",
    @Json(name = "timestamp") val timestamp: Long = 0,
    @Json(name = "rttMs") val rttMs: Float = 0f,
    @Json(name = "avgRttMs") val avgRttMs: Float = 0f,
    @Json(name = "jitterMs") val jitterMs: Float = 0f,
    @Json(name = "packetLossRate") val packetLossRate: Float = 0f,
    @Json(name = "avgPacketLossRate") val avgPacketLossRate: Float = 0f,
    @Json(name = "effectiveFps") val effectiveFps: Float = 0f,
    @Json(name = "targetFps") val targetFps: Float = 0f,
    @Json(name = "frameLatencyMs") val frameLatencyMs: Float = 0f,
    @Json(name = "bufferStatus") val bufferStatus: String = "healthy",
    @Json(name = "connectionHealth") val connectionHealth: Int = 0,
    @Json(name = "isWiFi") val isWiFi: Boolean = false,
    @Json(name = "monitors") val monitors: List<MonitorFeedback>? = null
)
