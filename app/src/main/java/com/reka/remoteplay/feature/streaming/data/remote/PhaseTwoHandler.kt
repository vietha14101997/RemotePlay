package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.*
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhaseTwoHandler @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val webRtcManager: WebRtcManager,
    private val cursorRenderer: CursorRenderer
) {
    private val _monitors = MutableStateFlow<List<MonitorInfoDto>>(emptyList())
    val monitors: StateFlow<List<MonitorInfoDto>> = _monitors.asStateFlow()

    private val _iceReady = MutableStateFlow(false)
    val iceReady: StateFlow<Boolean> = _iceReady.asStateFlow()

    private val _configuredFps = MutableStateFlow(60)
    val configuredFps: StateFlow<Int> = _configuredFps.asStateFlow()

    fun setConfiguredFps(fps: Int) { _configuredFps.value = fps }

    private val _configuredCodec = MutableStateFlow("H265")
    val configuredCodec: StateFlow<String> = _configuredCodec.asStateFlow()

    fun setConfiguredCodec(codec: String) { _configuredCodec.value = codec }

    private val _availableFpsOptions = MutableStateFlow(listOf(30, 60))
    val availableFpsOptions: StateFlow<List<Int>> = _availableFpsOptions.asStateFlow()

    fun setAvailableFpsOptions(options: List<Int>) { _availableFpsOptions.value = options }

    private var messageJob: Job? = null

    companion object {
        private const val TAG = "PhaseTwoHandler"
    }

    fun startListening(scope: CoroutineScope) {
        // Initialize WebRTC
        webRtcManager.initialize()

        // Wire ICE candidate callbacks to send via WebSocket
        webRtcManager.onMainIceCandidate = { candidate ->
            val msg = CandidateMessage(
                monitorIndex = 0,
                candidate = candidate.sdp
            )
            webSocketClient.sendText(MessageParser.serialize(msg))
        }

        webRtcManager.onVideoIceCandidate = { monitorIndex, candidate ->
            val msg = VideoCandidateMessage(
                monitorIndex = monitorIndex,
                candidate = candidate.sdp
            )
            webSocketClient.sendText(MessageParser.serialize(msg))
        }

        // Listen for WebSocket messages
        messageJob?.cancel()
        messageJob = scope.launch {
            webSocketClient.textMessages.collect { text ->
                handleMessage(text)
            }
        }
    }

    private fun handleMessage(text: String) {
        val type = MessageParser.getMessageType(text) ?: return

        when (type) {
            "config_complete" -> {
                val msg = MessageParser.parse<ConfigCompleteMessage>(text) ?: return
                _monitors.value = msg.monitors
                Log.d(TAG, "Config complete: ${msg.monitors.size} monitors, captureReady=${msg.captureReady}")

                connectionStateRepo.tryTransition(ConnectionState.AwaitingSetupComplete)
                connectionStateRepo.tryTransition(ConnectionState.IceNegotiating)

                // Create main PC and send offer to server
                createMainPcAndOffer()
            }

            "answer" -> {
                // Server's answer for our main PC offer
                val msg = MessageParser.parse<AnswerMessage>(text) ?: return
                Log.d(TAG, "Received main PC answer")
                webRtcManager.handleMainAnswer(msg.sdp)
            }

            "video_offer" -> {
                // Server's offer for a video PC (per-monitor)
                val msg = MessageParser.parse<VideoOfferMessage>(text) ?: return
                Log.d(TAG, "Received video_offer for monitor ${msg.monitorIndex}")
                handleVideoOffer(msg.monitorIndex, msg.sdp)
            }

            "candidate" -> {
                // ICE candidate for main PC
                val msg = MessageParser.parse<CandidateMessage>(text) ?: return
                webRtcManager.addMainIceCandidate(null, 0, msg.candidate)
            }

            "video_candidate" -> {
                // ICE candidate for a video PC
                val msg = MessageParser.parse<VideoCandidateMessage>(text) ?: return
                webRtcManager.addVideoIceCandidate(msg.monitorIndex, null, 0, msg.candidate)
            }

            "ice_ready" -> {
                val msg = MessageParser.parse<IceReadyMessage>(text) ?: return
                Log.d(TAG, "ICE ready: ${msg.monitorCount} monitors")
                _iceReady.value = true
                connectionStateRepo.tryTransition(ConnectionState.ReadyToStream)
            }

            "streaming_started" -> {
                Log.d(TAG, "Streaming started")
                connectionStateRepo.tryTransition(ConnectionState.Streaming)
            }

            "cursor_image" -> {
                val msg = MessageParser.parse<CursorImageMessage>(text) ?: return
                cursorRenderer.handleCursorImage(
                    cursorId = msg.cursorId,
                    width = msg.width,
                    height = msg.height,
                    hotspotX = msg.hotspotX,
                    hotspotY = msg.hotspotY,
                    imageBase64 = msg.imageBase64
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

    private fun createMainPcAndOffer() {
        webRtcManager.createMainPcOffer { offerSdp ->
            val msg = OfferMessage(monitorIndex = 0, sdp = offerSdp)
            webSocketClient.sendText(MessageParser.serialize(msg))
            Log.d(TAG, "Sent main PC offer to server")
        }
    }

    private fun handleVideoOffer(monitorIndex: Int, offerSdp: String) {
        webRtcManager.handleVideoOffer(monitorIndex, offerSdp) { answerSdp ->
            val msg = VideoAnswerMessage(monitorIndex = monitorIndex, sdp = answerSdp)
            webSocketClient.sendText(MessageParser.serialize(msg))
            Log.d(TAG, "Sent video_answer for monitor $monitorIndex")
        }
    }

    fun sendStartStreaming() {
        val msg = StartStreamingMessage()
        webSocketClient.sendText(MessageParser.serialize(msg))
        connectionStateRepo.tryTransition(ConnectionState.StartingStream)
        Log.d(TAG, "Sent start_streaming")
    }

    fun sendProceedPhase3() {
        val msg = ProceedMessage(phase = 3)
        webSocketClient.sendText(MessageParser.serialize(msg))
        Log.d(TAG, "Sent proceed phase 3")
    }

    fun reset() {
        messageJob?.cancel()
        messageJob = null
        webRtcManager.dispose()
        _monitors.value = emptyList()
        _iceReady.value = false
    }
}
