package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VideoOfferMessage(
    @Json(name = "type") val type: String = "video_offer",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class VideoAnswerMessage(
    @Json(name = "type") val type: String = "video_answer",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class VideoCandidateMessage(
    @Json(name = "type") val type: String = "video_candidate",
    @Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @Json(name = "candidate") val candidate: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioOfferMessage(
    @Json(name = "type") val type: String = "audio_offer",
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioAnswerMessage(
    @Json(name = "type") val type: String = "audio_answer",
    @Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioCandidateMessage(
    @Json(name = "type") val type: String = "audio_candidate",
    @Json(name = "candidate") val candidate: String = ""
)
