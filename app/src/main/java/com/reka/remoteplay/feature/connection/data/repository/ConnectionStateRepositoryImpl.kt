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
        synchronized(this) {
            val oldState = _state.value
            if (oldState == newState) return true

            if (!isValidTransition(oldState, newState)) {
                Log.w(TAG, "Invalid transition: ${oldState::class.simpleName} -> ${newState::class.simpleName}")
                return false
            }
            
            Log.d(TAG, "${oldState::class.simpleName} -> ${newState::class.simpleName}${message?.let { ": $it" } ?: ""}")
            _state.value = newState
            return true
        }
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

        // Always allow Disconnected or Error -> Connecting to restart the flow
        if ((from is ConnectionState.Disconnected || from is ConnectionState.Error) && to is ConnectionState.Connecting) return true

        // Allow reconnecting from most states
        if (to is ConnectionState.Reconnecting) {
            return from !is ConnectionState.Disconnected && from !is ConnectionState.Connecting
        }

        // From Reconnecting, can go back to Connecting
        if (from is ConnectionState.Reconnecting && to is ConnectionState.Connecting) return true

        val fromOrder = getOrder(from)
        val toOrder = getOrder(to)

        // During the initial connection phase (up to ConfiguringSettings),
        // allow any forward transition. This prevents getting stuck if messages
        // arrive out of order or some intermediate state transitions are missed.
        if (fromOrder in 0..5 && toOrder in 0..5) {
            return toOrder > fromOrder
        }

        // Strict transitions for the rest of the flow
        return when (from) {
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

    private fun getOrder(state: ConnectionState): Int = when (state) {
        is ConnectionState.Connecting -> 0
        is ConnectionState.AwaitingHardwareInfo -> 1
        is ConnectionState.SpeedTesting -> 2
        is ConnectionState.AwaitingNetworkInfo -> 3
        is ConnectionState.AwaitingSuggestedConfig -> 4
        is ConnectionState.ConfiguringSettings -> 5
        is ConnectionState.SendingDisplayConfig -> 6
        is ConnectionState.AwaitingSetupComplete -> 7
        is ConnectionState.IceNegotiating -> 8
        is ConnectionState.ReadyToStream -> 9
        is ConnectionState.StartingStream -> 10
        is ConnectionState.Streaming -> 11
        else -> -1
    }

    companion object {
        private const val TAG = "ConnectionState"
    }
}
