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
 * (u,v) represents the precise mouse point / hotspot in normalized coordinates [0..1].
 * The bitmap is drawn offset by (-hotspotX * scale, -hotspotY * scale) so the pointer tip
 * always remains exactly at (u, v) across all cursor shape transitions.
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
        val targetW = if (desktopWidth > 0) desktopWidth else 1920
        val targetH = if (desktopHeight > 0) desktopHeight else 1080
        val scaleX = size.width / targetW.toFloat()
        val scaleY = size.height / targetH.toFloat()

        val cursorPointX = cursorState.u * size.width
        val cursorPointY = cursorState.v * size.height

        if (cursorImage != null && !cursorImage.bitmap.isRecycled) {
            val dstW = (cursorImage.bitmap.width * scaleX).toInt().coerceAtLeast(1)
            val dstH = (cursorImage.bitmap.height * scaleY).toInt().coerceAtLeast(1)

            // Offset top-left so hotspot aligns precisely with cursorPoint
            val drawX = cursorPointX - (cursorImage.hotspotX * scaleX)
            val drawY = cursorPointY - (cursorImage.hotspotY * scaleY)

            drawImage(
                image = cursorImage.bitmap.asImageBitmap(),
                dstOffset = IntOffset(drawX.toInt(), drawY.toInt()),
                dstSize = IntSize(dstW, dstH)
            )
        } else {
            drawCircle(Color.White, 4f, center = Offset(cursorPointX, cursorPointY))
            drawCircle(Color.Black, 4f, center = Offset(cursorPointX, cursorPointY), style = Stroke(1.5f))
        }
    }
}
