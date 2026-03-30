package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Phase 1: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class HardwareInfoAckMessage(
    @param:Json(name = "type") val type: String = "hardware_info_ack",
    @param:Json(name = "clientCodecs") val clientCodecs: ClientCodecCapability? = null,
    @param:Json(name = "perTrackPc") val perTrackPc: Boolean = true
)

@JsonClass(generateAdapter = true)
data class SpeedTestRequestMessage(
    @param:Json(name = "type") val type: String = "speedtest_request",
    @param:Json(name = "direction") val direction: String = "download",
    @param:Json(name = "durationMs") val durationMs: Int = 2000
)

@JsonClass(generateAdapter = true)
data class SpeedTestResultMessage(
    @param:Json(name = "type") val type: String = "speedtest_result",
    @param:Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @param:Json(name = "pingMs") val pingMs: Double = 0.0,
    @param:Json(name = "jitterMs") val jitterMs: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class ProceedMessage(
    @param:Json(name = "type") val type: String = "proceed",
    @param:Json(name = "phase") val phase: Int = 2
)

// ==================== Phase 2: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class DisplayConfigMessage(
    @param:Json(name = "type") val type: String = "display_config",
    @param:Json(name = "monitors") val monitors: Int = 3,
    @param:Json(name = "resolution") val resolution: ResolutionDto = ResolutionDto(),
    @param:Json(name = "refreshRate") val refreshRate: Int = 60,
    @param:Json(name = "bitrateKbps") val bitrateKbps: Int = 20000,
    @param:Json(name = "fps") val fps: Int = 60,
    @param:Json(name = "preferGpu") val preferGpu: String? = null,
    @param:Json(name = "monitorType") val monitorType: String = "standard",
    @param:Json(name = "isUsbMode") val isUsbMode: Boolean = false,
    @param:Json(name = "windowsScale") val windowsScale: Int = 125
)

// ==================== Phase 3: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class StartStreamingMessage(
    @param:Json(name = "type") val type: String = "start_streaming"
)

@JsonClass(generateAdapter = true)
data class StopStreamingMessage(
    @param:Json(name = "type") val type: String = "stop_streaming"
)

@JsonClass(generateAdapter = true)
data class PauseStreamingMessage(
    @param:Json(name = "type") val type: String = "pause_streaming"
)

@JsonClass(generateAdapter = true)
data class ResumeStreamingMessage(
    @param:Json(name = "type") val type: String = "resume_streaming"
)

@JsonClass(generateAdapter = true)
data class PauseMonitorMessage(
    @param:Json(name = "type") val type: String = "pause_monitor",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class ResumeMonitorMessage(
    @param:Json(name = "type") val type: String = "resume_monitor",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class DecoderReadyMessage(
    @param:Json(name = "type") val type: String = "decoder_ready",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class UpdateConfigMessage(
    @param:Json(name = "type") val type: String = "update_config",
    @param:Json(name = "fps") val fps: Int? = null,
    @param:Json(name = "qualityPreset") val qualityPreset: String? = null,
    @param:Json(name = "screenWidth") val screenWidth: Int? = null,
    @param:Json(name = "screenHeight") val screenHeight: Int? = null,
)

/** M2: type-safe replacement for the raw JSON string previously used in StreamingViewModel. */
@JsonClass(generateAdapter = true)
data class SetQualityMessage(
    @param:Json(name = "type") val type: String = "set_quality",
    @param:Json(name = "quality") val quality: String
)

// ==================== Feedback: Client -> Server ====================

@JsonClass(generateAdapter = true)
data class FpsFeedbackMessage(
    @param:Json(name = "type") val type: String = "fps_feedback",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "effectiveFps") val effectiveFps: Float = 0f,
    @param:Json(name = "renderedFrames") val renderedFrames: Int = 0,
    @param:Json(name = "totalFrames") val totalFrames: Long = 0,
    @param:Json(name = "droppedFrames") val droppedFrames: Int = 0
)

@JsonClass(generateAdapter = true)
data class QualityFeedbackMessage(
    @param:Json(name = "type") val type: String = "quality_feedback",
    @param:Json(name = "timestamp") val timestamp: Long = 0,
    @param:Json(name = "rttMs") val rttMs: Float = 0f,
    @param:Json(name = "avgRttMs") val avgRttMs: Float = 0f,
    @param:Json(name = "jitterMs") val jitterMs: Float = 0f,
    @param:Json(name = "packetLossRate") val packetLossRate: Float = 0f,
    @param:Json(name = "avgPacketLossRate") val avgPacketLossRate: Float = 0f,
    @param:Json(name = "effectiveFps") val effectiveFps: Float = 0f,
    @param:Json(name = "targetFps") val targetFps: Float = 0f,
    @param:Json(name = "frameLatencyMs") val frameLatencyMs: Float = 0f,
    @param:Json(name = "bufferStatus") val bufferStatus: String = "healthy",
    @param:Json(name = "connectionHealth") val connectionHealth: Int = 0,
    @param:Json(name = "isWiFi") val isWiFi: Boolean = false,
    @param:Json(name = "monitors") val monitors: List<MonitorFeedback>? = null
)
