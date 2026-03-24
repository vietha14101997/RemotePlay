package com.reka.remoteplay.feature.connection.domain.usecase

import android.util.DisplayMetrics
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.data.local.ConnectionPreferences
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.data.remote.PhaseOneHandler
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class ConnectToServerUseCase @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val preferences: ConnectionPreferences,
    private val phaseOneHandler: PhaseOneHandler
) {
    suspend operator fun invoke(
        host: String,
        port: Int,
        isUsb: Boolean,
        displayMetrics: DisplayMetrics,
        scope: CoroutineScope
    ) {
        phaseOneHandler.reset()
        connectionStateRepo.tryTransition(ConnectionState.Connecting)
        preferences.saveServer(SavedServer(host = host, port = port))
        phaseOneHandler.startListening(scope, displayMetrics)
        webSocketClient.connect(host, port, isUsb = isUsb)
    }
}
