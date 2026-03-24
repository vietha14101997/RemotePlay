package com.reka.remoteplay.feature.connection.data.repository

import android.util.Log
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionStateRepositoryImpl @Inject constructor() : ConnectionStateRepository {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override val currentState: ConnectionState get() = _state.value

    override fun tryTransition(newState: ConnectionState, message: String?): Boolean {
        val oldState = _state.value
        if (!isValidTransition(oldState, newState)) {
            Log.w(TAG, "Invalid transition: ${oldState::class.simpleName} -> ${newState::class.simpleName}")
            return false
        }
        Log.d(TAG, "${oldState::class.simpleName} -> ${newState::class.simpleName}${message?.let { ": $it" } ?: ""}")
        _state.value = newState
        return true
    }

    override fun forceTransition(newState: ConnectionState, message: String?) {
        val oldState = _state.value
        Log.d(TAG, "FORCE: ${oldState::class.simpleName} -> ${newState::class.simpleName}${message?.let { ": $it" } ?: ""}")
        _state.value = newState
    }

    override fun reset() {
        forceTransition(ConnectionState.Disconnected, "Reset")
    }

    private fun isValidTransition(from: ConnectionState, to: ConnectionState): Boolean {
        // Always allow transition to Error or Disconnected
        if (to is ConnectionState.Error || to is ConnectionState.Disconnected) return true

        // Always allow Disconnected -> Connecting
        if (from is ConnectionState.Disconnected && to is ConnectionState.Connecting) return true

        // Allow reconnecting from most states
        if (to is ConnectionState.Reconnecting) {
            return from !is ConnectionState.Disconnected && from !is ConnectionState.Connecting
        }

        // From Reconnecting, can go back to Connecting
        if (from is ConnectionState.Reconnecting && to is ConnectionState.Connecting) return true

        return when (from) {
            is ConnectionState.Connecting -> to is ConnectionState.AwaitingHardwareInfo
            is ConnectionState.AwaitingHardwareInfo -> to is ConnectionState.SpeedTesting
            is ConnectionState.SpeedTesting -> to is ConnectionState.AwaitingNetworkInfo
            is ConnectionState.AwaitingNetworkInfo -> to is ConnectionState.AwaitingSuggestedConfig
            is ConnectionState.AwaitingSuggestedConfig -> to is ConnectionState.ConfiguringSettings
            is ConnectionState.ConfiguringSettings -> to is ConnectionState.SendingDisplayConfig
            is ConnectionState.SendingDisplayConfig -> to is ConnectionState.AwaitingSetupComplete
            is ConnectionState.AwaitingSetupComplete -> to is ConnectionState.IceNegotiating
            is ConnectionState.IceNegotiating -> to is ConnectionState.ReadyToStream
            is ConnectionState.ReadyToStream -> to is ConnectionState.StartingStream
            is ConnectionState.StartingStream -> to is ConnectionState.Streaming
            is ConnectionState.Streaming -> to is ConnectionState.Reconnecting
            else -> false
        }
    }

    companion object {
        private const val TAG = "ConnectionState"
    }
}
