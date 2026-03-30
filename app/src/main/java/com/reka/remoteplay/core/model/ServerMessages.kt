package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Phase 1: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class HardwareInfoMessage(
    @Json(name = "type") val type: String = "hardware_info",
    @Json(name = "device") val device: DeviceInfo = DeviceInfo(),
    @Json(name = "encoder") val encoder: EncoderInfo = EncoderInfo(),
    @Json(name = "monitors") val monitors: List<MonitorInfoDto> = emptyList(),
    @Json(name = "maxQualityHeight") val maxQualityHeight: Int = 1440
)

@JsonClass(generateAdapter = true)
data class SpeedTestStartMessage(
    @Json(name = "type") val type: String = "speedtest_start",
    @Json(name = "direction") val direction: String = "download",
    @Json(name = "chunkSize") val chunkSize: Int = 65536,
    @Json(name = "durationMs") val durationMs: Int = 2000
)

@JsonClass(generateAdapter = true)
data class NetworkInfoMessage(
    @Json(name = "type") val type: String = "network_info",
    @Json(name = "pingMs") val pingMs: Double = 0.0,
    @Json(name = "jitterMs") val jitterMs: Double = 0.0,
    @Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @Json(name = "connectionType") val connectionType: String = "Unknown"
)

@JsonClass(generateAdapter = true)
data class SuggestedConfigMessage(
    @Json(name = "type") val type: String = "suggested_config",
    @Json(name = "monitors") val monitors: Int = 3,
    @Json(name = "resolution") val resolution: ResolutionDto = ResolutionDto(),
    @Json(name = "bitrateKbps") val bitrateKbps: Int = 20000,
    @Json(name = "fps") val fps: Int = 60,
    @Json(name = "refreshRate") val refreshRate: Int = 60,
    @Json(name = "reason") val reason: String = "",
    @Json(name = "selectedCodec") val selectedCodec: String = "H264",
    @Json(name = "connectionType") val connectionType: String = "Unknown",
    @Json(name = "networkInfo") val networkInfo: NetworkInfoDto? = null,
    @Json(name = "maxNativeHeight") val maxNativeHeight: Int = 0
)

// ==================== Phase 2: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class ConfigProgressMessage(
    @Json(name = "type") val type: String = "config_progress",
    @Json(name = "step") val step: String = "",
    @Json(name = "progress") val progress: Int = 0,
    @Json(name = "message") val message: String = ""
)

@JsonClass(generateAdapter = true)
data class ConfigCompleteMessage(
    @Json(name = "type") val type: String = "config_complete",
    @Json(name = "monitors") val monitors: List<MonitorInfoDto> = emptyList(),
    @Json(name = "captureReady") val captureReady: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OfferMessage(
    @Json(name = "type") val type: String = "offer",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AnswerMessage(
    @Json(name = "type") val type: String = "answer",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class CandidateMessage(
    @Json(name = "type") val type: String = "candidate",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "candidate") val candidate: String = ""
)

@JsonClass(generateAdapter = true)
data class EndOfCandidatesMessage(
    @Json(name = "type") val type: String = "end_of_candidates",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class IceReadyMessage(
    @Json(name = "type") val type: String = "ice_ready",
    @Json(name = "monitorCount") val monitorCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class ReconnectRequestMessage(
    @Json(name = "type") val type: String = "reconnect_request",
    @Json(name = "reason") val reason: String = "",
    @Json(name = "suggestedCodec") val suggestedCodec: String? = null
)

// ==================== Phase 3: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class StreamingStartedMessage(
    @Json(name = "type") val type: String = "streaming_started",
    @Json(name = "timestamp") val timestamp: Long = 0
)

@JsonClass(generateAdapter = true)
data class ConfigUpdatedMessage(
    @Json(name = "type") val type: String = "config_updated",
    @Json(name = "fps") val fps: Int = 0,
    @Json(name = "bitrateKbps") val bitrateKbps: Int = 0,
    @Json(name = "resolutionHeight") val resolutionHeight: Int = 0,
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "message") val message: String = ""
)

@JsonClass(generateAdapter = true)
data class ForegroundMonitorMessage(
    @Json(name = "type") val type: String = "foreground_monitor",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class CodecChangedMessage(
    @Json(name = "type") val type: String = "codec_changed",
    @Json(name = "actualCodec") val actualCodec: String = ""
)

@JsonClass(generateAdapter = true)
data class CaretPositionMessage(
    @Json(name = "type") val type: String = "caret_position",
    @Json(name = "u") val u: Float = 0.5f,
    @Json(name = "v") val v: Float = 0.5f
)

// ==================== Cursor Messages ====================

@JsonClass(generateAdapter = true)
data class CursorPositionMessage(
    @Json(name = "type") val type: String = "cursor_position",
    @Json(name = "monitorIndex") val monitorIndex: Int = -1,
    @Json(name = "u") val u: Float = 0f,
    @Json(name = "v") val v: Float = 0f,
    @Json(name = "visible") val visible: Boolean = true,
    @Json(name = "cursorType") val cursorType: Int = 1,
    @Json(name = "cursorId") val cursorId: Long = 0
)

@JsonClass(generateAdapter = true)
data class CursorImageMessage(
    @Json(name = "type") val type: String = "cursor_image",
    @Json(name = "cursorId") val cursorId: Long = 0,
    @Json(name = "cursorType") val cursorType: Int = 0,
    @Json(name = "width") val width: Int = 0,
    @Json(name = "height") val height: Int = 0,
    @Json(name = "hotspotX") val hotspotX: Int = 0,
    @Json(name = "hotspotY") val hotspotY: Int = 0,
    @Json(name = "imageBase64") val imageBase64: String = ""
)

// ==================== Adaptive Messages ====================

@JsonClass(generateAdapter = true)
data class FpsAdjustedMessage(
    @Json(name = "type") val type: String = "fps_adjusted",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "targetFps") val targetFps: Int = 0
)

@JsonClass(generateAdapter = true)
data class BitrateAdjustedMessage(
    @Json(name = "type") val type: String = "bitrate_adjusted",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "bitrateKbps") val bitrateKbps: Int = 0,
    @Json(name = "reason") val reason: String = ""
)

@JsonClass(generateAdapter = true)
data class QualityRecommendationMessage(
    @Json(name = "type") val type: String = "quality_recommendation",
    @Json(name = "recommendation") val recommendation: String = "",
    @Json(name = "reason") val reason: String = ""
)

// ==================== Common ====================

@JsonClass(generateAdapter = true)
data class ErrorMessage(
    @Json(name = "type") val type: String = "error",
    @Json(name = "phase") val phase: Int = 0,
    @Json(name = "code") val code: String = "",
    @Json(name = "message") val message: String = ""
)
