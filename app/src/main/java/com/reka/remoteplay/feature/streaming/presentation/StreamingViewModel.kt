package com.reka.remoteplay.feature.streaming.presentation

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reka.remoteplay.R
import com.reka.remoteplay.core.model.*
import com.reka.remoteplay.core.util.EncoderResolutionCalculator
import com.reka.remoteplay.core.util.QualityPreset
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.*
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamingViewModel @Inject constructor(
    private val application: Application,
    private val webRtcManager: WebRtcManager,
    val videoDecoderManager: VideoDecoderManager,
    private val connectionStateRepo: ConnectionStateRepository,
    private val phaseTwoHandler: PhaseTwoHandler,
    private val webSocketClient: WebSocketClient,
    private val cursorRenderer: CursorRenderer,
    private val externalInputHandler: ExternalInputHandler,
    private val audioPlayer: AudioPlayer
) : AndroidViewModel(application) {

    val connectionState = connectionStateRepo.state
    val monitors = phaseTwoHandler.monitors
    val activeMonitor = videoDecoderManager.activeMonitor
    val firstFrameReceived = videoDecoderManager.firstFrameReceived
    val rttMs = webRtcManager.p2pRttMs
    val cursorState = cursorRenderer.cursorState
    val cursorImage = cursorRenderer.cursorImage
    val iceConnectionState = webRtcManager.iceConnectionState

    private val _showUI = MutableStateFlow(false)
    val showUI: StateFlow<Boolean> = _showUI.asStateFlow()

    // Viewer quality preset — controls server-side frame skip
    private val _viewerQuality = MutableStateFlow("high") // "high", "medium", "low"
    val viewerQuality: StateFlow<String> = _viewerQuality.asStateFlow()

    fun setViewerQuality(quality: String) {
        _viewerQuality.value = quality
        // M2: use type-safe model + MessageParser instead of raw JSON string
        webSocketClient.sendText(MessageParser.serialize(SetQualityMessage(quality = quality)))
    }

    private val _streamFps = MutableStateFlow(phaseTwoHandler.configuredFps.value)
    val streamFps: StateFlow<Int> = _streamFps.asStateFlow()
    val availableFpsOptions = phaseTwoHandler.availableFpsOptions

    private val _qualityPreset = MutableStateFlow(phaseTwoHandler.qualityPreset.value)
    val qualityPreset: StateFlow<QualityPreset> = _qualityPreset.asStateFlow()

    val qualityPresetHeights: Map<QualityPreset, Int>
        get() {
            val sw = phaseTwoHandler.screenWidth.value
            val sh = phaseTwoHandler.screenHeight.value
            val maxQH = phaseTwoHandler.maxQualityHeight.value
            return QualityPreset.entries.associateWith { preset ->
                EncoderResolutionCalculator.calculate(sw, sh, preset, maxQH).second
            }
        }

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _caretU = MutableStateFlow(0.5f)

    private val _isDragging = MutableStateFlow(false)
    val isDragging: StateFlow<Boolean> = _isDragging.asStateFlow()

    fun setDragging(dragging: Boolean) {
        _isDragging.value = dragging
    }

    private var streamingInitialized = false

    fun initStreaming() {
        if (streamingInitialized) return
        streamingInitialized = true

        val isResume = videoDecoderManager.hasCachedCodecConfigs()
        val monitorList = monitors.value
        val fps = phaseTwoHandler.configuredFps.value
        val codec = phaseTwoHandler.configuredCodec.value
        
        videoDecoderManager.initialize(monitorList.size, codec, fps)
        videoDecoderManager.startFrameCollection()

        // Start P2P ping measurement via DataChannel
        webRtcManager.startPingLoop(viewModelScope)

        // Signal server when decoder is ready so it re-sends codec config + IDR
        videoDecoderManager.onDecoderReady = { monitorIndex ->
            val msg = DecoderReadyMessage(monitorIndex = monitorIndex)
            webSocketClient.sendText(MessageParser.serialize(msg))
        }

        // UI Feedback for codec change
        videoDecoderManager.onCodecChanged = { newCodec ->
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    application,
                    application.getString(R.string.codec_changed, newCodec),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        audioPlayer.startDataChannelAudio(viewModelScope)

        viewModelScope.launch(Dispatchers.Default) {
            webRtcManager.cursorData.collect { data ->
                cursorRenderer.handleCursorData(data)
            }
        }

        viewModelScope.launch {
            webSocketClient.textMessages.collect { text ->
                when (val msg = MessageParser.parseServerMessage(text)) {
                    is ForegroundMonitorMessage -> {
                        if (msg.monitorIndex != videoDecoderManager.activeMonitor.value) {
                            switchMonitor(msg.monitorIndex)
                        }
                    }
                    is CodecChangedMessage -> {
                        videoDecoderManager.changeCodec(msg.actualCodec)
                        val activeIdx = videoDecoderManager.activeMonitor.value
                        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = activeIdx)))
                        // Server needs 1-2 frames to flush old codec before sending IDR
                        delay(CODEC_SWITCH_FLUSH_MS)
                        webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = activeIdx)))
                    }
                    is CaretPositionMessage -> {
                        _caretU.value = msg.u
                    }
                }
            }
        }

        if (isResume) {
            val activeIdx = videoDecoderManager.activeMonitor.value
            viewModelScope.launch {
                // Wait for decoder settle after PauseMonitor handshake
                delay(RESUME_SETTLE_MS)
                webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = activeIdx)))
                delay(PAUSE_BETWEEN_RESUME_MS)
                webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = activeIdx)))
                delay(RESUME_SETTLE_MS)
                webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(activeIdx))
                sendWarpCursor(activeIdx, 0.5f, 0.5f)
                externalInputHandler.setEnabled(true)
            }
        } else {
            viewModelScope.launch {
                phaseTwoHandler.iceReady.first { it }
                phaseTwoHandler.sendProceedPhase3()
                phaseTwoHandler.sendStartStreaming()

                monitors.value.forEachIndexed { index, _ ->
                    if (index != 0) {
                        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = index)))
                    }
                }

                // Allow ICE to stabilise before injecting focus + cursor warp
                delay(FOCUS_WARP_DELAY_MS)
                webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(0))
                sendWarpCursor(0, 0.5f, 0.5f)
                externalInputHandler.setEnabled(true)
            }
        }
    }

    fun switchMonitor(index: Int) {
        val current = activeMonitor.value
        if (current == index) return
        val cursor = cursorRenderer.cursorState.value
        videoDecoderManager.switchMonitor(index)
        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = current)))
        webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = index)))
        webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(index))
        sendWarpCursor(index, cursor.u, cursor.v)
    }

    fun reConfineCursor() {
        val activeIdx = videoDecoderManager.activeMonitor.value
        webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(activeIdx))
    }

    fun toggleMute() {
        val newValue = !_isMuted.value
        _isMuted.value = newValue
        audioPlayer.setMuted(newValue)
    }

    fun toggleMic() {
        _isMicEnabled.value = !_isMicEnabled.value
    }

    fun toggleUI() {
        _showUI.value = !_showUI.value
    }

    fun hideUI() {
        _showUI.value = false
    }

    private val _showKeyboard = MutableStateFlow(false)
    val showKeyboard: StateFlow<Boolean> = _showKeyboard.asStateFlow()

    private var mouseSensitivity = 1.0f

    fun setMouseSensitivity(sensitivity: Float) {
        mouseSensitivity = sensitivity
    }

    fun toggleKeyboard() {
        _showKeyboard.value = !_showKeyboard.value
    }

    fun changeFps(newFps: Int) {
        _streamFps.value = newFps
        phaseTwoHandler.setConfiguredFps(newFps)
        val msg = UpdateConfigMessage(fps = newFps)
        webSocketClient.sendText(MessageParser.serialize(msg))
    }

    fun changeQualityPreset(preset: QualityPreset) {
        _qualityPreset.value = preset
        phaseTwoHandler.setQualityPreset(preset)
        val screenW = phaseTwoHandler.screenWidth.value
        val screenH = phaseTwoHandler.screenHeight.value
        val msg = UpdateConfigMessage(
            qualityPreset = preset.name,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        webSocketClient.sendText(MessageParser.serialize(msg))
    }

    fun sendText(text: String) {
        if (text.isNotEmpty()) {
            webRtcManager.sendInput(InputProtocol.encodeText(text))
        }
    }

    fun sendKey(vk: Int, down: Boolean) {
        webRtcManager.sendInput(InputProtocol.encodeKey(vk.toShort(), down))
    }

    fun sendMouseMove(dx: Short, dy: Short) {
        val scaledDx = (dx * mouseSensitivity).toInt().toShort()
        val scaledDy = (dy * mouseSensitivity).toInt().toShort()
        webRtcManager.sendInput(InputProtocol.encodeMouseMove(scaledDx, scaledDy))
    }

    fun sendMouseButton(button: Byte, down: Boolean) {
        webRtcManager.sendInput(InputProtocol.encodeMouseButton(button, down))
    }

    fun sendMouseWheel(deltaY: Short, deltaX: Short = 0) {
        webRtcManager.sendInput(InputProtocol.encodeMouseWheel(deltaY, deltaX))
    }

    fun sendWarpCursor(monitorIndex: Int, u: Float, v: Float) {
        webRtcManager.sendInput(InputProtocol.encodeWarpCursor(monitorIndex.toByte(), u, v))
    }

    fun pauseAndGoBack() {
        externalInputHandler.setEnabled(false)
        audioPlayer.stop()
        webRtcManager.sendInput(InputProtocol.encodeReleaseCursorConfinement())
        webSocketClient.sendText(MessageParser.serialize(PauseStreamingMessage()))
        videoDecoderManager.pauseForResume()
        streamingInitialized = false
        connectionStateRepo.forceTransition(ConnectionState.ConfiguringSettings)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        externalInputHandler.dispose()
        webRtcManager.stopPingLoop()
        if (!videoDecoderManager.hasCachedCodecConfigs()) {
            videoDecoderManager.releaseAll()
            webRtcManager.dispose()
        }
    }

    private companion object {
        /** Server needs 1-2 frames to flush old codec before sending the next IDR. */
        const val CODEC_SWITCH_FLUSH_MS = 50L
        /** Decoder needs time to settle after PauseMonitor/ResumeMonitor handshake. */
        const val RESUME_SETTLE_MS = 200L
        /** Gap between Pause and Resume signals to avoid race on the server encoder. */
        const val PAUSE_BETWEEN_RESUME_MS = 100L
        /** Allow ICE to stabilise before injecting focus warp + enabling input. */
        const val FOCUS_WARP_DELAY_MS = 300L
    }
}
