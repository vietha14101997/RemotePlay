package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.runtime.*
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
                // Phase 1 complete → show config review screen
                if (!navigated) {
                    navigated = true
                    onNavigateToConfigReview()
                }
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                // Reset navigation flag on disconnect
                navigated = false
            }
            else -> {}
        }
    }

    val savedServers by viewModel.savedServers.collectAsState(initial = emptyList())
    val hostInput by viewModel.hostInput.collectAsState()
    val portInput by viewModel.portInput.collectAsState()
    val usbMode by viewModel.usbMode.collectAsState(initial = false)

    ConnectionScreen(
        connectionState = connectionState,
        savedServers = savedServers,
        hostInput = hostInput,
        portInput = portInput,
        usbMode = usbMode,
        onHostChanged = viewModel::onHostChanged,
        onPortChanged = viewModel::onPortChanged,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onConnectToServer = viewModel::connectToServer,
        onRemoveServer = viewModel::removeServer,
        onSetUsbMode = viewModel::setUsbMode
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

    // Track if stream was paused (came back from StreamingScreen)
    val isPaused = remember { mutableStateOf(false) }

    // Navigate to streaming when ICE completes
    var navigated by remember { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        if (!navigated) {
            when (connectionState) {
                is ConnectionState.ReadyToStream,
                is ConnectionState.StartingStream,
                is ConnectionState.Streaming -> {
                    navigated = true
                    isPaused.value = true // Next time we return, show Resume
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
            isPaused = isPaused.value,
            onProceed = { monitors, resolution, fps ->
                navigated = false // Allow re-navigation after new Start
                viewModel.proceed(monitors, resolution, fps)
            },
            onResume = {
                navigated = false
                // Resume streaming — navigate back to StreamingScreen
                viewModel.resumeStreaming()
                onNavigateToStreaming()
            },
            onBack = {
                isPaused.value = false
                viewModel.disconnect()
                onBack()
            }
        )
    }
}
