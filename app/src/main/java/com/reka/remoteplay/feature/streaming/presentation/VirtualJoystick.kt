package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Virtual joystick that reports normalized displacement (-1..1) on each axis.
 *
 * @param onDelta Called continuously while dragging with (dx, dy) in -1..1 range.
 *                dx: left=-1, right=+1. dy: up=-1, down=+1.
 * @param onRelease Called when user lifts finger (joystick snaps back to center).
 */
@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    onDelta: (dx: Float, dy: Float) -> Unit,
    onRelease: () -> Unit = {}
) {
    val sizeDp = 72.dp
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        thumbOffset = Offset.Zero
                        onRelease()
                    },
                    onDragCancel = {
                        thumbOffset = Offset.Zero
                        onRelease()
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val radius = size.width / 2f
                    val newOffset = thumbOffset + Offset(dragAmount.x, dragAmount.y)

                    // Clamp to circle
                    val dist = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                    thumbOffset = if (dist > radius) {
                        newOffset * (radius / dist)
                    } else {
                        newOffset
                    }

                    // Normalize to -1..1
                    onDelta(thumbOffset.x / radius, thumbOffset.y / radius)
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.width / 2f
        val thumbRadius = baseRadius * 0.35f

        // Base circle — dark fill + visible border for contrast on any background
        drawCircle(
            color = Color.Black.copy(alpha = 0.3f),
            radius = baseRadius,
            center = center
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.4f),
            radius = baseRadius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )

        // Thumb — dark core + light border
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = thumbRadius,
            center = center + thumbOffset
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = thumbRadius,
            center = center + thumbOffset,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )
    }
}
