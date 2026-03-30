package com.reka.remoteplay.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reka.remoteplay.core.network.relay.TokenManager
import com.reka.remoteplay.feature.auth.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val relayUrl: String = "http://34.87.150.141:8443",
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isRegistering: Boolean = false,
    val loginSuccess: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(relayUrl = tokenManager.relayUrl ?: "http://34.87.150.141:8443")
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = tokenManager.isLoggedIn

    fun onRelayUrlChange(url: String) { _uiState.update { it.copy(relayUrl = url) } }
    fun onEmailChange(email: String) { _uiState.update { it.copy(email = email, error = null) } }
    fun onPasswordChange(pw: String) { _uiState.update { it.copy(password = pw, error = null) } }
    fun onUsernameChange(name: String) { _uiState.update { it.copy(username = name, error = null) } }
    fun toggleMode() { _uiState.update { it.copy(isRegistering = !it.isRegistering, error = null) } }

    fun submit() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Email and password are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            tokenManager.relayUrl = state.relayUrl

            val result = if (state.isRegistering) {
                if (state.username.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, error = "Username is required") }
                    return@launch
                }
                authRepository.register(state.email, state.username, state.password).map {
                    authRepository.login(state.relayUrl, state.email, state.password)
                }.getOrElse { Result.failure(it) }
            } else {
                authRepository.login(state.relayUrl, state.email, state.password)
            }

            result.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, loginSuccess = true) } },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed") } }
            )
        }
    }
}
