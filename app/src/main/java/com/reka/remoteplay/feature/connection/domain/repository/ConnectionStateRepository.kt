package com.reka.remoteplay.feature.connection.domain.repository

import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

interface ConnectionStateRepository {
    val state: StateFlow<ConnectionState>
    val currentState: ConnectionState
    fun tryTransition(newState: ConnectionState, message: String? = null): Boolean
    fun forceTransition(newState: ConnectionState, message: String? = null)
    fun reset()
}
