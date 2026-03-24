package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.QualityFeedbackMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionHealthMonitor @Inject constructor(
    private val webSocketClient: WebSocketClient
) {
    private var monitorJob: Job? = null
    private var missedPongs = 0

    private val _health = MutableStateFlow(ConnectionHealth())
    val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    private val rttSamples = mutableListOf<Float>()

    companion object {
        private const val TAG = "HealthMonitor"
        private const val FEEDBACK_INTERVAL_MS = 3000L
        private const val MAX_MISSED_PONGS = 3
    }

    data class ConnectionHealth(
        val rttMs: Float = 0f,
        val avgRttMs: Float = 0f,
        val jitterMs: Float = 0f,
        val isHealthy: Boolean = true,
        val missedPongs: Int = 0
    )

    fun start(scope: CoroutineScope) {
        stop()
        monitorJob = scope.launch {
            while (isActive) {
                delay(FEEDBACK_INTERVAL_MS)
                updateHealth()
                sendQualityFeedback()
            }
        }
    }

    private fun updateHealth() {
        val currentRtt = webSocketClient.rttMs.value
        if (currentRtt > 0) {
            rttSamples.add(currentRtt)
            if (rttSamples.size > 20) rttSamples.removeAt(0)
            missedPongs = 0
        } else {
            missedPongs++
        }

        val avgRtt = if (rttSamples.isNotEmpty()) rttSamples.average().toFloat() else 0f
        val jitter = if (rttSamples.size > 1) {
            var sum = 0f
            for (i in 1 until rttSamples.size) {
                sum += kotlin.math.abs(rttSamples[i] - rttSamples[i - 1])
            }
            sum / (rttSamples.size - 1)
        } else 0f

        _health.value = ConnectionHealth(
            rttMs = currentRtt,
            avgRttMs = avgRtt,
            jitterMs = jitter,
            isHealthy = missedPongs < MAX_MISSED_PONGS,
            missedPongs = missedPongs
        )

        if (missedPongs >= MAX_MISSED_PONGS) {
            Log.w(TAG, "Connection health critical: $missedPongs missed pongs")
        }
    }

    private fun sendQualityFeedback() {
        val h = _health.value
        val feedback = QualityFeedbackMessage(
            timestamp = System.currentTimeMillis(),
            rttMs = h.rttMs,
            avgRttMs = h.avgRttMs,
            jitterMs = h.jitterMs,
            bufferStatus = if (h.isHealthy) "healthy" else "high_latency",
            connectionHealth = if (h.isHealthy) 100 else 0
        )
        webSocketClient.sendText(MessageParser.serialize(feedback))
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        rttSamples.clear()
        missedPongs = 0
        _health.value = ConnectionHealth()
    }
}
