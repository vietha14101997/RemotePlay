package com.reka.remoteplay.feature.connection.data.remote

import android.util.DisplayMetrics
import android.util.Log
import com.reka.remoteplay.core.model.ErrorMessage
import com.reka.remoteplay.core.model.HardwareInfoAckMessage
import com.reka.remoteplay.core.model.HardwareInfoMessage
import com.reka.remoteplay.core.model.ProceedMessage
import com.reka.remoteplay.core.model.SuggestedConfigMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhaseOneHandler @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val codecDetector: CodecDetector,
    private val speedTestClient: SpeedTestClient,
    // P5 F8: hardware_info carries the host's supports_ice_restart capability flag.
    private val webRtcManager: WebRtcManager
) {
    private val _serverInfo = MutableStateFlow<HardwareInfoMessage?>(null)
    val serverInfo: StateFlow<HardwareInfoMessage?> = _serverInfo.asStateFlow()

    private val _suggestedConfig = MutableStateFlow<SuggestedConfigMessage?>(null)
    val suggestedConfig: StateFlow<SuggestedConfigMessage?> = _suggestedConfig.asStateFlow()

    private var messageJob: Job? = null
    private var binaryJob: Job? = null
    private var listeningScope: CoroutineScope? = null

    companion object {
        private const val TAG = "PhaseOneHandler"
    }

    fun startListening(scope: CoroutineScope, displayMetrics: DisplayMetrics) {
        Log.i(TAG, "startListening called")
        listeningScope = scope
        
        messageJob?.cancel()
        messageJob = scope.launch {
            Log.d(TAG, "Message collection job started")
            webSocketClient.textMessages.collect { text ->
                Log.v(TAG, "Collected text message: ${text.take(50)}...")
                handleTextMessage(text, displayMetrics)
            }
        }

        binaryJob?.cancel()
        binaryJob = scope.launch {
            webSocketClient.binaryMessages.collect { data ->
                speedTestClient.handleBinaryData(data)
            }
        }
    }

    private fun handleTextMessage(text: String, displayMetrics: DisplayMetrics) {
        android.util.Log.e("PhaseOneHandler", "!!! handleTextMessage START !!! Type detection for: ${text.take(100)}")
        val type = MessageParser.getMessageType(text)
        android.util.Log.e("PhaseOneHandler", "!!! Detected type: $type")
        
        if (type == null) return

        try {
            when (type) {
                "hardware_info" -> {
                    android.util.Log.e("PhaseOneHandler", ">>> PROCESSING hardware_info <<<")
                    val msg = MessageParser.parse<HardwareInfoMessage>(text)
                    if (msg == null) {
                        android.util.Log.e("PhaseOneHandler", "FAILED to parse hardware_info JSON")
                        return
                    }
                    
                    _serverInfo.value = msg
                    webRtcManager.setSupportsIceRestart(msg.supportsIceRestart)

                    // Force transition to ensure we don't get stuck due to ordering issues
                    connectionStateRepo.forceTransition(ConnectionState.AwaitingHardwareInfo)

                    val codecs = codecDetector.detectCapabilities(displayMetrics)
                    val ack = HardwareInfoAckMessage(
                        clientCodecs = codecs,
                        perTrackPc = true
                    )
                    val ackJson = MessageParser.serialize(ack)
                    Log.d(TAG, "Sending hardware_info_ack: $ackJson")
                    webSocketClient.sendText(ackJson)

                    // Transition to next state
                    val transitioned = connectionStateRepo.tryTransition(ConnectionState.AwaitingSuggestedConfig)
                    Log.d(TAG, "Transitioned to AwaitingSuggestedConfig: $transitioned")
                }

                "speedtest_start" -> {
                    Log.d(TAG, "Received speedtest_start")
                    connectionStateRepo.tryTransition(ConnectionState.SpeedTesting)
                    // The SpeedTestClient handles binary data via binaryJob
                }

                "suggested_config" -> {
                    Log.d(TAG, "Received suggested_config")
                    val msg = MessageParser.parse<SuggestedConfigMessage>(text) ?: return
                    _suggestedConfig.value = msg
                    connectionStateRepo.tryTransition(ConnectionState.ConfiguringSettings)
                }

                "error" -> {
                    val msg = MessageParser.parse<ErrorMessage>(text) ?: return
                    connectionStateRepo.forceTransition(
                        ConnectionState.Error(msg.message, phase = msg.phase)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message type $type: ${e.message}", e)
            connectionStateRepo.forceTransition(ConnectionState.Error("Client error: ${e.message}"))
        }
    }

    fun sendProceed() {
        val proceed = ProceedMessage(phase = 2)
        webSocketClient.sendText(MessageParser.serialize(proceed))
        connectionStateRepo.tryTransition(ConnectionState.SendingDisplayConfig)
    }

    fun reset() {
        messageJob?.cancel()
        binaryJob?.cancel()
        messageJob = null
        binaryJob = null
        listeningScope = null
        _serverInfo.value = null
        _suggestedConfig.value = null
        speedTestClient.reset()
        // P5 F8: don't let a previous host's capability leak into a fresh connection attempt
        // before the new hardware_info arrives — default back to "unsupported" (safe: falls
        // back to restart_phase2 rather than risking an ICE restart the new host can't apply).
        webRtcManager.setSupportsIceRestart(false)
    }
}
