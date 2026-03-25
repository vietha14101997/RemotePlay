package com.reka.remoteplay.feature.streaming.presentation

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current
    var uiHidden by remember { mutableStateOf(false) }
    var showFpsPicker by remember { mutableStateOf(false) }

    // Close FPS picker when menu collapses
    LaunchedEffect(showUI) { if (!showUI) showFpsPicker = false }

    // Back handler: show UI if hidden, otherwise double-press to pause
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

    // Soft keyboard input
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }
    var lastSentText by remember { mutableStateOf("") }

    LaunchedEffect(showKeyboard) {
        if (showKeyboard) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    // Force landscape + immersive fullscreen + call volume control + keep screen on
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

    // Auto-switch monitor when foreground window is on a different monitor
    // NOT during drag — drag stays confined to current monitor, use button to switch.
    LaunchedEffect(cursorState.monitorIndex) {
        if (!isDragging && cursorState.monitorIndex != activeMonitor) {
            onSwitchMonitor(cursorState.monitorIndex)
        }
    }

    // Navigate back on pause/disconnect
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
            .background(Color.Black)
    ) {
        // ===== Hidden TextField for soft keyboard input =====
        // KeyboardType.Ascii bypasses IME composition (Telex/VNI/Unikey),
        // so each key press sends immediately without waiting for compose.
        // This prevents "a a" → "â" issue in games.
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

                    repeat(toDelete) {
                        onSendKey(0x08, true)
                        onSendKey(0x08, false)
                    }
                    for (ch in toSend) {
                        val vk = charToVirtualKey(ch)
                        if (vk != 0) {
                            onSendKey(vk, true)
                            onSendKey(vk, false)
                        } else {
                            // Fallback: Unicode characters without VK mapping
                            onSendText(ch.toString())
                        }
                    }
                    lastSentText = committed
                }

                if (committed.length > 200) {
                    textFieldValue = TextFieldValue()
                    lastSentText = ""
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp)
                .offset(x = (-100).dp)
        )

        // ===== Persistent Video Surface =====
        val activeMonitorInfo = monitors.getOrNull(activeMonitor)
        val videoAspectRatio = if (activeMonitorInfo != null) {
            activeMonitorInfo.width.toFloat() / activeMonitorInfo.height.toFloat().coerceAtLeast(1f)
        } else 16f / 9f

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspectRatio)
                .fillMaxSize()
        ) {
            VideoSurface(
                onSurfaceCreated = onSurfaceCreated,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = Modifier.fillMaxSize()
            )

            // Hide cursor overlay during drag — DXGI capture includes cursor in
            // composed frames during window drag, showing overlay causes ghost
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

        // ===== Loading overlay =====
        if (activeMonitor !in firstFrames) {
            Box(
                modifier = Modifier.fillMaxSize().background(AppOverlayBlack),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppAccent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.waiting_for_video), color = AppTextPrimary, fontSize = 14.sp)
                }
            }
        }

        // ===== Touch gesture layer =====
        TouchpadLayer(
            onSendMouseMove = onSendMouseMove,
            onSendMouseButton = onSendMouseButton,
            onSendMouseWheel = onSendMouseWheel,
            onSetDragging = onSetDragging,
            onReConfineCursor = onReConfineCursor,
            modifier = Modifier.fillMaxSize()
        )

        // ===== All UI elements (hidden when uiHidden) =====
        AnimatedVisibility(
            visible = !uiHidden,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Ping badge (top-right)
                Surface(
                    shape = CircleShape,
                    color = AppSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 12.dp)
                        .size(44.dp)
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

                // Menu (top-left) — unified surface block
                Surface(
                    shape = CircleShape,
                    color = AppSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Menu toggle (always first)
                        MenuIconButton(
                            icon = Icons.Default.Menu,
                            tint = when {
                                rttMs < 20 -> AppGreenLight
                                rttMs < 50 -> AppYellow
                                else -> AppRedLight
                            },
                            onClick = onToggleUI
                        )

                        // Expanded items
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
                                    icon = @Suppress("DEPRECATION")
                                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    tint = if (isMuted) AppTextTertiary else AppTextPrimary,
                                    onClick = onToggleMute
                                )
                                MenuIconButton(
                                    icon = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                    tint = if (isMicEnabled) AppGreenLight else AppTextTertiary,
                                    onClick = onToggleMic
                                )
                                MenuIconButton(
                                    icon = Icons.Default.VisibilityOff,
                                    tint = AppTextTertiary,
                                    onClick = {
                                        onHideUI()
                                        uiHidden = true
                                        Toast.makeText(context, context.getString(R.string.press_back_to_show_ui), Toast.LENGTH_SHORT).show()
                                    }
                                )

                                // FPS label — click to open FPS picker
                                Spacer(modifier = Modifier.height(4.dp))
                                FpsButton(
                                    fps = streamFps,
                                    isActive = showFpsPicker,
                                    onClick = { showFpsPicker = !showFpsPicker }
                                )
                            }
                        }
                    }
                }

                // FPS picker bar (top-center, horizontal)
                AnimatedVisibility(
                    visible = showFpsPicker,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.CenterHorizontally),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.CenterHorizontally),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = AppSurface.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            availableFpsOptions.forEach { fps ->
                                FpsButton(
                                    fps = fps,
                                    isActive = streamFps == fps,
                                    onClick = {
                                        onChangeFps(fps)
                                        showFpsPicker = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Keyboard button (bottom-right)
                FloatingCircleButton(
                    icon = Icons.Default.Keyboard,
                    tint = AppTextPrimary,
                    onClick = onToggleKeyboard,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                )

                // Monitor tab bar (bottom-left)
                if (monitors.size > 1) {
                    MonitorTabBar(
                        monitors = monitors,
                        activeIndex = activeMonitor,
                        onSelect = onSwitchMonitor,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }
}

// ===== Floating circle button (reusable) =====

/** Icon button inside a menu — no own background (parent Surface provides it) */
@Composable
private fun MenuIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** FPS selector button inside menu */
@Composable
private fun FpsButton(fps: Int, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$fps",
            color = if (isActive) AppAccent else AppTextTertiary,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** Standalone floating circle button with background */
@Composable
private fun FloatingCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = AppSurface.copy(alpha = 0.85f),
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

// ===== Video Surface =====

@Composable
private fun VideoSurface(
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
            }
        },
        modifier = modifier
    )
}

// ===== Touchpad layer =====

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
            // Two-finger scroll gesture
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val secondDown = withTimeoutOrNull(150) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                return@withTimeoutOrNull true
                            }
                        }
                    }
                    if (secondDown != true) return@awaitEachGesture

                    isScrolling = true
                    var accumY = 0f
                    var accumX = 0f
                    var totalMoved = 0f
                    val scrollThreshold = 8f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            isScrolling = false
                            break
                        }

                        var avgDy = 0f
                        var avgDx = 0f
                        pressed.forEach { p ->
                            avgDy += p.position.y - p.previousPosition.y
                            avgDx += p.position.x - p.previousPosition.x
                            p.consume()
                        }
                        avgDy /= pressed.size
                        avgDx /= pressed.size
                        totalMoved += kotlin.math.abs(avgDx) + kotlin.math.abs(avgDy)

                        accumY += avgDy
                        accumX += avgDx

                        val scrollY = (accumY / scrollThreshold).toInt()
                        val scrollX = (accumX / scrollThreshold).toInt()
                        if (scrollY != 0) {
                            onSendMouseWheel((scrollY * 40).toShort(), 0)
                            accumY -= scrollY * scrollThreshold
                        }
                        if (scrollX != 0) {
                            onSendMouseWheel(0, (scrollX * 40).toShort())
                            accumX -= scrollX * scrollThreshold
                        }
                    }

                    if (totalMoved < slop * 2) {
                        onSendMouseButton(1.toByte(), true)
                        onSendMouseButton(1.toByte(), false)
                    }
                }
            }
            // Single-finger: tap, drag, double-tap-drag, long-press-drag
            // No activeMonitor key — relative input doesn't depend on which monitor is active.
            // This keeps the gesture coroutine alive during monitor switch (seamless drag).
            .pointerInput(isScrolling) {
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop

                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)

                        if (isScrolling) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.none { it.pressed }) break
                            }
                            continue
                        }

                        var isDrag = false
                        var isDoubleTapDrag = false

                        val upOrDrag = withTimeoutOrNull(longPressMs) {
                            var totalDx = 0f
                            var totalDy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull() ?: continue

                                if (!pointer.pressed) {
                                    pointer.consume()
                                    return@withTimeoutOrNull "up"
                                }

                                val dx = pointer.position.x - pointer.previousPosition.x
                                val dy = pointer.position.y - pointer.previousPosition.y
                                totalDx += dx; totalDy += dy

                                if (totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) {
                                    isDrag = true
                                    pointer.consume()
                                    return@withTimeoutOrNull "drag"
                                }
                            }
                        }

                        when (upOrDrag) {
                            null -> {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.firstOrNull() ?: break
                                    if (!pointer.pressed) break
                                    val dx = pointer.position.x - pointer.previousPosition.x
                                    val dy = pointer.position.y - pointer.previousPosition.y
                                    if (dx != 0f || dy != 0f) {
                                        onSendMouseMove(dx.toInt().toShort(), dy.toInt().toShort())
                                    }
                                    pointer.consume()
                                }
                            }

                            "up" -> {
                                val secondDown = withTimeoutOrNull(doubleTapMs) {
                                    awaitFirstDown(requireUnconsumed = false)
                                }

                                if (secondDown == null) {
                                    onSendMouseButton(0, true)
                                    onSendMouseButton(0, false)
                                } else {
                                    val secondResult = withTimeoutOrNull(longPressMs) {
                                        var totalDx = 0f
                                        var totalDy = 0f
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pointer = event.changes.firstOrNull() ?: continue
                                            if (!pointer.pressed) {
                                                pointer.consume()
                                                return@withTimeoutOrNull "up"
                                            }
                                            val dx = pointer.position.x - pointer.previousPosition.x
                                            val dy = pointer.position.y - pointer.previousPosition.y
                                            totalDx += dx; totalDy += dy
                                            if (totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) {
                                                pointer.consume()
                                                return@withTimeoutOrNull "drag"
                                            }
                                        }
                                    }

                                    when (secondResult) {
                                        "up", null -> {
                                            onSendMouseButton(0, true)
                                            onSendMouseButton(0, false)
                                            onSendMouseButton(0, true)
                                            onSendMouseButton(0, false)
                                        }
                                        "drag" -> {
                                            isDoubleTapDrag = true
                                            onSetDragging(true)
                                            onSendMouseButton(0, true)
                                        }
                                    }
                                }
                            }

                            "drag" -> {
                                isDrag = true
                            }
                        }

                        if (isDrag || isDoubleTapDrag) {
                            var accumX = 0f
                            var accumY = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull() ?: continue
                                if (!pointer.pressed) {
                                    pointer.consume()
                                    if (isDoubleTapDrag) {
                                        onSendMouseButton(0, false)
                                        onSetDragging(false)
                                        // Re-confine cursor after drag — Windows drag operation
                                        // overrides ClipCursor during window move
                                        onReConfineCursor()
                                    }
                                    break
                                }
                                accumX += pointer.position.x - pointer.previousPosition.x
                                accumY += pointer.position.y - pointer.previousPosition.y

                                val dx = accumX.toInt()
                                val dy = accumY.toInt()
                                if (dx != 0 || dy != 0) {
                                    onSendMouseMove(dx.toShort(), dy.toShort())
                                    accumX -= dx
                                    accumY -= dy
                                }
                                pointer.consume()
                            }
                        }
                    }
                }
            }
    )
}


