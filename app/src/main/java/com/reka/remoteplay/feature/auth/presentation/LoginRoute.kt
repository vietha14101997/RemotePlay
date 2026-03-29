package com.reka.remoteplay.feature.auth.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginRoute(
    onLoginSuccess: () -> Unit,
    onSkip: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) onLoginSuccess()
    }

    LoginScreen(
        state = state,
        onRelayUrlChange = viewModel::onRelayUrlChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onUsernameChange = viewModel::onUsernameChange,
        onToggleMode = viewModel::toggleMode,
        onSubmit = viewModel::submit,
        onSkip = onSkip
    )
}
