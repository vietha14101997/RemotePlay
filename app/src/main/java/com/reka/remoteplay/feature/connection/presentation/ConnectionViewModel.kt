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
import com.reka.remoteplay.core.util.EncoderResolutionCalculator
import com.reka.remoteplay.core.util.QualityPreset
import com.reka.remoteplay.core.util.ScreenSpecDetector
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.core.network.WsConnectionState
import com.reka.remoteplay.feature.connection.data.local.ConnectionPreferences
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.data.remote.PhaseOneHandler
import com.reka.remoteplay.feature.connection.data.remote.ServerDiscoveryService
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.data.GuestConnectionRepository
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
    private val audioPlayer: AudioPlayer,
    private val guestConnectionRepository: GuestConnectionRepository,
    private val webRtcManager: com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager
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
    val webRtcConnectionType = phaseTwoHandler.webRtcConnectionType

    // Saved stream settings
    val savedMonitors = preferences.streamMonitors
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val savedResolution = preferences.streamResolution
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1080)
    val savedFps = preferences.streamFps
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)
    val savedWindowsScale = preferences.windowsScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 125)
    val bindMobileScreen = preferences.bindMobileScreen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val deviceScreenSpecs = ScreenSpecDetector.detect(application)

    val savedQualityPreset = preferences.qualityPreset
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Quality")

    /** Current quality preset as enum */
    val qualityPreset: StateFlow<QualityPreset>
        get() = savedQualityPreset.map { name ->
            QualityPreset.entries.find { it.name == name } ?: QualityPreset.Quality
        }.stateIn(viewModelScope, SharingStarted.Eagerly, QualityPreset.Quality)

    fun setBindMobileScreen(enabled: Boolean) {
        viewModelScope.launch { preferences.saveBindMobileScreen(enabled) }
    }

    fun setQualityPreset(preset: QualityPreset) {
        viewModelScope.launch { preferences.saveQualityPreset(preset.name) }
    }

    private val _hostInput = MutableStateFlow("")
    private val _portInput = MutableStateFlow("8288")

    // Guest connect state
    private val _guestDeviceId = MutableStateFlow("")
    val guestDeviceId: StateFlow<String> = _guestDeviceId.asStateFlow()
    private val _guestPassword = MutableStateFlow("")
    val guestPassword: StateFlow<String> = _guestPassword.asStateFlow()
    private val _guestError = MutableStateFlow<String?>(null)
    val guestError: StateFlow<String?> = _guestError.asStateFlow()
    private val _guestConnecting = MutableStateFlow(false)
    val guestConnecting: StateFlow<Boolean> = _guestConnecting.asStateFlow()

    fun onGuestDeviceIdChange(id: String) { _guestDeviceId.value = id; _guestError.value = null }
    fun onGuestPasswordChange(pw: String) { _guestPassword.value = pw; _guestError.value = null }

    fun connectAsGuest() {
        val id = _guestDeviceId.value.trim()
        val pw = _guestPassword.value.trim()
        if (id.isBlank() || pw.isBlank()) return

        viewModelScope.launch {
            _guestConnecting.value = true
            _guestError.value = null

            guestConnectionRepository.joinRoom(id, pw).fold(
                onSuccess = { roomInfo ->
                    phaseOneHandler.reset()
                    phaseTwoHandler.reset()
                    connectionStateRepo.tryTransition(ConnectionState.Connecting)

                    val dm = getApplication<Application>().resources.displayMetrics
                    phaseOneHandler.startListening(viewModelScope, dm)

                    val relayUrl = guestConnectionRepository.getRelayUrl()
                    webSocketClient.connectRoom(relayUrl, roomInfo.roomId, roomInfo.clientId)
                    _guestConnecting.value = false
                },
                onFailure = { e ->
                    _guestError.value = e.message
                    _guestConnecting.value = false
                }
            )
        }
    }

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

    /**
     * Connect using QR scan result.
     * If tunnelUrl is available → internet mode (wss:// via Cloudflare tunnel).
     * Otherwise → LAN mode (ws:// direct IP).
     */
    fun connectWithQrConfig(config: com.reka.remoteplay.core.model.QrScannerConfig) {
        stopScan()
        phaseOneHandler.reset()
        phaseTwoHandler.reset()
        connectionStateRepo.tryTransition(ConnectionState.Connecting)

        val dm = getApplication<Application>().resources.displayMetrics
        phaseOneHandler.startListening(viewModelScope, dm)

        if (config.hasTunnelUrl) {
            // Internet mode via Cloudflare tunnel
            _hostInput.value = config.tunnelUrl!!
            viewModelScope.launch {
                preferences.saveServer(SavedServer(host = config.tunnelUrl, port = config.port))
            }
            webSocketClient.connectTunnel(config.tunnelUrl)
        } else {
            // LAN mode — direct IP
            _hostInput.value = config.ip
            _portInput.value = config.port.toString()
            viewModelScope.launch {
                preferences.saveServer(SavedServer(host = config.ip, port = config.port))
            }
            webSocketClient.connect(config.ip, config.port, isUsb = false)
        }
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

    fun proceed(monitors: Int, fps: Int, windowsScale: Int = 125) {
        val config = suggestedConfig.value ?: return
        val preset = qualityPreset.value

        val displayConfig: DisplayConfigMessage
        val streamFps: Int

        if (bindMobileScreen.value) {
            // Bind Mobile mode: VDD refresh rate = phone max Hz, stream FPS = user-selected
            val specs = ScreenSpecDetector.detect(getApplication())
            val deviceHz = specs.refreshRate.roundToInt().coerceIn(30, 240)
            streamFps = fps

            val landscapeW = maxOf(specs.widthPx, specs.heightPx)
            val landscapeH = minOf(specs.widthPx, specs.heightPx)
            val maxQH = serverInfo.value?.maxQualityHeight ?: 1440
            val (alignedW, alignedH) = EncoderResolutionCalculator.calculate(
                landscapeW, landscapeH, preset, maxQH
            )

            displayConfig = DisplayConfigMessage(
                monitors = 1,
                resolution = ResolutionDto(width = alignedW, height = alignedH),
                refreshRate = deviceHz,
                bitrateKbps = config.bitrateKbps,
                fps = streamFps,
                monitorType = "bind_mobile",
                isUsbMode = false,
                windowsScale = windowsScale
            )

            phaseTwoHandler.setScreenDimensions(landscapeW, landscapeH)
            phaseTwoHandler.setMaxQualityHeight(maxQH)
        } else {
            // Standard mode: use server suggested resolution + quality preset
            val maxQH = serverInfo.value?.maxQualityHeight ?: 1440
            val sugW = config.resolution.width
            val sugH = config.resolution.height
            val (alignedW, alignedH) = EncoderResolutionCalculator.calculate(
                sugW, sugH, preset, maxQH
            )

            streamFps = fps
            displayConfig = DisplayConfigMessage(
                monitors = monitors,
                resolution = ResolutionDto(width = alignedW, height = alignedH),
                refreshRate = fps,
                bitrateKbps = config.bitrateKbps,
                fps = fps,
                monitorType = "standard",
                isUsbMode = false,
                windowsScale = windowsScale
            )

            phaseTwoHandler.setScreenDimensions(sugW, sugH)
            phaseTwoHandler.setMaxQualityHeight(maxQH)
        }

        // Save settings
        viewModelScope.launch {
            preferences.saveStreamSettings(monitors, fps, windowsScale)
        }

        // Compute and store available FPS options for streaming screen dynamic adjustment
        val maxHz = if (bindMobileScreen.value) {
            ScreenSpecDetector.detect(getApplication()).refreshRate
        } else {
            config.refreshRate.toFloat()
        }
        phaseTwoHandler.setAvailableFpsOptions(com.reka.remoteplay.core.util.buildFpsOptions(maxHz))

        webSocketClient.sendText(MessageParser.serialize(displayConfig))
        phaseTwoHandler.setConfiguredFps(streamFps)
        phaseTwoHandler.setConfiguredCodec(config.selectedCodec)
        phaseTwoHandler.setQualityPreset(qualityPreset.value)

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
