package com.reka.remoteplay.core.network.relay

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginRequest(val email: String, val password: String, @param:Json(name = "device_name") val deviceName: String)

@JsonClass(generateAdapter = true)
data class RegisterRequest(val email: String, val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class TokenResponse(
    @param:Json(name = "access_token") val accessToken: String,
    @param:Json(name = "refresh_token") val refreshToken: String,
    @param:Json(name = "expires_in") val expiresIn: Int
)

@JsonClass(generateAdapter = true)
data class RegisterResponse(@param:Json(name = "user_id") val userId: String, val message: String)

@JsonClass(generateAdapter = true)
data class RefreshRequest(@param:Json(name = "refresh_token") val refreshToken: String)

@JsonClass(generateAdapter = true)
data class DevicesResponse(val devices: List<RelayDevice>)

@JsonClass(generateAdapter = true)
data class RelayDevice(
    val id: String,
    @param:Json(name = "device_name") val deviceName: String,
    @param:Json(name = "device_type") val deviceType: String,
    val online: Boolean,
    @param:Json(name = "last_seen_at") val lastSeenAt: String? = null,
    @param:Json(name = "room_id") val roomId: String? = null,
    @param:Json(name = "display_id") val displayId: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateSessionRequest(@param:Json(name = "device_id") val deviceId: String)

@JsonClass(generateAdapter = true)
data class SessionResponse(@param:Json(name = "session_id") val sessionId: String, val status: String)

@JsonClass(generateAdapter = true)
data class IceServersResponse(
    @param:Json(name = "ice_servers") val iceServers: List<IceServerConfig>,
    val ttl: Int
)

@JsonClass(generateAdapter = true)
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@JsonClass(generateAdapter = true)
data class ErrorResponse(val error: String)

@JsonClass(generateAdapter = true)
data class GuestSessionRequest(
    @param:Json(name = "device_id") val deviceId: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RoomJoinRequest(
    @param:Json(name = "room_id") val roomId: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RoomJoinResponse(
    @param:Json(name = "room_id") val roomId: String,
    val state: String,
    val role: String,
    @param:Json(name = "client_id") val clientId: String
)
