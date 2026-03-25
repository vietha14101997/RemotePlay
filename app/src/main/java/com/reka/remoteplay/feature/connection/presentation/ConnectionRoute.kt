package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState

@Composable
fun ConnectionRoute(
    onNavigateToConfigReview: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()

    // Auto-navigate based on connection state
    var navigated by remember { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        when (connectionState) {
            is ConnectionState.ConfiguringSettings -> {
                if (!navigated) {
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
        onRemoveServer = viewModel::removeServer
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
    val savedResolution by viewModel.savedResolution.collectAsState()
    val savedFps by viewModel.savedFps.collectAsState()
    val bindMobileScreen by viewModel.bindMobileScreen.collectAsState()

    // Track if stream was paused — survives recomposition/backstack
    var isPaused by rememberSaveable { mutableStateOf(false) }
    val connectionType = remember { viewModel.getConnectionType() }

    // Navigate to streaming when ICE completes (Start flow only, not Resume)
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
            savedResolution = savedResolution,
            savedFps = savedFps,
            connectionType = connectionType,
            bindMobileScreen = bindMobileScreen,
            deviceScreenSpecs = viewModel.deviceScreenSpecs,
            onBindMobileScreenChanged = viewModel::setBindMobileScreen,
            onProceed = { monitors, resolution, fps ->
                navigated = false
                isPaused = false
                viewModel.proceed(monitors, resolution, fps)
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
