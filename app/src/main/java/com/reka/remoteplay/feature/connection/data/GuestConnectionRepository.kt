package com.reka.remoteplay.feature.connection.data

import android.util.Log
import com.reka.remoteplay.core.network.relay.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuestConnectionRepository @Inject constructor(
    private val relayApi: RelayApi,
    private val tokenManager: TokenManager
) {
    fun getRelayUrl(): String {
        return tokenManager.relayUrl ?: DEFAULT_RELAY_URL
    }

    suspend fun fetchIceServers(): List<IceServerConfig> {
        return try {
            val resp = relayApi.getIceServersPublic()
            if (resp.isSuccessful) {
                resp.body()?.iceServers ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch ICE servers: ${e.message}")
            emptyList()
        }
    }

    /**
     * Join a room by ID + password. Returns room info including state and role.
     */
    suspend fun joinRoom(roomId: String, password: String): Result<RoomJoinResponse> {
        return try {
            val normalizedId = roomId.replace("-", "").replace(" ", "").uppercase()
            val resp = relayApi.joinRoom(RoomJoinRequest(normalizedId, password))
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                val message = when (resp.code()) {
                    401 -> "Invalid password"
                    404 -> "Room not found"
                    409 -> "Room is full"
                    429 -> "Too many attempts, try again later"
                    else -> "Connection failed"
                }
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Keep old method for backward compatibility
    suspend fun connectAsGuest(deviceId: String, password: String): Result<SessionResponse> {
        return try {
            val normalizedId = deviceId.replace("-", "").replace(" ", "").uppercase()
            val resp = relayApi.createGuestSession(GuestSessionRequest(normalizedId, password))
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                val message = when (resp.code()) {
                    401 -> "Invalid password"
                    404 -> "Device not found or offline"
                    409 -> "Server is busy"
                    429 -> "Too many attempts, try again later"
                    else -> "Connection failed"
                }
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GuestConnectionRepo"
        const val DEFAULT_RELAY_URL = "http://34.87.150.141:8443"
    }
}
