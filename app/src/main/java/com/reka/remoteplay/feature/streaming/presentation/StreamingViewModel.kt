package com.reka.remoteplay.feature.streaming.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reka.remoteplay.core.model.PauseMonitorMessage
import com.reka.remoteplay.core.model.PauseStreamingMessage
import com.reka.remoteplay.core.model.ResumeMonitorMessage
import com.reka.remoteplay.core.model.ResumeStreamingMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.CursorRenderer
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
    private val cursorRenderer: CursorRenderer
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

    private val _showKeyboard = MutableStateFlow(false)
    val showKeyboard: StateFlow<Boolean> = _showKeyboard.asStateFlow()

    private var streamingInitialized = false

    fun initStreaming() {
        if (streamingInitialized) return
        streamingInitialized = true

        val monitorList = monitors.value
        videoDecoderManager.initialize(monitorList.size, "H265")
        videoDecoderManager.startFrameCollection(viewModelScope)

        // Collect cursor data from WebRTC
        viewModelScope.launch(Dispatchers.Default) {
            webRtcManager.cursorData.collect { data ->
                cursorRenderer.handleCursorData(data)
            }
        }

        viewModelScope.launch {
            phaseTwoHandler.iceReady.first { it }
            phaseTwoHandler.sendProceedPhase3()
            phaseTwoHandler.sendStartStreaming()

            // Pause all non-active monitors so resume_monitor will trigger IDR later
            val monitorList = monitors.value
            monitorList.forEachIndexed { index, _ ->
                if (index != 0) {
                    webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = index)))
                }
            }

            // Confine cursor to active monitor + nudge
            delay(300)
            webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(0))
            nudgeMouse()
        }
    }

    fun switchMonitor(index: Int) {
        val current = activeMonitor.value
        if (current == index) return

        // Pause old monitor
        webSocketClient.sendText(MessageParser.serialize(PauseMonitorMessage(monitorIndex = current)))

        // Swap decoder on persistent Surface (synchronous)
        videoDecoderManager.switchMonitor(index)

        // Resume new monitor — server sends IDR because it was paused
        webSocketClient.sendText(MessageParser.serialize(ResumeMonitorMessage(monitorIndex = index)))

        // Confine cursor to new monitor
        webRtcManager.sendInput(InputProtocol.encodeFocusMonitor(index))

        // Wait for first frame, THEN warp cursor + nudge
        viewModelScope.launch {
            videoDecoderManager.firstFrameReceived.first { index in it }
            sendWarpCursor(index, 0.5f, 0.5f)
            nudgeMouse()
        }
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

    fun toggleKeyboard() {
        _showKeyboard.value = !_showKeyboard.value
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

    fun sendKey(virtualKey: Short, down: Boolean) {
        webRtcManager.sendInput(InputProtocol.encodeKey(virtualKey, down))
    }

    fun sendText(text: String) {
        webRtcManager.sendInput(InputProtocol.encodeText(text))
    }

    fun sendClick(monitorIndex: Int, u: Float, v: Float) {
        sendWarpCursor(monitorIndex, u, v)
        sendMouseButton(0.toByte(), true)
        sendMouseButton(0.toByte(), false)
    }

    fun sendRightClick(monitorIndex: Int, u: Float, v: Float) {
        sendWarpCursor(monitorIndex, u, v)
        sendMouseButton(1.toByte(), true)
        sendMouseButton(1.toByte(), false)
    }

    /** Pause streaming and go back to config screen (keep connection alive) */
    fun pauseAndGoBack() {
        // Release cursor confinement before pausing
        webRtcManager.sendInput(InputProtocol.encodeReleaseCursorConfinement())
        webSocketClient.sendText(MessageParser.serialize(PauseStreamingMessage()))
        videoDecoderManager.releaseAll()
        streamingInitialized = false
        // Transition back to ConfiguringSettings so ConfigReviewScreen shows "Resume"
        connectionStateRepo.forceTransition(ConnectionState.ConfiguringSettings())
    }

    /** Full disconnect */
    fun disconnect() {
        videoDecoderManager.releaseAll()
        webRtcManager.dispose()
        webSocketClient.disconnect()
        connectionStateRepo.reset()
    }

    override fun onCleared() {
        super.onCleared()
        videoDecoderManager.releaseAll()
    }
}
