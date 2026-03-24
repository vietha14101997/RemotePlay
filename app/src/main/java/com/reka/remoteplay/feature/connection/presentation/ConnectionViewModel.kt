package com.reka.remoteplay.feature.connection.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reka.remoteplay.core.model.DisplayConfigMessage
import com.reka.remoteplay.core.model.ResolutionDto
import com.reka.remoteplay.core.model.ResumeStreamingMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.core.network.WsConnectionState
import com.reka.remoteplay.feature.connection.data.local.ConnectionPreferences
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.data.remote.PhaseOneHandler
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.PhaseTwoHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    application: Application,
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val preferences: ConnectionPreferences,
    private val phaseOneHandler: PhaseOneHandler,
    private val phaseTwoHandler: PhaseTwoHandler
) : AndroidViewModel(application) {

    val connectionState = connectionStateRepo.state
    val savedServers = preferences.savedServers
    val usbMode = preferences.usbMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Phase 1 data
    val serverInfo = phaseOneHandler.serverInfo
    val suggestedConfig = phaseOneHandler.suggestedConfig

    // Phase 2 data
    val monitors = phaseTwoHandler.monitors

    private val _hostInput = MutableStateFlow("")
    val hostInput: StateFlow<String> = _hostInput.asStateFlow()

    private val _portInput = MutableStateFlow("8288")
    val portInput: StateFlow<String> = _portInput.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.lastHost.collect { host ->
                if (_hostInput.value.isEmpty() && host.isNotEmpty()) {
                    _hostInput.value = host
                }
            }
        }

        // Monitor WebSocket connection state changes
        viewModelScope.launch {
            webSocketClient.connectionState.collect { wsState ->
                when (wsState) {
                    WsConnectionState.CONNECTING -> {
                        connectionStateRepo.tryTransition(ConnectionState.Connecting)
                    }
                    WsConnectionState.CONNECTED -> {
                        connectionStateRepo.tryTransition(ConnectionState.AwaitingHardwareInfo)
                    }
                    WsConnectionState.DISCONNECTED -> {
                        val current = connectionStateRepo.currentState
                        if (current.isConnected && current !is ConnectionState.Connecting) {
                            connectionStateRepo.forceTransition(
                                ConnectionState.Error("Connection lost", phase = 0)
                            )
                        }
                    }
                    WsConnectionState.FAILED -> {
                        connectionStateRepo.forceTransition(
                            ConnectionState.Error("Could not connect to server", phase = 0)
                        )
                    }
                }
            }
        }
    }

    fun onHostChanged(host: String) { _hostInput.value = host }
    fun onPortChanged(port: String) { _portInput.value = port }

    fun connect() {
        val host = _hostInput.value.trim()
        val port = _portInput.value.toIntOrNull() ?: 8288
        if (host.isEmpty()) return

        phaseOneHandler.reset()
        phaseTwoHandler.reset()
        connectionStateRepo.tryTransition(ConnectionState.Connecting)

        viewModelScope.launch {
            preferences.saveServer(SavedServer(host = host, port = port))
        }

        val dm = getApplication<Application>().resources.displayMetrics
        phaseOneHandler.startListening(viewModelScope, dm)

        val isUsb = usbMode.value
        webSocketClient.connect(host, port, isUsb = isUsb)
    }

    fun connectToServer(server: SavedServer) {
        _hostInput.value = server.host
        _portInput.value = server.port.toString()
        connect()
    }

    fun disconnect() {
        webSocketClient.disconnect()
        phaseOneHandler.reset()
        phaseTwoHandler.reset()
        connectionStateRepo.reset()
    }

    fun proceed(monitors: Int, resolutionHeight: Int, fps: Int) {
        val config = suggestedConfig.value ?: return
        val isUsb = usbMode.value

        val resWidth = when (resolutionHeight) {
            720 -> 1280
            1080 -> 1920
            1440 -> 2560
            2160 -> 3840
            else -> 1920
        }
        val displayConfig = DisplayConfigMessage(
            monitors = monitors,
            resolution = ResolutionDto(width = resWidth, height = resolutionHeight),
            refreshRate = fps,
            bitrateKbps = config.bitrateKbps,
            fps = fps,
            monitorType = "standard",
            isUsbMode = isUsb
        )
        webSocketClient.sendText(MessageParser.serialize(displayConfig))
        phaseTwoHandler.setConfiguredFps(fps)

        phaseOneHandler.sendProceed()
        phaseTwoHandler.startListening(viewModelScope)
    }

    fun setUsbMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setUsbMode(enabled) }
    }

    fun removeServer(server: SavedServer) {
        viewModelScope.launch { preferences.removeServer(server.host, server.port) }
    }

    fun resumeStreaming() {
        webSocketClient.sendText(MessageParser.serialize(ResumeStreamingMessage()))
        connectionStateRepo.forceTransition(ConnectionState.Streaming)
    }
}
