package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Hardware Info DTOs ====================

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    @param:Json(name = "name") val name: String = "",
    @param:Json(name = "processor") val processor: String = "",
    @param:Json(name = "gpu") val gpu: String = "",
    @param:Json(name = "gpuVramGB") val gpuVramGB: Int = 0,
    @param:Json(name = "ramGB") val ramGB: Int = 0,
    @param:Json(name = "os") val os: String = ""
)

@JsonClass(generateAdapter = true)
data class EncoderInfo(
    @param:Json(name = "type") val type: String = "Unknown",
    @param:Json(name = "hwAccel") val hwAccel: Boolean = false,
    @param:Json(name = "maxWidth") val maxWidth: Int = 4096,
    @param:Json(name = "maxHeight") val maxHeight: Int = 4096,
    @param:Json(name = "maxBitrateKbps") val maxBitrateKbps: Int = 100000,
    @param:Json(name = "supportedCodecs") val supportedCodecs: List<String> = listOf("H264", "VP9", "VP8"),
    @param:Json(name = "preferredCodec") val preferredCodec: String = "H264",
    @param:Json(name = "supportsHevc") val supportsHevc: Boolean = false,
    @param:Json(name = "supportsVP9") val supportsVP9: Boolean = true,
    @param:Json(name = "supportsVP8") val supportsVP8: Boolean = true
)

@JsonClass(generateAdapter = true)
data class MonitorInfoDto(
    @param:Json(name = "id") val id: Int = 0,
    @param:Json(name = "name") val name: String = "",
    @param:Json(name = "width") val width: Int = 0,
    @param:Json(name = "height") val height: Int = 0,
    @param:Json(name = "refreshRate") val refreshRate: Int = 60,
    @param:Json(name = "isVirtual") val isVirtual: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ResolutionDto(
    @param:Json(name = "w") val width: Int = 1920,
    @param:Json(name = "h") val height: Int = 1080
)

@JsonClass(generateAdapter = true)
data class NetworkInfoDto(
    @param:Json(name = "pingMs") val pingMs: Double = 0.0,
    @param:Json(name = "jitterMs") val jitterMs: Double = 0.0,
    @param:Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @param:Json(name = "isUsbMode") val isUsbMode: Boolean = false,
    @param:Json(name = "usbLatencyMs") val usbLatencyMs: Double = 0.0,
    @param:Json(name = "usbVersion") val usbVersion: String? = null,
    @param:Json(name = "usbEstimatedBandwidthMbps") val usbEstimatedBandwidthMbps: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class ClientCodecCapability(
    @param:Json(name = "supportedCodecs") val supportedCodecs: List<String> = emptyList(),
    @param:Json(name = "preferredCodec") val preferredCodec: String = "H264",
    @param:Json(name = "supportsHevc") val supportsHevc: Boolean = false,
    @param:Json(name = "supportsVP9") val supportsVP9: Boolean = false,
    @param:Json(name = "supportsVP8") val supportsVP8: Boolean = false,
    @param:Json(name = "deviceModel") val deviceModel: String = "",
    @param:Json(name = "apiLevel") val apiLevel: Int = 0,
    @param:Json(name = "screenWidth") val screenWidth: Int = 0,
    @param:Json(name = "screenHeight") val screenHeight: Int = 0
)

@JsonClass(generateAdapter = true)
data class MonitorFeedback(
    @param:Json(name = "index") val index: Int = 0,
    @param:Json(name = "renderedFrames") val renderedFrames: Int = 0,
    @param:Json(name = "realFrames") val realFrames: Int = 0,
    @param:Json(name = "droppedFrames") val droppedFrames: Int = 0,
    @param:Json(name = "texturePtrWorking") val texturePtrWorking: Boolean = true
)

// ==================== Cursor Types ====================

enum class CursorType(val value: Int) {
    Unknown(0),
    Arrow(1),
    IBeam(2),
    Wait(3),
    Cross(4),
    SizeNWSE(5),
    SizeNESW(6),
    SizeWE(7),
    SizeNS(8),
    SizeAll(9),
    No(10),
    Hand(11),
    AppStarting(12),
    Help(13),
    UpArrow(14),
    Custom(99);

    companion object {
        fun fromValue(value: Int): CursorType =
            entries.find { it.value == value } ?: Unknown
    }
}