// ===== Monitor Tab Bar (vertical segmented, bottom-left, always visible) =====

@Composable
private fun MonitorTabBar(
    monitors: List<MonitorInfoDto>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = AppSurface.copy(alpha = 0.85f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            monitors.forEachIndexed { index, _ ->
                val isActive = index == activeIndex
                Surface(
                    shape = CircleShape,
                    color = if (isActive) AppAccent.copy(alpha = 0.25f) else Color.Transparent,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onSelect(index) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            color = if (isActive) AppAccent else AppTextTertiary,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

// ===== Character → Windows Virtual Key mapping =====

/** Map a character to its Windows Virtual Key code. Returns 0 if unmapped. */
private fun charToVirtualKey(ch: Char): Int = when {
    ch in 'a'..'z' -> 0x41 + (ch - 'a')
    ch in 'A'..'Z' -> 0x41 + (ch - 'A')
    ch in '0'..'9' -> 0x30 + (ch - '0')
    else -> when (ch) {
        ' ' -> 0x20
        '\n', '\r' -> 0x0D
        '\t' -> 0x09
        '-' -> 0xBD; '=' -> 0xBB
        '[' -> 0xDB; ']' -> 0xDD
        '\\' -> 0xDC; ';' -> 0xBA
        '\'' -> 0xDE; '`' -> 0xC0
        ',' -> 0xBC; '.' -> 0xBE
        '/' -> 0xBF
        else -> 0
    }
}
