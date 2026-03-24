package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun StreamingRoute(
    onBack: () -> Unit,
    viewModel: StreamingViewModel = hiltViewModel()
) {
    // Navigation is handled inside StreamingScreen via LaunchedEffect on connectionState
    StreamingScreen(
        onBack = onBack,
        viewModel = viewModel
    )
}
