package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== Hardware Info DTOs ====================

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    @Json(name = "name") val name: String = "",
    @Json(name = "processor") val processor: String = "",
    @Json(name = "gpu") val gpu: String = "",
    @Json(name = "gpuVramGB") val gpuVramGB: Int = 0,
    @Json(name = "ramGB") val ramGB: Int = 0,
    @Json(name = "os") val os: String = ""
)

@JsonClass(generateAdapter = true)
data class EncoderInfo(
    @Json(name = "type") val type: String = "Unknown",
    @Json(name = "hwAccel") val hwAccel: Boolean = false,
    @Json(name = "maxWidth") val maxWidth: Int = 4096,
    @Json(name = "maxHeight") val maxHeight: Int = 4096,
    @Json(name = "maxBitrateKbps") val maxBitrateKbps: Int = 100000,
    @Json(name = "supportedCodecs") val supportedCodecs: List<String> = listOf("H264", "VP9", "VP8"),
    @Json(name = "preferredCodec") val preferredCodec: String = "H264",
    @Json(name = "supportsHevc") val supportsHevc: Boolean = false,
    @Json(name = "supportsVP9") val supportsVP9: Boolean = true,
    @Json(name = "supportsVP8") val supportsVP8: Boolean = true
)

@JsonClass(generateAdapter = true)
data class MonitorInfoDto(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "name") val name: String = "",
    @Json(name = "width") val width: Int = 0,
    @Json(name = "height") val height: Int = 0,
    @Json(name = "refreshRate") val refreshRate: Int = 60,
    @Json(name = "isVirtual") val isVirtual: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ResolutionDto(
    @Json(name = "w") val width: Int = 1920,
    @Json(name = "h") val height: Int = 1080
)

@JsonClass(generateAdapter = true)
data class NetworkInfoDto(
    @Json(name = "pingMs") val pingMs: Double = 0.0,
    @Json(name = "jitterMs") val jitterMs: Double = 0.0,
    @Json(name = "bandwidthMbps") val bandwidthMbps: Double = 0.0,
    @Json(name = "isUsbMode") val isUsbMode: Boolean = false,
    @Json(name = "usbLatencyMs") val usbLatencyMs: Double = 0.0,
    @Json(name = "usbVersion") val usbVersion: String? = null,
    @Json(name = "usbEstimatedBandwidthMbps") val usbEstimatedBandwidthMbps: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class ClientCodecCapability(
    @Json(name = "supportedCodecs") val supportedCodecs: List<String> = emptyList(),
    @Json(name = "preferredCodec") val preferredCodec: String = "H264",
    @Json(name = "supportsHevc") val supportsHevc: Boolean = false,
    @Json(name = "supportsVP9") val supportsVP9: Boolean = false,
    @Json(name = "supportsVP8") val supportsVP8: Boolean = false,
    @Json(name = "deviceModel") val deviceModel: String = "",
    @Json(name = "apiLevel") val apiLevel: Int = 0,
    @Json(name = "screenWidth") val screenWidth: Int = 0,
    @Json(name = "screenHeight") val screenHeight: Int = 0
)

@JsonClass(generateAdapter = true)
data class MonitorFeedback(
    @Json(name = "index") val index: Int = 0,
    @Json(name = "renderedFrames") val renderedFrames: Int = 0,
    @Json(name = "realFrames") val realFrames: Int = 0,
    @Json(name = "droppedFrames") val droppedFrames: Int = 0,
    @Json(name = "texturePtrWorking") val texturePtrWorking: Boolean = true
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
