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
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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

@Composable
fun StreamingScreen(
    monitors: List<MonitorInfoDto>,
    activeMonitor: Int,
    showUI: Boolean,
    firstFrames: Set<Int>,
    rttMs: Float,
    connectionState: ConnectionState,
    showKeyboard: Boolean,
    isMuted: Boolean,
    isMicEnabled: Boolean,
    cursorState: CursorRenderer.CursorState,
    isDragging: Boolean,
    cursorImage: CursorRenderer.CursorImageEntry?,
    onBack: () -> Unit,
    onInitStreaming: () -> Unit,
    onPauseAndGoBack: () -> Unit,
    onSwitchMonitor: (Int) -> Unit,
    onToggleUI: () -> Unit,
    onHideUI: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onSendKey: (Int, Boolean) -> Unit,
    onSendText: (String) -> Unit,
    onSendMouseMove: (Short, Short) -> Unit,
    onSendMouseButton: (Byte, Boolean) -> Unit,
    onSendMouseWheel: (Short, Short) -> Unit,
    onSetDragging: (Boolean) -> Unit,
    onReConfineCursor: () -> Unit,
    streamFps: Int = 60,
    availableFpsOptions: List<Int> = listOf(30, 60),
    onChangeFps: (Int) -> Unit = {},
    qualityPreset: com.reka.remoteplay.core.util.QualityPreset = com.reka.remoteplay.core.util.QualityPreset.Quality,
    qualityPresetHeights: Map<com.reka.remoteplay.core.util.QualityPreset, Int> = emptyMap(),
    onChangeQualityPreset: (com.reka.remoteplay.core.util.QualityPreset) -> Unit = {},
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isHardwareKeyboardAvailable = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    
    var uiHidden by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }

    LaunchedEffect(showUI) {
        if (!showUI) {
            showQualityPicker = false
        }
    }

    var backPressedOnce by remember { mutableStateOf(false) }
    BackHandler {
        if (uiHidden) {
            uiHidden = false
        } else if (backPressedOnce) {
            onPauseAndGoBack()
        } else {
            backPressedOnce = true
            Toast.makeText(context, context.getString(R.string.press_back_to_pause), Toast.LENGTH_SHORT).show()
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

    // Only auto-show soft keyboard if hardware keyboard is NOT available
    LaunchedEffect(showKeyboard, isHardwareKeyboardAvailable) {
        if (showKeyboard && !isHardwareKeyboardAvailable) {
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

    LaunchedEffect(Unit) { onInitStreaming() }

    LaunchedEffect(cursorState.monitorIndex) {
        if (!isDragging && cursorState.monitorIndex != activeMonitor) {
            onSwitchMonitor(cursorState.monitorIndex)
        }
    }

    LaunchedEffect(connectionState) {
        when (connectionState) {
            is ConnectionState.ConfiguringSettings -> onBack()
            is ConnectionState.Disconnected -> onBack()
            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Transparent TextField for software keyboard input, 
        // bypassed when using hardware keyboard to prevent focus issues.
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
                    repeat(toDelete) { onSendKey(0x08, true); onSendKey(0x08, false) }
                    for (ch in toSend) {
                        val vk = charToVirtualKey(ch)
                        if (vk != 0) { onSendKey(vk, true); onSendKey(vk, false) }
                        else { onSendText(ch.toString()) }
                    }
                    lastSentText = committed
                }
                if (committed.length > 200) { textFieldValue = TextFieldValue(); lastSentText = "" }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.focusRequester(focusRequester).size(1.dp).offset(x = (-100).dp)
        )

        val activeMonitorInfo = monitors.getOrNull(activeMonitor)
        val videoAspectRatio = if (activeMonitorInfo != null) {
            activeMonitorInfo.width.toFloat() / activeMonitorInfo.height.toFloat().coerceAtLeast(1f)
        } else 16f / 9f

        Box(
            modifier = Modifier.align(Alignment.Center).aspectRatio(videoAspectRatio).fillMaxSize()
        ) {
            VideoSurface(
                onSurfaceCreated = onSurfaceCreated,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = Modifier.fillMaxSize()
            )
            if (!isDragging) {
                CursorOverlay(
                    cursorState = cursorState,
                    cursorImage = cursorImage,
                    desktopWidth = activeMonitorInfo?.width ?: 1920,
                    desktopHeight = activeMonitorInfo?.height ?: 1080,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (activeMonitor !in firstFrames) {
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
            onSendMouseMove = onSendMouseMove,
            onSendMouseButton = onSendMouseButton,
            onSendMouseWheel = onSendMouseWheel,
            onSetDragging = onSetDragging,
            onReConfineCursor = onReConfineCursor,
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
                            text = stringResource(R.string.ping_ms_format, rttMs.toInt()),
                            color = when {
                                rttMs < 20 -> AppGreenLight
                                rttMs < 50 -> AppYellow
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
                                rttMs < 20 -> AppGreenLight
                                rttMs < 50 -> AppYellow
                                else -> AppRedLight
                            },
                            onClick = onToggleUI
                        )

                        AnimatedVisibility(
                            visible = showUI,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MenuIconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    tint = AppRedLight,
                                    onClick = onPauseAndGoBack
                                )
                                MenuIconButton(
                                    icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    tint = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    onClick = onToggleMute
                                )
                                MenuIconButton(
                                    icon = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                    tint = if (isMicEnabled) AppGreenLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = onToggleMic
                                )
                                MenuIconButton(
                                    icon = Icons.Default.VisibilityOff,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = {
                                        onHideUI()
                                        uiHidden = true
                                        Toast.makeText(context, context.getString(R.string.press_back_to_show_ui), Toast.LENGTH_SHORT).show()
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
                        // Quality presets
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.reka.remoteplay.core.util.QualityPreset.entries.forEach { preset ->
                                    QualityPresetButton(
                                        label = preset.displayName,
                                        isActive = qualityPreset == preset,
                                        onClick = { onChangeQualityPreset(preset); showQualityPicker = false }
                                    )
                                }
                            }
                        }
                        // FPS
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                availableFpsOptions.forEach { fps ->
                                    FpsButton(fps = fps, isActive = streamFps == fps, onClick = { onChangeFps(fps); showQualityPicker = false })
                                }
                            }
                        }
                    }
                }

                // Hide the floating keyboard button if a physical keyboard is connected
                if (!isHardwareKeyboardAvailable) {
                    FloatingCircleButton(
                        icon = Icons.Default.Keyboard,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onToggleKeyboard,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
                    )
                }

                if (monitors.size > 1) {
                    MonitorTabBar(
                        monitors = monitors,
                        activeIndex = activeMonitor,
                        onSelect = onSwitchMonitor,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)
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
                        if (scrollX != 0) { onSendMouseWheel(0, (scrollX * 40).toShort()); accumX -= scrollX * scrollThreshold }
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

private fun charToVirtualKey(ch: Char): Int = when {
    ch in 'a'..'z' -> 0x41 + (ch - 'a')
    ch in 'A'..'Z' -> 0x41 + (ch - 'A')
    ch in '0'..'9' -> 0x30 + (ch - '0')
    else -> when (ch) {
        ' ' -> 0x20; '\n', '\r' -> 0x0D; '\t' -> 0x09; '-' -> 0xBD; '=' -> 0xBB; '[' -> 0xDB; ']' -> 0xDD; '\\' -> 0xDC; ';' -> 0xBA; '\'' -> 0xDE; '`' -> 0xC0; ',' -> 0xBC; '.' -> 0xBE; '/' -> 0xBF; else -> 0
    }
}
