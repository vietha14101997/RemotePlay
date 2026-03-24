package com.reka.remoteplay.feature.streaming.domain.repository

import com.reka.remoteplay.core.model.MonitorInfoDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface StreamingRepository {
    val monitors: StateFlow<List<MonitorInfoDto>>
    val iceReady: StateFlow<Boolean>
    fun startListening(scope: CoroutineScope)
    fun sendProceedPhase3()
    fun sendStartStreaming()
    fun reset()
}
