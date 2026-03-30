package com.reka.remoteplay.core.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VideoOfferMessage(
    @param:Json(name = "type") val type: String = "video_offer",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class VideoAnswerMessage(
    @param:Json(name = "type") val type: String = "video_answer",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class VideoCandidateMessage(
    @param:Json(name = "type") val type: String = "video_candidate",
    @param:Json(name = "monitorIndex") val monitorIndex: Int = 0,
    @param:Json(name = "candidate") val candidate: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioOfferMessage(
    @param:Json(name = "type") val type: String = "audio_offer",
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioAnswerMessage(
    @param:Json(name = "type") val type: String = "audio_answer",
    @param:Json(name = "sdp") val sdp: String = ""
)

@JsonClass(generateAdapter = true)
data class AudioCandidateMessage(
    @param:Json(name = "type") val type: String = "audio_candidate",
    @param:Json(name = "candidate") val candidate: String = ""
)
