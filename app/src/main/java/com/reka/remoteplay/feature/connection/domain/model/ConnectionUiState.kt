package com.reka.remoteplay.feature.connection.domain.model

import com.reka.remoteplay.core.model.HardwareInfoMessage
import com.reka.remoteplay.core.model.SuggestedConfigMessage
import com.reka.remoteplay.feature.connection.data.local.SavedServer

data class ConnectionUiState(
    val hostInput: String = "",
    val portInput: String = "8288",
    val isConnecting: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val savedServers: List<SavedServer> = emptyList(),
    val usbMode: Boolean = false,
    val serverInfo: HardwareInfoMessage? = null,
    val suggestedConfig: SuggestedConfigMessage? = null,
    val speedTestProgress: Float = 0f,
    val speedTestStatus: String = "",
    val rttMs: Float = 0f,
    val errorMessage: String? = null
)
