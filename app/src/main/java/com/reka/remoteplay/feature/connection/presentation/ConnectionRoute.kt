package com.reka.remoteplay.feature.connection.presentation

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState

@Composable
fun ConnectionRoute(
    onNavigateToConfigReview: () -> Unit,
    onNavigateToStreaming: () -> Unit,
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

    ConnectionScreen(
        viewModel = viewModel,
        onConnected = onNavigateToStreaming
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
    LaunchedEffect(connectionState) {
        // Detect return from streaming (pauseAndGoBack sets ConfiguringSettings)
        if (connectionState is ConnectionState.ConfiguringSettings) {
            // Check if we already had a streaming session before
            if (isPaused.value) {
                // Already marked as paused, stay here
            }
        }
    }

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
                viewModel.disconnect()
                onBack()
            }
        )
    }
}
