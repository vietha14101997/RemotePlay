package com.reka.remoteplay.feature.streaming.presentation

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.reka.remoteplay.R
import com.reka.remoteplay.core.model.MonitorInfoDto
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.streaming.data.remote.CursorRenderer
import com.reka.remoteplay.ui.theme.*
import kotlinx.coroutines.delay

data class StreamingUiState(
    val monitors: List<MonitorInfoDto> = emptyList(),
    val activeMonitor: Int = 0,
    val showUI: Boolean = false,
    val firstFrames: Set<Int> = emptySet(),
    val rttMs: Float = 0f,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val showKeyboard: Boolean = false,
    val isMuted: Boolean = false,
    val isMicEnabled: Boolean = false,
    val cursorState: CursorRenderer.CursorState = CursorRenderer.CursorState(),
    val isDragging: Boolean = false,
    val cursorImage: CursorRenderer.CursorImageEntry? = null,
    val streamFps: Int = 60,
    val availableFpsOptions: List<Int> = listOf(30, 60),
    val qualityPreset: com.reka.remoteplay.core.util.QualityPreset = com.reka.remoteplay.core.util.QualityPreset.Quality,
    val qualityPresetHeights: Map<com.reka.remoteplay.core.util.QualityPreset, Int> = emptyMap(),
    val isViewerMode: Boolean = false,
    val viewerQuality: String = "high"
)

data class StreamingUiActions(
    val onBack: () -> Unit = {},
    val onInitStreaming: () -> Unit = {},
    val onPauseAndGoBack: () -> Unit = {},
    val onSwitchMonitor: (Int) -> Unit = {},
    val onToggleUI: () -> Unit = {},
    val onHideUI: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleMic: () -> Unit = {},
    val onToggleKeyboard: () -> Unit = {},
    val onSendKey: (Int, Boolean) -> Unit = { _, _ -> },
    val onSendText: (String) -> Unit = {},
    val onSendMouseMove: (Short, Short) -> Unit = { _, _ -> },
    val onSendMouseButton: (Byte, Boolean) -> Unit = { _, _ -> },
    val onSendMouseWheel: (Short, Short) -> Unit = { _, _ -> },
    val onSetDragging: (Boolean) -> Unit = {},
    val onReConfineCursor: () -> Unit = {},
    val onChangeFps: (Int) -> Unit = {},
    val onChangeQualityPreset: (com.reka.remoteplay.core.util.QualityPreset) -> Unit = {},
    val onChangeViewerQuality: (String) -> Unit = {}
)

