package com.reka.remoteplay.feature.connection.presentation

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.reka.remoteplay.feature.connection.data.remote.ServerDiscoveryService
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.AudioPlayer
import com.reka.remoteplay.feature.streaming.data.remote.PhaseTwoHandler
import com.reka.remoteplay.feature.streaming.data.remote.VideoDecoderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.roundToInt
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
    private val phaseTwoHandler: PhaseTwoHandler,
    private val serverDiscoveryService: ServerDiscoveryService,
    private val videoDecoderManager: VideoDecoderManager,
    private val audioPlayer: AudioPlayer
) : AndroidViewModel(application) {

    val connectionState = connectionStateRepo.state
    val savedServers = preferences.savedServers

    // Server discovery (manual trigger)
    val discoveredServers = serverDiscoveryService.servers
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Phase 1 data
    val serverInfo = phaseOneHandler.serverInfo
    val suggestedConfig = phaseOneHandler.suggestedConfig

    // Phase 2 data
    val monitors = phaseTwoHandler.monitors

    // Saved stream settings
    val savedMonitors = preferences.streamMonitors
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val savedResolution = preferences.streamResolution
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1080)
    val savedFps = preferences.streamFps
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)
    val bindMobileScreen = preferences.bindMobileScreen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val deviceScreenSpecs = com.reka.remoteplay.core.util.ScreenSpecDetector.detect(application)

    fun setBindMobileScreen(enabled: Boolean) {
        viewModelScope.launch { preferences.saveBindMobileScreen(enabled) }
    }

    private val _hostInput = MutableStateFlow("")
    private val _portInput = MutableStateFlow("8288")

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

    fun startScan() {
        serverDiscoveryService.start(viewModelScope)
        _isScanning.value = true
    }

    fun stopScan() {
        serverDiscoveryService.stop()
        _isScanning.value = false
    }

    fun connectToDiscovered(server: ServerDiscoveryService.DiscoveredServer) {
        _hostInput.value = server.ip
        _portInput.value = server.port.toString()
        connect() // connect() calls stop() internally
    }

    fun connect() {
        val host = _hostInput.value.trim()
        val port = _portInput.value.toIntOrNull() ?: 8288
        if (host.isEmpty()) return

        stopScan()
        phaseOneHandler.reset()
        phaseTwoHandler.reset()
        connectionStateRepo.tryTransition(ConnectionState.Connecting)

        viewModelScope.launch {
            preferences.saveServer(SavedServer(host = host, port = port))
        }

        val dm = getApplication<Application>().resources.displayMetrics
        phaseOneHandler.startListening(viewModelScope, dm)

        webSocketClient.connect(host, port, isUsb = false)
    }

    fun connectToServer(server: SavedServer) {
        _hostInput.value = server.host
        _portInput.value = server.port.toString()
        connect()
    }

    fun disconnect() {
        audioPlayer.stop()
        videoDecoderManager.releaseAll()
        webSocketClient.disconnect()
        phaseOneHandler.reset()
        phaseTwoHandler.reset()
        connectionStateRepo.reset()
        stopScan()
    }

    fun proceed(monitors: Int, resolutionHeight: Int, fps: Int) {
        val config = suggestedConfig.value ?: return

        val displayConfig: DisplayConfigMessage
        val streamFps: Int

        if (bindMobileScreen.value) {
            // Bind Mobile mode: detect actual device screen specs at connection time
            val specs = com.reka.remoteplay.core.util.ScreenSpecDetector.detect(getApplication())
            val deviceHz = specs.refreshRate.roundToInt().coerceIn(30, 240)
            streamFps = deviceHz.coerceAtMost(120)

            displayConfig = DisplayConfigMessage(
                monitors = 1,
                resolution = ResolutionDto(width = specs.widthPx, height = specs.heightPx),
                refreshRate = deviceHz,
                bitrateKbps = config.bitrateKbps,
                fps = streamFps,
                monitorType = "bind_mobile",
                isUsbMode = false
            )
        } else {
            // Standard mode
            viewModelScope.launch {
                preferences.saveStreamSettings(monitors, resolutionHeight, fps)
            }

            val resWidth = when (resolutionHeight) {
                720 -> 1280; 1080 -> 1920; 1440 -> 2560; 2160 -> 3840; else -> 1920
            }
            streamFps = fps
            displayConfig = DisplayConfigMessage(
                monitors = monitors,
                resolution = ResolutionDto(width = resWidth, height = resolutionHeight),
                refreshRate = fps,
                bitrateKbps = config.bitrateKbps,
                fps = fps,
                monitorType = "standard",
                isUsbMode = false
            )
        }

        webSocketClient.sendText(MessageParser.serialize(displayConfig))
        phaseTwoHandler.setConfiguredFps(streamFps)

        phaseOneHandler.sendProceed()
        phaseTwoHandler.startListening(viewModelScope)
    }

    fun getConnectionType(): String {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "Unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                else -> "Unknown"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun removeServer(server: SavedServer) {
        viewModelScope.launch { preferences.removeServer(server.host, server.port) }
    }

    fun resumeStreaming() {
        webSocketClient.sendText(MessageParser.serialize(ResumeStreamingMessage()))
        connectionStateRepo.forceTransition(ConnectionState.Streaming)
    }

    override fun onCleared() {
        super.onCleared()
        serverDiscoveryService.stop()
    }
}
