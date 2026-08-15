package com.reka.remoteplay.feature.streaming.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Uses high-stiffness spring animation to smooth out network packet jitter while
 * maintaining instantaneous responsiveness without lag.
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

    val animatedU by animateFloatAsState(
        targetValue = cursorState.u,
        animationSpec = spring(stiffness = 3000f, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "cursorU"
    )
    val animatedV by animateFloatAsState(
        targetValue = cursorState.v,
        animationSpec = spring(stiffness = 3000f, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "cursorV"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val targetW = if (desktopWidth > 0) desktopWidth else 1920
        val targetH = if (desktopHeight > 0) desktopHeight else 1080
        val scaleX = size.width / targetW.toFloat()
        val scaleY = size.height / targetH.toFloat()

        val cursorPointX = animatedU * size.width
        val cursorPointY = animatedV * size.height

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