@Composable
fun StreamingScreen(
    state: StreamingUiState,
    actions: StreamingUiActions,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isHardwareKeyboardAvailable = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    
    var uiHidden by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }

    val pressBackToPauseMsg = stringResource(R.string.press_back_to_pause)
    val pressBackToShowUiMsg = stringResource(R.string.press_back_to_show_ui)

    // Zoom & Pan state
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    
    // Joystick deltas
    val zoomDelta = remember { mutableFloatStateOf(0f) }
    val moveDeltaX = remember { mutableFloatStateOf(0f) }
    val moveDeltaY = remember { mutableFloatStateOf(0f) }

    // Use rememberUpdatedState to ensure LaunchedEffect always uses latest delta values
    // without restarting the loop unnecessarily.
    val currentZoomDelta by rememberUpdatedState(zoomDelta.floatValue)
    val currentMoveDeltaX by rememberUpdatedState(moveDeltaX.floatValue)
    val currentMoveDeltaY by rememberUpdatedState(moveDeltaY.floatValue)

    LaunchedEffect(Unit) {
        while (true) {
            val zD = currentZoomDelta
            val mDX = currentMoveDeltaX
            val mDY = currentMoveDeltaY

            if (zD != 0f) {
                zoomScale = (zoomScale - zD * 0.03f).coerceIn(1f, 4f)
            }
            
            // Apply panning if zoomed in
            if (zoomScale > 1f && (mDX != 0f || mDY != 0f)) {
                panX = (panX - mDX * 0.02f).coerceIn(-1f, 1f)
                panY = (panY - mDY * 0.02f).coerceIn(-1f, 1f)
            } else if (zoomScale <= 1f) {
                panX = 0f
                panY = 0f
            }

            delay(16L)
        }
    }

    LaunchedEffect(state.showUI) {
        if (!state.showUI) {
            showQualityPicker = false
        }
    }

    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (uiHidden) {
            uiHidden = false
        } else if (backPressedOnce) {
            actions.onPauseAndGoBack()
        } else {
            backPressedOnce = true
            Toast.makeText(context, pressBackToPauseMsg, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(backPressedOnce) {
        if (backPressedOnce) {
            delay(2000)
            backPressedOnce = false
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }
    var lastSentText by remember { mutableStateOf("") }

    LaunchedEffect(state.showKeyboard, isHardwareKeyboardAvailable) {
        if (state.showKeyboard && !isHardwareKeyboardAvailable) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    LaunchedEffect(Unit) { actions.onInitStreaming() }

    LaunchedEffect(state.cursorState.monitorIndex) {
        if (!state.isDragging && state.cursorState.monitorIndex != state.activeMonitor) {
            actions.onSwitchMonitor(state.cursorState.monitorIndex)
        }
    }

    LaunchedEffect(state.connectionState) {
        when (state.connectionState) {
            is ConnectionState.ConfiguringSettings -> actions.onBack()
            is ConnectionState.Disconnected -> actions.onBack()
            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                if (newValue.composition != null) return@BasicTextField
                val committed = newValue.text
                val sent = lastSentText
                if (committed != sent) {
                    val common = committed.commonPrefixWith(sent).length
                    val toDelete = sent.length - common
                    val toSend = committed.substring(common)
                    repeat(toDelete) { actions.onSendKey(0x08, true); actions.onSendKey(0x08, false) }
                    for (ch in toSend) {
                        val vk = charToVirtualKey(ch)
                        if (vk != 0) { actions.onSendKey(vk, true); actions.onSendKey(vk, false) }
                        else { actions.onSendText(ch.toString()) }
                    }
                    lastSentText = committed
                }
                if (committed.length > 200) { textFieldValue = TextFieldValue(); lastSentText = "" }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.focusRequester(focusRequester).size(1.dp).offset(x = (-100).dp)
        )

        val activeMonitorInfo = state.monitors.getOrNull(state.activeMonitor)
        val videoAspectRatio = if (activeMonitorInfo != null) {
            activeMonitorInfo.width.toFloat() / activeMonitorInfo.height.toFloat().coerceAtLeast(1f)
        } else 16f / 9f

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspectRatio)
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panX * size.width * (zoomScale - 1f) / 2f
                    translationY = panY * size.height * (zoomScale - 1f) / 2f
                }
        ) {
            VideoSurface(
                onSurfaceCreated = onSurfaceCreated,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = Modifier.fillMaxSize()
            )
            if (!state.isDragging) {
                CursorOverlay(
                    cursorState = state.cursorState,
                    cursorImage = state.cursorImage,
                    desktopWidth = activeMonitorInfo?.width ?: 1920,
                    desktopHeight = activeMonitorInfo?.height ?: 1080,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (state.activeMonitor !in state.firstFrames) {
            Box(
                modifier = Modifier.fillMaxSize().background(AppOverlayBlack),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.waiting_for_video), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                }
            }
        }

        TouchpadLayer(
            onSendMouseMove = actions.onSendMouseMove,
            onSendMouseButton = actions.onSendMouseButton,
            onSendMouseWheel = actions.onSendMouseWheel,
            onSetDragging = actions.onSetDragging,
            onReConfineCursor = actions.onReConfineCursor,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(visible = !uiHidden, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp).size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.ping_ms_format, state.rttMs.toInt()),
                            color = when {
                                state.rttMs < 20 -> AppGreenLight
                                state.rttMs < 50 -> AppYellow
                                else -> AppRedLight
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MenuIconButton(
                            icon = Icons.Default.Menu,
                            tint = when {
                                state.rttMs < 20 -> AppGreenLight
                                state.rttMs < 50 -> AppYellow
                                else -> AppRedLight
                            },
                            onClick = actions.onToggleUI
                        )

                        AnimatedVisibility(
                            visible = state.showUI,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MenuIconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    tint = AppRedLight,
                                    onClick = actions.onPauseAndGoBack
                                )
                                MenuIconButton(
                                    icon = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    tint = if (state.isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    onClick = actions.onToggleMute
                                )
                                MenuIconButton(
                                    icon = if (state.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                    tint = if (state.isMicEnabled) AppGreenLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = actions.onToggleMic
                                )
                                MenuIconButton(
                                    icon = Icons.Default.VisibilityOff,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = {
                                        actions.onHideUI()
                                        uiHidden = true
                                        Toast.makeText(context, pressBackToShowUiMsg, Toast.LENGTH_SHORT).show()
                                    }
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable {
                                            showQualityPicker = !showQualityPicker
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Settings",
                                        tint = if (showQualityPicker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showQualityPicker,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.CenterHorizontally),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.CenterHorizontally),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.isViewerMode) {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf("low", "medium", "high").forEach { q ->
                                        QualityPresetButton(
                                            label = q.uppercase(),
                                            isActive = state.viewerQuality == q,
                                            onClick = { actions.onChangeViewerQuality(q); showQualityPicker = false }
                                        )
                                    }
                                }
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.reka.remoteplay.core.util.QualityPreset.entries.forEach { preset ->
                                        val height = state.qualityPresetHeights[preset]
                                        val label = if (height != null) "${preset.displayName} (${height}p)" else preset.displayName
                                        QualityPresetButton(
                                            label = label,
                                            isActive = state.qualityPreset == preset,
                                            onClick = { actions.onChangeQualityPreset(preset); showQualityPicker = false }
                                        )
                                    }
                                }
                            }
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    state.availableFpsOptions.forEach { fps ->
                                        FpsButton(
                                            fps = fps,
                                            isActive = state.streamFps == fps,
                                            onClick = { actions.onChangeFps(fps); showQualityPicker = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isHardwareKeyboardAvailable) {
                    FloatingCircleButton(
                        icon = Icons.Default.Keyboard,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = actions.onToggleKeyboard,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
                    )
                }

                if (state.monitors.size > 1) {
                    MonitorTabBar(
                        monitors = state.monitors,
                        activeIndex = state.activeMonitor,
                        onSelect = actions.onSwitchMonitor,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)
                    )
                }

                AnimatedVisibility(
                    visible = !state.showUI,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                ) {
                    VirtualJoystick(
                        onDelta = { _, dy -> zoomDelta.floatValue = dy },
                        onRelease = { zoomDelta.floatValue = 0f }
                    )
                }
                AnimatedVisibility(
                    visible = !state.showUI,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                ) {
                    VirtualJoystick(
                        onDelta = { dx, dy -> moveDeltaX.floatValue = dx; moveDeltaY.floatValue = dy },
                        onRelease = { moveDeltaX.floatValue = 0f; moveDeltaY.floatValue = 0f }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(modifier = Modifier.size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FpsButton(fps: Int, isActive: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text = "$fps", color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun QualityPresetButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.defaultMinSize(minWidth = 42.dp).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text = label, color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun FloatingCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), modifier = modifier.size(44.dp).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun VideoSurface(onSurfaceCreated: (Surface) -> Unit, onSurfaceDestroyed: () -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { onSurfaceCreated(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) { onSurfaceDestroyed() }
                })
            }
        },
        modifier = modifier
    )
}

@Composable
private fun TouchpadLayer(
    onSendMouseMove: (Short, Short) -> Unit,
    onSendMouseButton: (Byte, Boolean) -> Unit,
    onSendMouseWheel: (Short, Short) -> Unit,
    onSetDragging: (Boolean) -> Unit,
    onReConfineCursor: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isScrolling by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val secondDown = withTimeoutOrNull(150) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) return@withTimeoutOrNull true
                        }
                    }
                    if (secondDown != true) return@awaitEachGesture
                    isScrolling = true
                    var accumY = 0f; var accumX = 0f; var totalMoved = 0f; val scrollThreshold = 8f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) { isScrolling = false; break }
                        var avgDy = 0f; var avgDx = 0f
                        pressed.forEach { p -> avgDy += p.position.y - p.previousPosition.y; avgDx += p.position.x - p.previousPosition.x; p.consume() }
                        avgDy /= pressed.size; avgDx /= pressed.size
                        totalMoved += kotlin.math.abs(avgDx) + kotlin.math.abs(avgDy)
                        accumY += avgDy; accumX += avgDx
                        val scrollY = (accumY / scrollThreshold).toInt(); val scrollX = (accumX / scrollThreshold).toInt()
                        if (scrollY != 0) { onSendMouseWheel((scrollY * 40).toShort(), 0); accumY -= scrollY * scrollThreshold }
                        if (scrollX != 0) { onSendMouseWheel(0, (-(scrollX * 40)).toShort()); accumX -= scrollX * scrollThreshold }
                    }
                    if (totalMoved < slop * 2) { onSendMouseButton(1.toByte(), true); onSendMouseButton(1.toByte(), false) }
                }
            }
            .pointerInput(isScrolling) {
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        if (isScrolling) {
                            while (true) { val e = awaitPointerEvent(); if (e.changes.none { it.pressed }) break }
                            continue
                        }
                        var isDrag = false; var isDoubleTapDrag = false
                        val upOrDrag = withTimeoutOrNull(longPressMs) {
                            var totalDx = 0f; var totalDy = 0f
                            while (true) {
                                val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull() ?: continue
                                if (!pointer.pressed) { pointer.consume(); return@withTimeoutOrNull "up" }
                                val dx = pointer.position.x - pointer.previousPosition.x; val dy = pointer.position.y - pointer.previousPosition.y
                                totalDx += dx; totalDy += dy
                                if (totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) { isDrag = true; pointer.consume(); return@withTimeoutOrNull "drag" }
                            }
                        }
                        when (upOrDrag) {
                            null -> {
                                while (true) {
                                    val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull() ?: break
                                    if (!pointer.pressed) break
                                    val dx = pointer.position.x - pointer.previousPosition.x; val dy = pointer.position.y - pointer.previousPosition.y
                                    if (dx != 0f || dy != 0f) { onSendMouseMove(dx.toInt().toShort(), dy.toInt().toShort()) }
                                    pointer.consume()
                                }
                            }
                            "up" -> {
                                val secondDown = withTimeoutOrNull(doubleTapMs) { awaitFirstDown(requireUnconsumed = false) }
                                if (secondDown == null) { onSendMouseButton(0, true); onSendMouseButton(0, false) }
                                else {
                                    val secondResult = withTimeoutOrNull(longPressMs) {
                                        var totalDx = 0f; var totalDy = 0f
                                        while (true) {
                                            val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull() ?: continue
                                            if (!pointer.pressed) { pointer.consume(); return@withTimeoutOrNull "up" }
                                            val dx = pointer.position.x - pointer.previousPosition.x; val dy = pointer.position.y - pointer.previousPosition.y
                                            totalDx += dx; totalDy += dy
                                            if (totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) { pointer.consume(); return@withTimeoutOrNull "drag" }
                                        }
                                    }
                                    when (secondResult) {
                                        "up", null -> { onSendMouseButton(0, true); onSendMouseButton(0, false); onSendMouseButton(0, true); onSendMouseButton(0, false) }
                                        "drag" -> { isDoubleTapDrag = true; onSetDragging(true); onSendMouseButton(0, true) }
                                    }
                                }
                            }
                            "drag" -> { isDrag = true }
                        }
                        if (isDrag || isDoubleTapDrag) {
                            var accumX = 0f; var accumY = 0f
                            while (true) {
                                val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull() ?: continue
                                if (!pointer.pressed) {
                                    pointer.consume()
                                    if (isDoubleTapDrag) { onSendMouseButton(0, false); onSetDragging(false); onReConfineCursor() }
                                    break
                                }
                                accumX += pointer.position.x - pointer.previousPosition.x; accumY += pointer.position.y - pointer.previousPosition.y
                                val dx = accumX.toInt(); val dy = accumY.toInt()
                                if (dx != 0 || dy != 0) { onSendMouseMove(dx.toShort(), dy.toShort()); accumX -= dx; accumY -= dy }
                                pointer.consume()
                            }
                        }
                    }
                }
            }
    )
}

