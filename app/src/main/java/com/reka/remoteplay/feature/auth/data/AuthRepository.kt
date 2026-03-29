package com.reka.remoteplay.feature.auth.data

import com.reka.remoteplay.core.network.relay.LoginRequest
import com.reka.remoteplay.core.network.relay.RefreshRequest
import com.reka.remoteplay.core.network.relay.RegisterRequest
import com.reka.remoteplay.core.network.relay.RelayApi
import com.reka.remoteplay.core.network.relay.TokenManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val relayApi: RelayApi,
    private val tokenManager: TokenManager
) {
    suspend fun login(relayUrl: String, email: String, password: String): Result<Unit> {
        return try {
            val resp = relayApi.login(LoginRequest(email, password, "RemotePlay Android"))
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                tokenManager.relayUrl = relayUrl
                tokenManager.saveTokens(body.accessToken, body.refreshToken)
                Result.success(Unit)
            } else {
                Result.failure(Exception(resp.errorBody()?.string() ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, username: String, password: String): Result<String> {
        return try {
            val resp = relayApi.register(RegisterRequest(email, username, password))
            if (resp.isSuccessful) {
                Result.success(resp.body()?.userId ?: "")
            } else {
                Result.failure(Exception(resp.errorBody()?.string() ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<Unit> {
        val refresh = tokenManager.refreshToken ?: return Result.failure(Exception("No refresh token"))
        return try {
            val resp = relayApi.refresh(RefreshRequest(refresh))
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                tokenManager.saveTokens(body.accessToken, body.refreshToken)
                Result.success(Unit)
            } else {
                tokenManager.clearTokens()
                Result.failure(Exception("Refresh failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        tokenManager.clearTokens()
    }
}
