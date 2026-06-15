package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState

@Composable
fun ConnectionRoute(
    onNavigateToConfigReview: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isViewerMode by viewModel.isViewerMode.collectAsState()

    // Auto-navigate based on connection state
    var navigated by remember { mutableStateOf(false) }
    LaunchedEffect(connectionState, isViewerMode) {
        when (connectionState) {
            is ConnectionState.ConfiguringSettings -> {
                if (!navigated && !isViewerMode) {
                    navigated = true
                    onNavigateToConfigReview()
                }
            }
            is ConnectionState.ReadyToStream,
            is ConnectionState.StartingStream,
            is ConnectionState.Streaming -> {
                if (!navigated && isViewerMode) {
                    navigated = true
                    onNavigateToConfigReview()
                }
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                navigated = false
            }
            else -> {}
        }
    }

    val savedServers by viewModel.savedServers.collectAsState(initial = emptyList())
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val relayDevices by viewModel.relayDevices.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val guestDeviceId by viewModel.guestDeviceId.collectAsState()
    val guestPassword by viewModel.guestPassword.collectAsState()
    val guestError by viewModel.guestError.collectAsState()
    val guestConnecting by viewModel.guestConnecting.collectAsState()

    ConnectionScreen(
        connectionState = connectionState,
        savedServers = savedServers,
        discoveredServers = discoveredServers,
        isScanning = isScanning,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onDisconnect = viewModel::disconnect,
        onConnectToServer = viewModel::connectToServer,
        onConnectToDiscovered = viewModel::connectToDiscovered,
        onRemoveServer = viewModel::removeServer,
        relayDevices = relayDevices,
        diagnostics = diagnostics,
        isLoggedIn = isLoggedIn,
        onConnectToRelayDevice = viewModel::connectToRelayDevice,
        guestDeviceId = guestDeviceId,
        guestPassword = guestPassword,
        guestError = guestError,
        guestConnecting = guestConnecting,
        onGuestDeviceIdChange = viewModel::onGuestDeviceIdChange,
        onGuestPasswordChange = viewModel::onGuestPasswordChange,
        onGuestConnect = viewModel::connectAsGuest,
        onLogout = {
            viewModel.logout()
            onNavigateToLogin()
        }
    )
}

@Composable
fun ConfigReviewRoute(
    onNavigateToStreaming: () -> Unit,
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val suggestedConfig by viewModel.suggestedConfig.collectAsState()
    val savedMonitors by viewModel.savedMonitors.collectAsState()
    val savedFps by viewModel.savedFps.collectAsState()
    val savedWindowsScale by viewModel.savedWindowsScale.collectAsState()
    val bindMobileScreen by viewModel.bindMobileScreen.collectAsState()
    val qualityPreset by viewModel.qualityPreset.collectAsState()

    var isPaused by rememberSaveable { mutableStateOf(false) }
    val connectionType = remember { viewModel.getConnectionType() }
    val webRtcConnectionType by viewModel.webRtcConnectionType.collectAsState()
    val iceHost by viewModel.webRtcIceHostCount.collectAsState()
    val iceSrflx by viewModel.webRtcIceSrflxCount.collectAsState()
    val iceRelay by viewModel.webRtcIceRelayCount.collectAsState()
    val icePrflx by viewModel.webRtcIcePrflxCount.collectAsState()
    val iceGatherMs by viewModel.webRtcIceGatherDurationMs.collectAsState()
    val diagnostics = remember(iceHost, iceSrflx, iceRelay, icePrflx, iceGatherMs, webRtcConnectionType) {
        if (webRtcConnectionType == "unknown" || webRtcConnectionType == "checking") null
        else ConnectionDiagnostics(
            connectionType = webRtcConnectionType,
            hostCount = iceHost,
            srflxCount = iceSrflx,
            relayCount = iceRelay,
            prflxCount = icePrflx,
            gatherDurationMs = iceGatherMs
        )
    }

    var navigated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        if (!navigated && !isPaused) {
            when (connectionState) {
                is ConnectionState.ReadyToStream,
                is ConnectionState.StartingStream,
                is ConnectionState.Streaming -> {
                    navigated = true
                    isPaused = true
                    onNavigateToStreaming()
                }
                else -> {}
            }
        }
    }

    if (serverInfo != null && suggestedConfig != null) {
        ConfigReviewScreen(
            serverInfo = serverInfo!!,
            suggestedConfig = suggestedConfig!!,
            connectionState = connectionState,
            isPaused = isPaused,
            savedMonitors = savedMonitors,
            savedFps = savedFps,
            savedWindowsScale = savedWindowsScale,
            connectionType = connectionType,
            webRtcConnectionType = webRtcConnectionType,
            bindMobileScreen = bindMobileScreen,
            deviceScreenSpecs = viewModel.deviceScreenSpecs,
            qualityPreset = qualityPreset,
            onBindMobileScreenChanged = viewModel::setBindMobileScreen,
            onQualityPresetChanged = viewModel::setQualityPreset,
            onProceed = { monitors, fps, windowsScale ->
                navigated = false
                isPaused = false
                viewModel.proceed(monitors, fps, windowsScale)
            },
            onResume = {
                viewModel.resumeStreaming()
                onNavigateToStreaming()
            },
            onBack = {
                isPaused = false
                viewModel.disconnect()
                onBack()
            }
        )
    }
}
