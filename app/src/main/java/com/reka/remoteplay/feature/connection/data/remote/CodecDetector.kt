package com.reka.remoteplay.feature.connection.data.remote

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import com.reka.remoteplay.core.model.ClientCodecCapability
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CodecDetector @Inject constructor() {

    fun detectCapabilities(displayMetrics: DisplayMetrics): ClientCodecCapability {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        val hevcFormat = MediaFormat.createVideoFormat("video/hevc", 1920, 1080)
        val supportsHevc = codecList.findDecoderForFormat(hevcFormat) != null

        val supportedCodecs = buildList {
            if (supportsHevc) add("H265")
            add("H264") // Always supported on Android
        }

        return ClientCodecCapability(
            supportedCodecs = supportedCodecs,
            preferredCodec = if (supportsHevc) "H265" else "H264",
            supportsHevc = supportsHevc,
            supportsVP9 = false,
            supportsVP8 = false,
            deviceModel = Build.MODEL,
            apiLevel = Build.VERSION.SDK_INT,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels
        )
    }
}
