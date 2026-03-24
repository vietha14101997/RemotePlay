package com.reka.remoteplay.feature.connection.data.remote

import android.util.DisplayMetrics
import android.util.Log
import com.reka.remoteplay.core.model.ErrorMessage
import com.reka.remoteplay.core.model.HardwareInfoAckMessage
import com.reka.remoteplay.core.model.HardwareInfoMessage
import com.reka.remoteplay.core.model.ProceedMessage
import com.reka.remoteplay.core.model.SpeedTestResult
import com.reka.remoteplay.core.model.SuggestedConfigMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhaseOneHandler @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val codecDetector: CodecDetector,
    private val speedTestClient: SpeedTestClient
) {
    private val _serverInfo = MutableStateFlow<HardwareInfoMessage?>(null)
    val serverInfo: StateFlow<HardwareInfoMessage?> = _serverInfo.asStateFlow()

    private val _suggestedConfig = MutableStateFlow<SuggestedConfigMessage?>(null)
    val suggestedConfig: StateFlow<SuggestedConfigMessage?> = _suggestedConfig.asStateFlow()

    private val _speedTestResult = MutableStateFlow<SpeedTestResult?>(null)
    val speedTestResult: StateFlow<SpeedTestResult?> = _speedTestResult.asStateFlow()

    val speedTestProgress = speedTestClient.progress
    val speedTestStatus = speedTestClient.status

    private var messageJob: Job? = null
    private var listeningScope: CoroutineScope? = null

    companion object {
        private const val TAG = "PhaseOneHandler"
    }

    /**
     * Starts collecting WebSocket text and binary frames for Phase 1.
     * Must be called once the WebSocket connection is open.
     * Cancel by calling [reset].
     */
    fun startListening(scope: CoroutineScope, displayMetrics: DisplayMetrics) {
        listeningScope = scope
        messageJob?.cancel()
        messageJob = scope.launch {
            webSocketClient.textMessages.collect { text ->
                handleTextMessage(text, displayMetrics)
            }
        }

        // Binary frames feed the bandwidth measurement in SpeedTestClient
        scope.launch {
            webSocketClient.binaryMessages.collect { data ->
                speedTestClient.handleBinaryData(data)
            }
        }
    }

    private suspend fun handleTextMessage(text: String, displayMetrics: DisplayMetrics) {
        // "pong" frames are intercepted by WebSocketClient and forwarded via onPong callback;
        // they never reach textMessages, so no guard is needed here.

        val type = MessageParser.getMessageType(text) ?: return

        when (type) {
            "hardware_info" -> {
                Log.d(TAG, "Received hardware_info")
                val msg = MessageParser.parse<HardwareInfoMessage>(text) ?: return
                _serverInfo.value = msg

                // Confirm we are in the expected state (no-op if already there)
                connectionStateRepo.tryTransition(ConnectionState.AwaitingHardwareInfo)

                // Detect client codec capabilities and acknowledge
                val codecs = codecDetector.detectCapabilities(displayMetrics)
                val ack = HardwareInfoAckMessage(
                    clientCodecs = codecs,
                    perTrackPc = true
                )
                webSocketClient.sendText(MessageParser.serialize(ack))
                Log.d(TAG, "Sent hardware_info_ack (codec=${codecs.preferredCodec}, perTrackPc=true)")

                connectionStateRepo.tryTransition(ConnectionState.SpeedTesting)

                // Launch speed test in a SEPARATE coroutine so it doesn't block
                // the textMessages collector. The collector must remain active to
                // process "speedtest_end" while the bandwidth test is running.
                listeningScope?.launch {
                    try {
                        val result = speedTestClient.runSpeedTest()
                        _speedTestResult.value = result
                        Log.d(
                            TAG,
                            "Speed test complete: ${"%.1f".format(result.bandwidthMbps)}Mbps, " +
                                "${"%.1f".format(result.pingMs)}ms ping"
                        )

                        connectionStateRepo.tryTransition(ConnectionState.AwaitingNetworkInfo)
                        connectionStateRepo.tryTransition(ConnectionState.AwaitingSuggestedConfig)
                    } catch (e: Exception) {
                        Log.e(TAG, "Speed test failed: ${e.message}")
                        connectionStateRepo.forceTransition(
                            ConnectionState.Error("Speed test failed: ${e.message}", phase = 1)
                        )
                    }
                }
            }

            "speedtest_end" -> {
                Log.d(TAG, "Received speedtest_end")
                speedTestClient.handleSpeedTestEnd()
            }

            "suggested_config" -> {
                Log.d(TAG, "Received suggested_config")
                val msg = MessageParser.parse<SuggestedConfigMessage>(text) ?: return
                _suggestedConfig.value = msg
                connectionStateRepo.tryTransition(
                    ConnectionState.ConfiguringSettings(config = msg)
                )
                Log.d(
                    TAG,
                    "Config: ${msg.monitors} monitors, " +
                        "${msg.resolution.width}x${msg.resolution.height}@${msg.fps}fps, " +
                        "codec=${msg.selectedCodec}"
                )
            }

            "error" -> {
                val msg = MessageParser.parse<ErrorMessage>(text) ?: return
                Log.e(TAG, "Server error: [${msg.code}] ${msg.message}")
                connectionStateRepo.forceTransition(
                    ConnectionState.Error(msg.message, phase = msg.phase)
                )
            }
        }
    }

    /**
     * Sends the `proceed` message to advance the server to Phase 2.
     * Call this after the user confirms the [suggestedConfig].
     */
    fun sendProceed() {
        val proceed = ProceedMessage(phase = 2)
        webSocketClient.sendText(MessageParser.serialize(proceed))
        connectionStateRepo.tryTransition(ConnectionState.SendingDisplayConfig)
        Log.d(TAG, "Sent proceed (phase=2)")
    }

    /** Cancels all listeners and clears cached state. Call on disconnect or reconnect. */
    fun reset() {
        messageJob?.cancel()
        messageJob = null
        listeningScope = null
        _serverInfo.value = null
        _suggestedConfig.value = null
        _speedTestResult.value = null
    }
}
