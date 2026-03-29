package com.reka.remoteplay.core.network.relay

import retrofit2.Response
import retrofit2.http.*

interface RelayApi {
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<TokenResponse>

    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequest): Response<Unit>

    @GET("devices")
    suspend fun getDevices(@Header("Authorization") token: String): Response<DevicesResponse>

    @POST("sessions/create")
    suspend fun createSession(
        @Header("Authorization") token: String,
        @Body request: CreateSessionRequest
    ): Response<SessionResponse>

    @GET("ice-servers")
    suspend fun getIceServers(@Header("Authorization") token: String): Response<IceServersResponse>

    @POST("sessions/guest")
    suspend fun createGuestSession(@Body request: GuestSessionRequest): Response<SessionResponse>

    @GET("ice-servers-public")
    suspend fun getIceServersPublic(): Response<IceServersResponse>

    @POST("rooms/join")
    suspend fun joinRoom(
        @Body request: RoomJoinRequest,
        @Header("Authorization") token: String = ""
    ): Response<RoomJoinResponse>
}
