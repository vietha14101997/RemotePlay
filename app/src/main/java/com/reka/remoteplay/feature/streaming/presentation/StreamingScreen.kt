package com.reka.remoteplay.feature.streaming.presentation

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.reka.remoteplay.core.model.MonitorInfoDto
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun StreamingScreen(
    onBack: () -> Unit,
    viewModel: StreamingViewModel = hiltViewModel()
) {
    val monitors by viewModel.monitors.collectAsState()
    val activeMonitor by viewModel.activeMonitor.collectAsState()
    val showUI by viewModel.showUI.collectAsState()
    val firstFrames by viewModel.firstFrameReceived.collectAsState()
    val rttMs by viewModel.rttMs.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Force landscape + immersive fullscreen
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity ?: return@DisposableEffect onDispose {}
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    LaunchedEffect(Unit) { viewModel.initStreaming() }

    // Auto-hide UI
    LaunchedEffect(showUI) {
        if (showUI) {
            delay(4000)
            viewModel.hideUI()
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
        // ===== Persistent Video Surface =====
        val activeMonitorInfo = monitors.getOrNull(activeMonitor)
        val videoAspectRatio = if (activeMonitorInfo != null) {
            activeMonitorInfo.width.toFloat() / activeMonitorInfo.height.toFloat().coerceAtLeast(1f)
        } else 16f / 9f

        // Video area within the Box (centered, aspect-ratio constrained)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspectRatio)
                .fillMaxSize()
        ) {
            VideoSurface(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            // Cursor overlay — inside video area so (u,v) maps correctly
            val cursorState by viewModel.cursorState.collectAsState()
            val cursorImage by viewModel.cursorImage.collectAsState()
            CursorOverlay(
                cursorState = cursorState,
                cursorImage = cursorImage,
                desktopWidth = activeMonitorInfo?.width ?: 1920,
                desktopHeight = activeMonitorInfo?.height ?: 1080,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== Loading overlay =====
        if (activeMonitor !in firstFrames) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppAccent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Waiting for video...", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // ===== Touch gesture layer (touchpad mode) =====
        TouchpadLayer(
            viewModel = viewModel,
            activeMonitor = activeMonitor,
            modifier = Modifier.fillMaxSize()
        )

        // ===== Floating menu button (top-left, always visible when UI hidden) =====
        AnimatedVisibility(
            visible = !showUI,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
        ) {
            FloatingMenuButton(
                rttMs = rttMs,
                onClick = { viewModel.toggleUI() }
            )
        }

        // ===== UI Panel (all elements shown/hidden together) =====
        AnimatedVisibility(
            visible = showUI,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Toolbar (top-center)
                FloatingToolbar(
                    rttMs = rttMs,
                    onKeyboard = { viewModel.toggleKeyboard() },
                    onBack = { viewModel.pauseAndGoBack() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )

                // Monitor tab bar (bottom-center)
                if (monitors.size > 1) {
                    MonitorTabBar(
                        monitors = monitors,
                        activeIndex = activeMonitor,
                        onSelect = {
                            viewModel.switchMonitor(it)
                            viewModel.hideUI()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}

// ===== Floating menu button (menu icon + RTT badge) =====

@Composable
private fun FloatingMenuButton(
    rttMs: Float,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = Color(0x44000000),
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Menu",
                tint = when {
                    rttMs < 20 -> AppGreenLight
                    rttMs < 50 -> AppYellow
                    else -> AppRedLight
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ===== Video Surface =====

@Composable
private fun VideoSurface(
    viewModel: StreamingViewModel,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        viewModel.videoDecoderManager.setActiveSurface(holder.surface)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        viewModel.videoDecoderManager.onSurfaceDestroyed()
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
    viewModel: StreamingViewModel,
    activeMonitor: Int,
    modifier: Modifier = Modifier
) {
    var isScrolling by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(activeMonitor) {
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
                            viewModel.sendMouseWheel((-scrollY * 40).toShort(), 0)
                            accumY -= scrollY * scrollThreshold
                        }
                        if (scrollX != 0) {
                            viewModel.sendMouseWheel(0, (scrollX * 40).toShort())
                            accumX -= scrollX * scrollThreshold
                        }
                    }

                    if (totalMoved < slop * 2) {
                        viewModel.sendMouseButton(1.toByte(), true)
                        viewModel.sendMouseButton(1.toByte(), false)
                    }
                }
            }
            .pointerInput(activeMonitor, isScrolling) {
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
                                        viewModel.sendMouseMove(dx.toInt().toShort(), dy.toInt().toShort())
                                    }
                                    pointer.consume()
                                }
                            }

                            "up" -> {
                                val secondDown = withTimeoutOrNull(doubleTapMs) {
                                    awaitFirstDown(requireUnconsumed = false)
                                }

                                if (secondDown == null) {
                                    viewModel.sendMouseButton(0, true)
                                    viewModel.sendMouseButton(0, false)
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
                                            viewModel.sendMouseButton(0, true)
                                            viewModel.sendMouseButton(0, false)
                                            viewModel.sendMouseButton(0, true)
                                            viewModel.sendMouseButton(0, false)
                                        }
                                        "drag" -> {
                                            isDoubleTapDrag = true
                                            viewModel.sendMouseButton(0, true)
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
                                        viewModel.sendMouseButton(0, false)
                                    }
                                    break
                                }
                                accumX += pointer.position.x - pointer.previousPosition.x
                                accumY += pointer.position.y - pointer.previousPosition.y

                                val dx = accumX.toInt()
                                val dy = accumY.toInt()
                                if (dx != 0 || dy != 0) {
                                    viewModel.sendMouseMove(dx.toShort(), dy.toShort())
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

@Composable
private fun FloatingToolbar(
    rttMs: Float,
    onKeyboard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface.copy(alpha = 0.8f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${rttMs.toInt()}ms",
                color = when {
                    rttMs < 20 -> AppGreenLight
                    rttMs < 50 -> AppYellow
                    else -> AppRedLight
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onKeyboard, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppRedLight, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MonitorTabBar(
    monitors: List<MonitorInfoDto>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface.copy(alpha = 0.8f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            monitors.forEachIndexed { index, _ ->
                val isActive = index == activeIndex
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isActive) AppAccent else Color.Transparent,
                    modifier = Modifier.clickable { onSelect(index) }
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (isActive) Color.White else AppTextTertiary,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
