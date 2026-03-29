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
    private val speedTestClient: SpeedTestClient
) {
    private val _serverInfo = MutableStateFlow<HardwareInfoMessage?>(null)
    val serverInfo: StateFlow<HardwareInfoMessage?> = _serverInfo.asStateFlow()

    private val _suggestedConfig = MutableStateFlow<SuggestedConfigMessage?>(null)
    val suggestedConfig: StateFlow<SuggestedConfigMessage?> = _suggestedConfig.asStateFlow()

    private var messageJob: Job? = null
    private var binaryJob: Job? = null
    private var speedTestJob: Job? = null
    private var listeningScope: CoroutineScope? = null

    companion object {
        private const val TAG = "PhaseOneHandler"
    }

    fun startListening(scope: CoroutineScope, displayMetrics: DisplayMetrics) {
        listeningScope = scope
        
        messageJob?.cancel()
        messageJob = scope.launch {
            webSocketClient.textMessages.collect { text ->
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
        val type = MessageParser.getMessageType(text) ?: return

        when (type) {
            "hardware_info" -> {
                Log.d(TAG, "Received hardware_info")
                val msg = MessageParser.parse<HardwareInfoMessage>(text) ?: return
                _serverInfo.value = msg

                connectionStateRepo.tryTransition(ConnectionState.AwaitingHardwareInfo)

                val codecs = codecDetector.detectCapabilities(displayMetrics)
                val ack = HardwareInfoAckMessage(
                    clientCodecs = codecs,
                    perTrackPc = true
                )
                webSocketClient.sendText(MessageParser.serialize(ack))

                // Speed test removed — wait directly for suggested_config
                connectionStateRepo.tryTransition(ConnectionState.AwaitingSuggestedConfig)
            }

            "suggested_config" -> {
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
    }

    fun sendProceed() {
        val proceed = ProceedMessage(phase = 2)
        webSocketClient.sendText(MessageParser.serialize(proceed))
        connectionStateRepo.tryTransition(ConnectionState.SendingDisplayConfig)
    }

    fun reset() {
        messageJob?.cancel()
        binaryJob?.cancel()
        speedTestJob?.cancel()
        messageJob = null
        binaryJob = null
        speedTestJob = null
        listeningScope = null
        _serverInfo.value = null
        _suggestedConfig.value = null
        speedTestClient.reset()
    }
}