@Composable
private fun MonitorTabBar(monitors: List<MonitorInfoDto>, activeIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), modifier = modifier) {
        Column(modifier = Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            monitors.forEachIndexed { index, _ ->
                val isActive = index == activeIndex
                Surface(
                    shape = CircleShape,
                    color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent,
                    modifier = Modifier.size(36.dp).clickable { onSelect(index) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "${index + 1}", color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun charToVirtualKey(ch: Char): Int = when (ch) {
    in 'a'..'z' -> 0x41 + (ch - 'a')
    in 'A'..'Z' -> 0x41 + (ch - 'A')
    in '0'..'9' -> 0x30 + (ch - '0')
    ' ' -> 0x20
    '\n', '\r' -> 0x0D
    '\t' -> 0x09
    '-' -> 0xBD
    '=' -> 0xBB
    '[' -> 0xDB
    ']' -> 0xDD
    '\\' -> 0xDC
    ';' -> 0xBA
    '\'' -> 0xDE
    '`' -> 0xC0
    ',' -> 0xBC
    '.' -> 0xBE
    '/' -> 0xBF
    else -> 0
}

@Preview(name = "Streaming Screen Overlay", device = "spec:width=1280dp,height=800dp,orientation=landscape", showBackground = true)
@Composable
private fun StreamingScreenPreview() {
    RemotePlayTheme {
        StreamingScreen(
            state = StreamingUiState(
                monitors = listOf(MonitorInfoDto(0, "Main", 1920, 1080)),
                showUI = true,
                rttMs = 15f,
                streamFps = 60,
                qualityPreset = com.reka.remoteplay.core.util.QualityPreset.Quality
            ),
            actions = StreamingUiActions(),
            onSurfaceCreated = {},
            onSurfaceDestroyed = {}
        )
    }
}
