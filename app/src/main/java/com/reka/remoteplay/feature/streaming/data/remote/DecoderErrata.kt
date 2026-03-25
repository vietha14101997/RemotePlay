package com.reka.remoteplay.feature.streaming.data.remote

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Device-specific decoder workarounds, inspired by Moonlight-android's errata system.
 *
 * Some hardware decoders ignore KEY_LOW_LATENCY or buffer frames internally.
 * This database provides per-chipset overrides to minimize decode latency.
 */
object DecoderErrata {

    private const val TAG = "DecoderErrata"

    data class DeviceConfig(
        /** Force max_dec_frame_buffering to this value. Null = don't override. */
        val forceMaxDecFrameBuffering: Int? = null,
        /** If true, don't set KEY_LOW_LATENCY (some decoders crash with it). */
        val skipLowLatencyFlag: Boolean = false,
        /** Prefer this alternate decoder name if available. */
        val preferAlternateDecoder: String? = null,
        /** Human-readable notes for debugging. */
        val notes: String = ""
    )

    // Key = substring match against decoder name (lowercase) or Build.HARDWARE (lowercase)
    private val errata = mapOf(
        "mt" to DeviceConfig(
            forceMaxDecFrameBuffering = 1,
            notes = "MediaTek decoders often ignore KEY_LOW_LATENCY, force single-frame buffer"
        ),
        "tegra" to DeviceConfig(
            forceMaxDecFrameBuffering = 1,
            notes = "Tegra shows ~30ms baseline per-frame; force single-frame buffer"
        ),
        "kirin" to DeviceConfig(
            forceMaxDecFrameBuffering = 1,
            notes = "Older Kirin decoders buffer multiple frames"
        )
        // Snapdragon/Adreno and Exynos generally handle KEY_LOW_LATENCY correctly
    )

    /** Look up device-specific config for the given decoder. */
    fun getConfig(decoderName: String): DeviceConfig {
        val lowerDecoder = decoderName.lowercase()
        val lowerHardware = Build.HARDWARE.lowercase()

        val key = errata.keys.find { prefix ->
            lowerDecoder.contains(prefix) || lowerHardware.contains(prefix)
        }
        val config = key?.let { errata[it] } ?: DeviceConfig()

        if (key != null) {
            Log.i(TAG, "Errata match: key=$key, decoder=$decoderName, hw=${Build.HARDWARE}, config=$config")
        }
        return config
    }

    /** Check if the given decoder supports FEATURE_LowLatency. */
    fun supportsLowLatency(decoderName: String, mimeType: String): Boolean {
        return try {
            val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos.find { it.name == decoderName }
            val capabilities = codecInfo?.getCapabilitiesForType(mimeType)
            val supported = capabilities?.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
            ) ?: false
            Log.i(TAG, "[$decoderName] FEATURE_LowLatency=$supported")
            supported
        } catch (_: Exception) {
            false
        }
    }
}
