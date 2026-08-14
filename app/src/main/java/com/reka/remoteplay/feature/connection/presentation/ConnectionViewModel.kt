package com.reka.remoteplay.feature.connection.presentation

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import com.reka.remoteplay.core.network.relay.RelayDevice
import com.reka.remoteplay.core.network.relay.TokenManager
import com.reka.remoteplay.feature.auth.data.AuthRepository
import com.reka.remoteplay.feature.connection.data.local.ConnectionPreferences
import com.reka.remoteplay.feature.connection.data.local.PairedHost
import com.reka.remoteplay.feature.connection.data.local.PairingStore
import com.reka.remoteplay.feature.connection.data.local.SavedServer
import com.reka.remoteplay.feature.connection.data.remote.PairingSessionManager
import com.reka.remoteplay.feature.connection.data.remote.PhaseOneHandler
import com.reka.remoteplay.feature.connection.data.remote.RelayDiscoveryService
import com.reka.remoteplay.feature.connection.data.remote.ServerDiscoveryService
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.data.GuestConnectionRepository
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.AudioPlayer
import com.reka.remoteplay.feature.streaming.data.remote.IceRestartTrigger
import com.reka.remoteplay.feature.streaming.data.remote.PhaseTwoHandler
import com.reka.remoteplay.feature.streaming.data.remote.VideoDecoderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
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
    private val relayDiscoveryService: RelayDiscoveryService,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val videoDecoderManager: VideoDecoderManager,
    private val audioPlayer: AudioPlayer,
    private val guestConnectionRepository: GuestConnectionRepository,
    private val webRtcManager: com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager,
    private val pairingSessionManager: PairingSessionManager,
    private val pairingStore: PairingStore
) : AndroidViewModel(application) {

    val connectionState = connectionStateRepo.state
    val savedServers = preferences.savedServers

    // Paired devices (fingerprint allowlist) — surfaced so the user can unpair a host, per
    // pairing-protocol-contract-v1.md's "Provide unpair/remove-device path" requirement.
    val pairedHosts: StateFlow<List<PairedHost>> = pairingStore.pairedHosts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun unpairHost(fingerprint: String) {
        viewModelScope.launch { pairingStore.unpair(fingerprint) }
    }

    // Server discovery (manual trigger)
    val discoveredServers = serverDiscoveryService.servers
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Relay devices (same-account, auto-discovery)
    val isLoggedIn: StateFlow<Boolean> = tokenManager.isLoggedIn
    private val _relayDevices = MutableStateFlow<List<RelayDevice>>(emptyList())
    val relayDevices: StateFlow<List<RelayDevice>> = _relayDevices.asStateFlow()

    // Phase 1 data
    val serverInfo = phaseOneHandler.serverInfo
    val suggestedConfig = phaseOneHandler.suggestedConfig

    // Phase 2 data
    val monitors = phaseTwoHandler.monitors
    val webRtcConnectionType = phaseTwoHandler.webRtcConnectionType
    val webRtcIceHostCount = webRtcManager.iceHostCount
    val webRtcIceSrflxCount = webRtcManager.iceSrflxCount
    val webRtcIceRelayCount = webRtcManager.iceRelayCount
    val webRtcIcePrflxCount = webRtcManager.icePrflxCount
    val webRtcIceGatherDurationMs = webRtcManager.iceGatherDurationMs

    // Saved stream settings
    val savedMonitors = preferences.streamMonitors
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)
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

    // Viewer mode: skip config screen, go straight to streaming
    private val _isViewerMode = MutableStateFlow(false)
    val isViewerMode: StateFlow<Boolean> = _isViewerMode.asStateFlow()

    private val connectionAttemptLock = Any()
    private var connectionAttemptGeneration = 0L
    private var connectionAttemptJob: Job? = null

    fun onGuestDeviceIdChange(id: String) { _guestDeviceId.value = id; _guestError.value = null }
    fun onGuestPasswordChange(pw: String) { _guestPassword.value = pw; _guestError.value = null }

    fun connectAsGuest() {
        val id = _guestDeviceId.value.trim()
        val pw = _guestPassword.value.trim()
        if (id.isBlank() || pw.isBlank()) return

        launchConnectionAttempt(showGuestProgress = true) { generation ->
            connectToRoom(generation, id, pw)
        }
    }

    private suspend fun connectToRoom(generation: Long, roomId: String, password: String) {
        _guestError.value = null

        // Fetch TURN servers before joining room (needed for cross-NAT)
        val iceServers = guestConnectionRepository.fetchIceServers()
        if (!isCurrentConnectionAttempt(generation)) return
        if (iceServers.isNotEmpty()) {
            webRtcManager.setIceServers(iceServers)
        }

        guestConnectionRepository.joinRoom(roomId, password).fold(
            onSuccess = { roomInfo ->
                if (!isCurrentConnectionAttempt(generation)) return@fold
                val isViewer = roomInfo.role == "viewer"
                _isViewerMode.value = isViewer

                phaseOneHandler.reset()
                phaseTwoHandler.reset()
                connectionStateRepo.tryTransition(ConnectionState.Connecting)

                if (isViewer) {
                    // Viewer: skip Phase 1+config, fast-track to Phase 2 ICE
                    connectionStateRepo.tryTransition(ConnectionState.AwaitingHardwareInfo)
                    connectionStateRepo.tryTransition(ConnectionState.AwaitingSuggestedConfig)
                    phaseTwoHandler.startListening()
                } else {
                    // Host: full flow
                    val dm = getApplication<Application>().resources.displayMetrics
                    phaseOneHandler.startListening(dm)
                }

                if (!isCurrentConnectionAttempt(generation)) return@fold
                val relayUrl = guestConnectionRepository.getRelayUrl()
                webSocketClient.connectRoom(relayUrl, roomInfo.roomId, roomInfo.clientId)
            },
            onFailure = { e ->
                if (isCurrentConnectionAttempt(generation)) _guestError.value = e.message
            }
        )
    }

    private fun launchConnectionAttempt(
        showGuestProgress: Boolean = false,
        block: suspend (generation: Long) -> Unit
    ) {
        synchronized(connectionAttemptLock) {
            val generation = ++connectionAttemptGeneration
            connectionAttemptJob?.cancel()
            _guestConnecting.value = showGuestProgress
            connectionAttemptJob = viewModelScope.launch {
                try {
                    block(generation)
                } finally {
                    synchronized(connectionAttemptLock) {
                        if (generation == connectionAttemptGeneration) {
                            connectionAttemptJob = null
                            _guestConnecting.value = false
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentConnectionAttempt(generation: Long): Boolean =
        synchronized(connectionAttemptLock) { generation == connectionAttemptGeneration }

    private fun invalidateConnectionAttempt() {
        synchronized(connectionAttemptLock) {
            connectionAttemptGeneration++
            connectionAttemptJob?.cancel()
            connectionAttemptJob = null
            _guestConnecting.value = false
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
                android.util.Log.d("ConnectionVM", "WS State Change: $wsState")
                when (wsState) {
                    WsConnectionState.CONNECTING -> {
                        connectionStateRepo.tryTransition(ConnectionState.Connecting)
                    }
                    WsConnectionState.RECONNECTING -> {
                        // Transient WS drop (network blip, relay/host restart) — surface as
                        // "Reconnecting…" instead of a hard failure. WebSocketClient retries
                        // with backoff on its own; this just reflects that in the UI.
                        val attempt = webSocketClient.reconnectAttempt.value
                        connectionStateRepo.tryTransition(ConnectionState.Reconnecting(attempt))
                    }
                    WsConnectionState.CONNECTED -> {
                        // Resuming after RECONNECTING: Reconnecting -> AwaitingHardwareInfo is not
                        // a modeled direct transition, so route through Connecting first (the
                        // state machine explicitly allows Reconnecting -> Connecting).
                        if (connectionStateRepo.currentState is ConnectionState.Reconnecting) {
                            connectionStateRepo.tryTransition(ConnectionState.Connecting)
                        }
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

        // P5 F10 (first trigger): detect WiFi<->Cellular handover / loss for the active
        // network while a session is live, and forward it to WebRtcManager's ICE-restart
        // gating + adaptive debounce. Registered for the ViewModel's whole lifetime (it stays
        // alive across the Streaming screen too — see AppNavigation, Streaming is pushed
        // without popping Connection) and unregistered in onCleared().
        registerIceRestartNetworkCallback()
    }

    // Start relay discovery when logged in
    init {
        viewModelScope.launch {
            tokenManager.isLoggedIn.collectLatest { loggedIn ->
                if (loggedIn) {
                    relayDiscoveryService.discoverServers().collect { devices ->
                        _relayDevices.value = devices
                    }
                } else {
                    _relayDevices.value = emptyList()
                }
            }
        }
    }

    fun connectToRelayDevice(device: RelayDevice) {
        val roomId = device.roomId ?: return
        launchConnectionAttempt(showGuestProgress = true) { generation ->
            // Same-account: join room without password (server skips password for owner)
            connectToRoom(generation, roomId, "")
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

    fun connectWithQr(config: com.reka.remoteplay.core.model.QrScannerConfig) {
        android.util.Log.i("ConnectionVM", "Connecting with QR: $config")
        launchConnectionAttempt(showGuestProgress = config.hasRelay) { generation ->
            // Relay path first: signaling via the user's own VPS — stable URL, no
            // Cloudflare quick-tunnel rate limits. Reuses the proven guest-join flow.
            if (config.hasRelay) {
                stopScan()
                tokenManager.relayUrl = config.relayUrl!!.trimEnd('/')
                _guestDeviceId.value = config.guestId!!
                _guestPassword.value = config.guestPass!!
                android.util.Log.i("ConnectionVM", "QR carries relay room ${config.guestId} — connecting via relay ${config.relayUrl}")
                connectToRoom(generation, config.guestId!!, config.guestPass!!)
                return@launchConnectionAttempt
            }

            connectDirect(generation, config)
        }
    }

    private suspend fun connectDirect(
        generation: Long,
        config: com.reka.remoteplay.core.model.QrScannerConfig
    ) {
        stopScan()
        phaseOneHandler.reset()
        phaseTwoHandler.reset() // clears any pending pairing secret — offer AFTER this line

        if (config.hasPairingOffer) {
            android.util.Log.i("ConnectionVM", "QR carries a pairing offer (sid=${config.sid}) — will verify after DTLS connects")
            pairingSessionManager.offerPairing(
                psk = config.psk!!,
                nonce = config.nonce!!,
                sid = config.sid!!,
                expMs = config.exp!!
            )
        }

        val dm = getApplication<Application>().resources.displayMetrics
        android.util.Log.i("ConnectionVM", "Starting PhaseOneHandler listening (Pre-connect)")
        phaseOneHandler.startListening(dm)

        val tunnelUrl = config.tunnelUrl
        if (!tunnelUrl.isNullOrEmpty()) {
            val host = tunnelUrl.replace("https://", "").replace("http://", "").trimEnd('/')
            _hostInput.value = host
            _portInput.value = "443"
            preferences.saveServer(SavedServer(name = "Remote PC", host = host, port = 443))
            if (!isCurrentConnectionAttempt(generation)) return
            connectionStateRepo.tryTransition(ConnectionState.Connecting)
            webSocketClient.connectTunnel(tunnelUrl)
        } else {
            val host = config.ip
            val port = config.port
            _hostInput.value = host
            _portInput.value = port.toString()
            preferences.saveServer(SavedServer(name = "Local PC", host = host, port = port))
            if (!isCurrentConnectionAttempt(generation)) return
            connectionStateRepo.tryTransition(ConnectionState.Connecting)
            webSocketClient.connect(host, port, isUsb = false)
        }
    }

    fun connect() {
        val host = _hostInput.value.trim()
        val port = _portInput.value.toIntOrNull() ?: 8288
        if (host.isEmpty()) return

        launchConnectionAttempt { generation ->
            stopScan()
            phaseOneHandler.reset()
            phaseTwoHandler.reset()
            preferences.saveServer(SavedServer(host = host, port = port))
            if (!isCurrentConnectionAttempt(generation)) return@launchConnectionAttempt
            connectionStateRepo.tryTransition(ConnectionState.Connecting)

            val dm = getApplication<Application>().resources.displayMetrics
            phaseOneHandler.startListening(dm)

            webSocketClient.connect(host, port, isUsb = false)
        }
    }

    fun connectToServer(server: SavedServer) {
        _hostInput.value = server.host
        _portInput.value = server.port.toString()
        connect()
    }

    fun disconnect() {
        invalidateConnectionAttempt()
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
        phaseTwoHandler.startListening()
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

    fun logout() {
        authRepository.logout()
    }

    // ==================== P5: ICE Restart on Network Change (first trigger) ====================

    private var connectivityManager: ConnectivityManager? = null
    private var iceRestartNetworkCallback: ConnectivityManager.NetworkCallback? = null

    // Last transport we observed for the active network (TRANSPORT_WIFI/TRANSPORT_CELLULAR/...);
    // null until the first callback fires. Used to tell a genuine WiFi<->Cellular handover apart
    // from a same-transport capability update (signal strength, bandwidth, ...) so we don't
    // spam WebRtcManager on every minor change — this doubles as the "cancel if the same
    // transport is restored quickly" behavior: no real transition means nothing is forwarded,
    // and WebRtcManager's own debounce + isHealthyNow recheck cover a genuine but brief drop.
    private var lastNetworkTransport: Int? = null

    private fun registerIceRestartNetworkCallback() {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onTransportObserved(cm.getNetworkCapabilities(network))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onTransportObserved(capabilities)
            }

            override fun onLost(network: Network) {
                if (isSessionLive()) {
                    webRtcManager.triggerIceRestart(IceRestartTrigger.NETWORK_HARD_LOST)
                }
            }
        }
        iceRestartNetworkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Some OEM/emulator ConnectivityManager implementations can throw here — network
            // change detection is best-effort; the second trigger (iceConnectionState monitor
            // in WebRtcManager) still covers recovery even if this registration fails.
            iceRestartNetworkCallback = null
        }
    }

    private fun onTransportObserved(capabilities: NetworkCapabilities?) {
        val transport = primaryTransportOf(capabilities) ?: return
        val changed = lastNetworkTransport != null && transport != lastNetworkTransport
        lastNetworkTransport = transport
        if (changed && isSessionLive()) {
            webRtcManager.triggerIceRestart(IceRestartTrigger.NETWORK_SOFT_CAPABILITIES_CHANGED)
        }
    }

    private fun isSessionLive(): Boolean = connectionStateRepo.currentState is ConnectionState.Streaming

    private fun primaryTransportOf(capabilities: NetworkCapabilities?): Int? = when {
        capabilities == null -> null
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
        else -> null
    }

    override fun onCleared() {
        super.onCleared()
        invalidateConnectionAttempt()
        serverDiscoveryService.stop()
        iceRestartNetworkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
    }
}
