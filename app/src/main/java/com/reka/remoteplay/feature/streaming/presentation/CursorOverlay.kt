package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reka.remoteplay.feature.streaming.data.remote.CursorRenderer

/**
 * Draws cursor overlay.
 *
 * Server sends (u,v) = cursor top-left position / monitor size (from DXGI Position).
 * This is the TOP-LEFT of the cursor bitmap, NOT the hotspot.
 * So we draw the bitmap directly at (u*w, v*h) without any hotspot offset.
 */
@Composable
fun CursorOverlay(
    cursorState: CursorRenderer.CursorState,
    cursorImage: CursorRenderer.CursorImageEntry? = null,
    desktopWidth: Int = 1920,
    desktopHeight: Int = 1080,
    modifier: Modifier = Modifier
) {
    if (!cursorState.visible) return

    Canvas(modifier = modifier.fillMaxSize()) {
        // (u,v) = cursor bitmap top-left in normalized monitor coordinates
        val x = cursorState.u * size.width
        val y = cursorState.v * size.height

        if (cursorImage != null && !cursorImage.bitmap.isRecycled) {
            // Scale cursor bitmap to match canvas-to-desktop ratio
            val scaleX = size.width / desktopWidth
            val scaleY = size.height / desktopHeight
            val dstW = (cursorImage.bitmap.width * scaleX).toInt().coerceAtLeast(1)
            val dstH = (cursorImage.bitmap.height * scaleY).toInt().coerceAtLeast(1)

            // Draw at (x,y) directly — server already sends top-left position
            drawImage(
                image = cursorImage.bitmap.asImageBitmap(),
                dstOffset = IntOffset(x.toInt(), y.toInt()),
                dstSize = IntSize(dstW, dstH)
            )
        } else {
            drawCircle(Color.White, 4f, center = Offset(x, y))
            drawCircle(Color.Black, 4f, center = Offset(x, y), style = Stroke(1.5f))
        }
    }
}
