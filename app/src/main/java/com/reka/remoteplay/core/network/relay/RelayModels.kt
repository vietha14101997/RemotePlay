package com.reka.remoteplay.core.network.relay

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Fallback relay when none is saved yet (user's own VPS, coturn co-located). */
const val DEFAULT_RELAY_URL = "http://180.93.2.132:8443"

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

/**
 * WAN P2P telemetry snapshot — contract v1, see
 * `plans/260715-2315-wan-p2p-turn-quality-hardening/telemetry-snapshot-contract-v1.md`.
 * Fire-and-forget, no-auth, no PII: `session_id` is an opaque per-connection grouping token
 * (never a network address/SDP/credential). Identity fields (schema_version..sequence) are
 * always present; selected-pair fields are present for `snapshot`/`path_transition` events;
 * QoE fields are entirely optional and null when the underlying stat isn't available — e.g.
 * this app's video path rides a DataChannel rather than an RTP receiver, so codec/resolution/
 * fps/qp/frame_drops aren't exposed by libwebrtc getStats and are left null rather than
 * fabricated.
 */
@JsonClass(generateAdapter = true)
data class ConnectionTelemetryRequest(
    @param:Json(name = "schema_version") val schemaVersion: Int = 1,
    val source: String = "android",
    /** snapshot | path_transition | ws_safe_mode_enter | ws_safe_mode_exit */
    val event: String,
    @param:Json(name = "session_id") val sessionId: String,
    /** main | video */
    @param:Json(name = "pc_role") val pcRole: String,
    @param:Json(name = "monitor_index") val monitorIndex: Int,
    val generation: Int,
    val sequence: Int,
    // --- selected candidate pair (present on snapshot/path_transition) ---
    @param:Json(name = "local_candidate_type") val localCandidateType: String? = null,
    @param:Json(name = "remote_candidate_type") val remoteCandidateType: String? = null,
    @param:Json(name = "address_family") val addressFamily: String? = null,
    val protocol: String? = null,
    @param:Json(name = "relay_protocol") val relayProtocol: String? = null,
    @param:Json(name = "path_class") val pathClass: String? = null,
    // --- QoE metrics (nullable; omitted when unavailable) ---
    @param:Json(name = "rtt_ms") val rttMs: Int? = null,
    @param:Json(name = "jitter_ms") val jitterMs: Int? = null,
    @param:Json(name = "loss_pct") val lossPct: Float? = null,
    @param:Json(name = "send_bitrate_kbps") val sendBitrateKbps: Int? = null,
    @param:Json(name = "available_bitrate_kbps") val availableBitrateKbps: Int? = null,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val qp: Int? = null,
    @param:Json(name = "frame_drops") val frameDrops: Int? = null,
    @param:Json(name = "ttff_ms") val ttffMs: Int? = null,
    @param:Json(name = "freeze_count") val freezeCount: Int? = null,
    @param:Json(name = "freeze_ms_total") val freezeMsTotal: Int? = null
)

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
