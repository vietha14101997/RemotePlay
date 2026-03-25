package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun StreamingRoute(
    onBack: () -> Unit,
    viewModel: StreamingViewModel = hiltViewModel()
) {
    val monitors by viewModel.monitors.collectAsState()
    val activeMonitor by viewModel.activeMonitor.collectAsState()
    val showUI by viewModel.showUI.collectAsState()
    val firstFrames by viewModel.firstFrameReceived.collectAsState()
    val rttMs by viewModel.rttMs.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val showKeyboard by viewModel.showKeyboard.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val cursorState by viewModel.cursorState.collectAsState()
    val isDragging by viewModel.isDragging.collectAsState()
    val cursorImage by viewModel.cursorImage.collectAsState()
    val streamFps by viewModel.streamFps.collectAsState()
    val availableFpsOptions by viewModel.availableFpsOptions.collectAsState()

    StreamingScreen(
        monitors = monitors,
        activeMonitor = activeMonitor,
        showUI = showUI,
        firstFrames = firstFrames,
        rttMs = rttMs,
        connectionState = connectionState,
        showKeyboard = showKeyboard,
        isMuted = isMuted,
        isMicEnabled = isMicEnabled,
        cursorState = cursorState,
        isDragging = isDragging,
        cursorImage = cursorImage,
        onBack = onBack,
        onInitStreaming = viewModel::initStreaming,
        onPauseAndGoBack = viewModel::pauseAndGoBack,
        onSwitchMonitor = viewModel::switchMonitor,
        onToggleUI = viewModel::toggleUI,
        onHideUI = viewModel::hideUI,
        onToggleMute = viewModel::toggleMute,
        onToggleMic = viewModel::toggleMic,
        onToggleKeyboard = viewModel::toggleKeyboard,
        onSendKey = viewModel::sendKey,
        onSendText = viewModel::sendText,
        onSendMouseMove = viewModel::sendMouseMove,
        onSendMouseButton = viewModel::sendMouseButton,
        onSendMouseWheel = viewModel::sendMouseWheel,
        onSetDragging = viewModel::setDragging,
        onReConfineCursor = viewModel::reConfineCursor,
        streamFps = streamFps,
        availableFpsOptions = availableFpsOptions,
        onChangeFps = viewModel::changeFps,
        onSurfaceCreated = { viewModel.videoDecoderManager.setActiveSurface(it) },
        onSurfaceDestroyed = { viewModel.videoDecoderManager.onSurfaceDestroyed() }
    )
}
