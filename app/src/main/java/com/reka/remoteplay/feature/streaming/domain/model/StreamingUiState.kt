package com.reka.remoteplay.feature.streaming.domain.model

import com.reka.remoteplay.core.model.MonitorInfoDto
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState

data class StreamingUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val monitors: List<MonitorInfoDto> = emptyList(),
    val activeMonitor: Int = 0,
    val firstFrameReceived: Set<Int> = emptySet(),
    val showToolbar: Boolean = true,
    val showKeyboard: Boolean = false,
    val rttMs: Float = 0f
)
