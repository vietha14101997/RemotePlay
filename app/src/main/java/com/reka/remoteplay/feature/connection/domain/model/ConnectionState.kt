package com.reka.remoteplay.feature.connection.domain.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object AwaitingHardwareInfo : ConnectionState()
    data object SpeedTesting : ConnectionState()
    data object AwaitingNetworkInfo : ConnectionState()
    data object AwaitingSuggestedConfig : ConnectionState()
    data object ConfiguringSettings : ConnectionState()
    data object SendingDisplayConfig : ConnectionState()
    data object AwaitingSetupComplete : ConnectionState()
    data object IceNegotiating : ConnectionState()
    data object ReadyToStream : ConnectionState()
    data object StartingStream : ConnectionState()
    data object Streaming : ConnectionState()
    data class Reconnecting(val attempt: Int = 0) : ConnectionState()
    data class Error(val message: String, val phase: Int = 0) : ConnectionState()

    val isConnected: Boolean
        get() = this !is Disconnected && this !is Error

    val description: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting to server..."
            is AwaitingHardwareInfo -> "Receiving server info..."
            is SpeedTesting -> "Testing connection speed..."
            is AwaitingNetworkInfo -> "Analyzing network..."
            is AwaitingSuggestedConfig -> "Getting recommendations..."
            is ConfiguringSettings -> "Review settings"
            is SendingDisplayConfig -> "Applying configuration..."
            is AwaitingSetupComplete -> "Server configuring displays..."
            is IceNegotiating -> "Establishing connection..."
            is ReadyToStream -> "Ready to stream"
            is StartingStream -> "Starting stream..."
            is Streaming -> "Streaming"
            is Reconnecting -> "Reconnecting (attempt $attempt)..."
            is Error -> "Error: $message"
        }

    val progressPercent: Int
        get() = when (this) {
            is Disconnected -> 0
            is Connecting -> 5
            is AwaitingHardwareInfo -> 15
            is SpeedTesting -> 25
            is AwaitingNetworkInfo -> 35
            is AwaitingSuggestedConfig -> 45
            is ConfiguringSettings -> 50
            is SendingDisplayConfig -> 55
            is AwaitingSetupComplete -> 65
            is IceNegotiating -> 80
            is ReadyToStream -> 90
            is StartingStream -> 95
            is Streaming -> 100
            is Reconnecting -> 50
            is Error -> 0
        }
}
