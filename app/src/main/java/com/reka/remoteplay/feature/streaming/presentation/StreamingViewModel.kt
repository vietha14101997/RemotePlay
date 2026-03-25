package com.reka.remoteplay.feature.streaming.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reka.remoteplay.core.model.PauseMonitorMessage
import com.reka.remoteplay.core.model.PauseStreamingMessage
import com.reka.remoteplay.core.model.ResumeMonitorMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.AudioPlayer
import com.reka.remoteplay.feature.streaming.data.remote.CursorRenderer
import com.reka.remoteplay.feature.streaming.data.remote.ExternalInputHandler
import com.reka.remoteplay.feature.streaming.data.remote.PhaseTwoHandler
import com.reka.remoteplay.feature.streaming.data.remote.VideoDecoderManager
import com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamingViewModel @Inject constructor(
    application: Application,
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
    val rttMs = webSocketClient.rttMs
    val cursorState = cursorRenderer.cursorState
    val cursorImage = cursorRenderer.cursorImage

    private val _showUI = MutableStateFlow(false)
    val showUI: StateFlow<Boolean> = _showUI.asStateFlow()

    private val _streamFps = MutableStateFlow(phaseTwoHandler.configuredFps.value)
    val streamFps: StateFlow<Int> = _streamFps.asStateFlow()
    val availableFpsOptions = phaseTwoHandler.availableFpsOptions

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    // Text caret position from server (for keyboard side placement)
    private val _caretU = MutableStateFlow(0.5f)

    // Tracks active double-tap-drag (for hiding cursor overlay during drag)
    private val _isDragging = MutableStateFlow(false)
    val isDragging: StateFlow<Boolean> = _isDragging.asStateFlow()

    fun setDragging(dragging: Boolean) {
        _isDragging.value = dragging
    }

    private var streamingInitialized = false

    fun initStreaming() {
        if (streamingInitialized) return
        streamingInitialized = true

        // Detect resume: VideoDecoderManager is @Singleton, survives ViewModel recreation.
        // If it has cached codec configs, this is a resume (not first start).
        val isResume = videoDecoderManager.hasCachedCodecConfigs()

        val monitorList = monitors.value
        val fps = phaseTwoHandler.configuredFps.value
        val codec = phaseTwoHandler.configuredCodec.value
        videoDecoderManager.initialize(monitorList.size, codec, fps)
        videoDecoderManager.startFrameCollection()

        // Start audio playback (DataChannel path with Opus decode)
        audioPlayer.startDataChannelAudio(viewModelScope)

        // Collect cursor data from WebRTC
        viewModelScope.launch(Dispatchers.Default) {
            webRtcManager.cursorData.collect { data ->
                cursorRenderer.handleCursorData(data)
            }
        }

        // Listen for server messages (foreground monitor switch, caret position)
        viewModelScope.launch {
            webSocketClient.textMessages.collect { text ->
                if (text.contains("foreground_monitor")) {
                    val idx = "\"monitorIndex\"\\s*:\\s*(\\d+)".toRegex()
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (idx != null && idx != videoDecoderManager.activeMonitor.value) {
                        switchMonitor(idx)
                    }
                } else if (text.contains("codec_changed")) {
                    val actual = "\"actualCodec\"\\s*:\\s*\"(\\w+)\"".toRegex()
                        .find(text)?.groupValues?.getOrNull(1)
                    if (actual != null) {
                        videoDecoderManager.changeCodec(actual)
                        // After decoder recreated with new codec, request fresh keyframe
                        // by doing pause+resume on active monitor → server sends codec config + IDR
                        val activeIdx = videoDecoderManager.activeMonitor.value
                        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = activeIdx)))
                        delay(50)
                        webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = activeIdx)))
                    }
                } else if (text.contains("caret_position")) {
                    val u = "\"u\"\\s*:\\s*([0-9.]+)".toRegex()
                        .find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    if (u != null) _caretU.value = u
                }
            }
        }

        if (isResume) {
            // Resume path: callbacks wired FIRST, then request keyframe from server.
            // This avoids the race where server sends IDR before client is ready.
            val activeIdx = videoDecoderManager.activeMonitor.value
            viewModelScope.launch {
                delay(200) // Let Surface + decoder setup complete

                // Pause then resume active monitor → server sends codec config + IDR
                webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = activeIdx)))
                delay(100)
                webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = activeIdx)))

                delay(200)
                webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(activeIdx))
                sendWarpCursor(activeIdx, 0.5f, 0.5f)
                nudgeMouse()
                externalInputHandler.setEnabled(true)
            }
        } else {
            // First start path: wait for ICE, send phase3 proceed
            viewModelScope.launch {
                phaseTwoHandler.iceReady.first { it }
                phaseTwoHandler.sendProceedPhase3()
                phaseTwoHandler.sendStartStreaming()

                // Pause all non-active monitors so resume_monitor will trigger IDR later
                val monitorsValue = monitors.value
                monitorsValue.forEachIndexed { index, _ ->
                    if (index != 0) {
                        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = index)))
                    }
                }

                // Confine cursor to active monitor, warp to center, nudge
                delay(300)
                webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(0))
                sendWarpCursor(0, 0.5f, 0.5f)
                nudgeMouse()

                // Enable external input devices (mouse/keyboard/gamepad)
                externalInputHandler.setEnabled(true)
            }
        }
    }

    fun switchMonitor(index: Int) {
        val current = activeMonitor.value
        if (current == index) return

        // Capture current cursor position (normalized 0..1)
        val cursor = cursorRenderer.cursorState.value

        // Swap decoder on persistent Surface (synchronous) FIRST
        // This ensures decoder is ready before frames arrive.
        videoDecoderManager.switchMonitor(index)

        // Pause old monitor
        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = current)))

        // Resume new monitor AFTER decoder is ready → server sends codec_config + IDR
        // This avoids the race where codec_config arrives before decoder exists.
        webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = index)))

        // Confine cursor to new monitor + warp immediately (no wait for first frame)
        webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(index))
        sendWarpCursor(index, cursor.u, cursor.v)
    }

    /** Re-send cursor confinement for active monitor. Called after drag ends
     *  because Windows drag operation overrides ClipCursor. */
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

    private fun nudgeMouse() {
        sendMouseMove(1, 0)
        sendMouseMove(-1, 0)
    }

    fun toggleUI() {
        _showUI.value = !_showUI.value
    }

    fun hideUI() {
        _showUI.value = false
    }

    private val _showKeyboard = MutableStateFlow(false)
    val showKeyboard: StateFlow<Boolean> = _showKeyboard.asStateFlow()

    fun toggleKeyboard() {
        _showKeyboard.value = !_showKeyboard.value
    }

    fun changeFps(newFps: Int) {
        _streamFps.value = newFps
        phaseTwoHandler.setConfiguredFps(newFps)
        val msg = com.reka.remoteplay.core.model.UpdateConfigMessage(fps = newFps)
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

    // ===== Input Methods =====

    fun sendMouseMove(dx: Short, dy: Short) {
        webRtcManager.sendInput(InputProtocol.encodeMouseMove(dx, dy))
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

    /** Pause streaming and go back to config screen (keep connection alive) */
    fun pauseAndGoBack() {
        externalInputHandler.setEnabled(false)
        audioPlayer.stop()
        webRtcManager.sendInput(InputProtocol.encodeReleaseCursorConfinement())
        webSocketClient.sendText(MessageParser.serialize(PauseStreamingMessage()))
        videoDecoderManager.pauseForResume() // Keep codec configs for fast resume
        streamingInitialized = false
        connectionStateRepo.forceTransition(ConnectionState.ConfiguringSettings)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        // If paused (has cached configs), pauseForResume() already cleaned up.
        // Don't destroy codec configs needed for resume.
        if (!videoDecoderManager.hasCachedCodecConfigs()) {
            videoDecoderManager.releaseAll()
        }
    }
}
