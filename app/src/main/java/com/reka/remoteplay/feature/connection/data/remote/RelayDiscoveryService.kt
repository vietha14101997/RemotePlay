package com.reka.remoteplay.feature.connection.data.remote

import com.reka.remoteplay.core.network.relay.RelayApi
import com.reka.remoteplay.core.network.relay.RelayDevice
import com.reka.remoteplay.core.network.relay.TokenManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayDiscoveryService @Inject constructor(
    private val relayApi: RelayApi,
    private val tokenManager: TokenManager
) {
    fun discoverServers(intervalMs: Long = 10_000): Flow<List<RelayDevice>> = flow {
        while (true) {
            if (tokenManager.isLoggedIn.value) {
                try {
                    val resp = relayApi.getDevices(tokenManager.authHeader())
                    if (resp.isSuccessful) {
                        emit(resp.body()?.devices ?: emptyList())
                    }
                } catch (_: Exception) {
                    emit(emptyList())
                }
            }
            delay(intervalMs)
        }
    }
}
