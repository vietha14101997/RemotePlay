package com.reka.remoteplay.core.network

import com.reka.remoteplay.core.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object MessageParser {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter = moshi.adapter(Map::class.java)

    fun getMessageType(rawJson: String): String? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = mapAdapter.fromJson(rawJson) as? Map<String, Any?>
            map?.get("type") as? String
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T> parse(rawJson: String): T? {
        return try {
            moshi.adapter(T::class.java).fromJson(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T> serialize(message: T): String {
        return moshi.adapter(T::class.java).toJson(message)
    }

    fun parseServerMessage(rawJson: String): Any? {
        val type = getMessageType(rawJson) ?: return null
        return when (type) {
            // Phase 1
            "hardware_info" -> parse<HardwareInfoMessage>(rawJson)
            "speedtest_start" -> parse<SpeedTestStartMessage>(rawJson)
            "speedtest_end" -> parse<SpeedTestEndMessage>(rawJson)
            "network_info" -> parse<NetworkInfoMessage>(rawJson)
            "suggested_config" -> parse<SuggestedConfigMessage>(rawJson)
            // Phase 2
            "config_progress" -> parse<ConfigProgressMessage>(rawJson)
            "config_complete" -> parse<ConfigCompleteMessage>(rawJson)
            "offer" -> parse<OfferMessage>(rawJson)
            "answer" -> parse<AnswerMessage>(rawJson)
            "candidate" -> parse<CandidateMessage>(rawJson)
            "end_of_candidates" -> parse<EndOfCandidatesMessage>(rawJson)
            "ice_ready" -> parse<IceReadyMessage>(rawJson)
            "video_offer" -> parse<VideoOfferMessage>(rawJson)
            "audio_offer" -> parse<AudioOfferMessage>(rawJson)
            "reconnect_request" -> parse<ReconnectRequestMessage>(rawJson)
            // Phase 3
            "streaming_started" -> parse<StreamingStartedMessage>(rawJson)
            "config_updated" -> parse<ConfigUpdatedMessage>(rawJson)
            "cursor_position" -> parse<CursorPositionMessage>(rawJson)
            "cursor_image" -> parse<CursorImageMessage>(rawJson)
            "fps_adjusted" -> parse<FpsAdjustedMessage>(rawJson)
            "bitrate_adjusted" -> parse<BitrateAdjustedMessage>(rawJson)
            "quality_recommendation" -> parse<QualityRecommendationMessage>(rawJson)
            "foreground_monitor" -> parse<ForegroundMonitorMessage>(rawJson)
            "codec_changed" -> parse<CodecChangedMessage>(rawJson)
            "caret_position" -> parse<CaretPositionMessage>(rawJson)
            // Common
            "error" -> parse<ErrorMessage>(rawJson)
            else -> null
        }
    }
}
