package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Phase 1: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class HardwareInfoMessage(
    @param:Json(name = "type") val type: String = "hardware_info",
    @param:Json(name = "device") val device: DeviceInfo = DeviceInfo(),
    @param:Json(name = "encoder") val encoder: EncoderInfo = EncoderInfo(),
    @param:Json(name = "monitors") val monitors: List<MonitorInfoDto> = emptyList(),
    @param:Json(name = "maxQualityHeight") val maxQualityHeight: Int = 1440
)

@JsonClass(generateAdapter = true)
data class SpeedTestStartMessage(
    @param:Json(name = "type") val type: String = "speedtest_start",
    @param:Json(name = "direction") val direction: String = "download",
    @param:Json(name = "chunkSize") val chunkSize: Int = 65536,
    @param:Json(name = "durationMs") val durationMs: Int = 2000
)

@JsonClass(generateAdapter = true)
data class NetworkInfoMessage(
    @param:Json(name = "type") val type: String = "network_info",
    @param:Json(name = "pingMs") val pingMs: Double = 0.0,
    @param:Json(name = "jitterMs") val jitterMs: Double = 0.0,
    @param:Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @param:Json(name = "connectionType") val connectionType: String = "Unknown"
)

@JsonClass(generateAdapter = true)
data class SuggestedConfigMessage(
    @param:Json(name = "type") val type: String = "suggested_config",
    @param:Json(name = "monitors") val monitors: Int = 3,
    @param:Json(name = "resolution") val resolution: ResolutionDto = ResolutionDto(),
    @param:Json(name = "bitrateKbps") val bitrateKbps: Int = 20000,
    @param:Json(name = "fps") val fps: Int = 60,
    @param:Json(name = "refreshRate") val refreshRate: Int = 60,
    @param:Json(name = "reason") val reason: String = "",
    @param:Json(name = "selectedCodec") val selectedCodec: String = "H264",
    @param:Json(name = "connectionType") val connectionType: String = "Unknown",
    @param:Json(name = "networkInfo") val networkInfo: NetworkInfoDto? = null,
    @param:Json(name = "maxNativeHeight") val maxNativeHeight: Int = 0
)

// ==================== Phase 2: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class ConfigProgressMessage(
    @param:Json(name = "type") val type: String = "config_progress",
    @param:Json(name = "step") val step: String = "",
    @param:Json(name = "progress") val progress: Int = 0,
    @param:Json(name = "message") val message: String = ""
)

@JsonClass(generateAdapter = true)
data class ConfigCompleteMessage(
    @param:Json(name = "type") val type: String = "config_complete",
    @param:Json(name = "monitors") val monitors: List<MonitorInfoDto> = emptyList(),
    @param:Json(name = "captureReady") val captureReady: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OfferMessage(
    @param:Json(name = "type") val type: String = "offer",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AnswerMessage(
    @param:Json(name = "type") val type: String = "answer",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class CandidateMessage(
    @param:Json(name = "type") val type: String = "candidate",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "candidate") val candidate: String = ""
)

@JsonClass(generateAdapter = true)
data class EndOfCandidatesMessage(
    @param:Json(name = "type") val type: String = "end_of_candidates",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class IceReadyMessage(
    @param:Json(name = "type") val type: String = "ice_ready",
    @param:Json(name = "monitorCount") val monitorCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class ReconnectRequestMessage(
    @param:Json(name = "type") val type: String = "reconnect_request",
    @param:Json(name = "reason") val reason: String = "",
    @param:Json(name = "suggestedCodec") val suggestedCodec: String? = null
)

// ==================== Phase 3: Server -> Client ====================

@JsonClass(generateAdapter = true)
data class StreamingStartedMessage(
    @param:Json(name = "type") val type: String = "streaming_started",
    @param:Json(name = "timestamp") val timestamp: Long = 0
)

@JsonClass(generateAdapter = true)
data class ConfigUpdatedMessage(
    @param:Json(name = "type") val type: String = "config_updated",
    @param:Json(name = "fps") val fps: Int = 0,
    @param:Json(name = "bitrateKbps") val bitrateKbps: Int = 0,
    @param:Json(name = "resolutionHeight") val resolutionHeight: Int = 0,
    @param:Json(name = "success") val success: Boolean = false,
    @param:Json(name = "message") val message: String = ""
)

@JsonClass(generateAdapter = true)
data class ForegroundMonitorMessage(
    @param:Json(name = "type") val type: String = "foreground_monitor",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class CodecChangedMessage(
    @param:Json(name = "type") val type: String = "codec_changed",
    @param:Json(name = "actualCodec") val actualCodec: String = ""
)

@JsonClass(generateAdapter = true)
data class CaretPositionMessage(
    @param:Json(name = "type") val type: String = "caret_position",
    @param:Json(name = "u") val u: Float = 0.5f,
    @param:Json(name = "v") val v: Float = 0.5f
)

// ==================== Cursor Messages ====================

@JsonClass(generateAdapter = true)
data class CursorPositionMessage(
    @param:Json(name = "type") val type: String = "cursor_position",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = -1,
    @param:Json(name = "u") val u: Float = 0f,
    @param:Json(name = "v") val v: Float = 0f,
    @param:Json(name = "visible") val visible: Boolean = true,
    @param:Json(name = "cursorType") val cursorType: Int = 1,
    @param:Json(name = "cursorId") val cursorId: Long = 0
)

@JsonClass(generateAdapter = true)
data class CursorImageMessage(
    @param:Json(name = "type") val type: String = "cursor_image",
    @param:Json(name = "cursorId") val cursorId: Long = 0,
    @param:Json(name = "cursorType") val cursorType: Int = 0,
    @param:Json(name = "width") val width: Int = 0,
    @param:Json(name = "height") val height: Int = 0,
    @param:Json(name = "hotspotX") val hotspotX: Int = 0,
    @param:Json(name = "hotspotY") val hotspotY: Int = 0,
    @param:Json(name = "imageBase64") val imageBase64: String = ""
)

// ==================== Adaptive Messages ====================

@JsonClass(generateAdapter = true)
data class FpsAdjustedMessage(
    @param:Json(name = "type") val type: String = "fps_adjusted",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "targetFps") val targetFps: Int = 0
)

@JsonClass(generateAdapter = true)
data class BitrateAdjustedMessage(
    @param:Json(name = "type") val type: String = "bitrate_adjusted",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "bitrateKbps") val bitrateKbps: Int = 0,
    @param:Json(name = "reason") val reason: String = ""
)

@JsonClass(generateAdapter = true)
data class QualityRecommendationMessage(
    @param:Json(name = "type") val type: String = "quality_recommendation",
    @param:Json(name = "recommendation") val recommendation: String = "",
    @param:Json(name = "reason") val reason: String = ""
)

// ==================== Common ====================

@JsonClass(generateAdapter = true)
data class ErrorMessage(
    @param:Json(name = "type") val type: String = "error",
    @param:Json(name = "phase") val phase: Int = 0,
    @param:Json(name = "code") val code: String = "",
    @param:Json(name = "message") val message: String = ""
)
