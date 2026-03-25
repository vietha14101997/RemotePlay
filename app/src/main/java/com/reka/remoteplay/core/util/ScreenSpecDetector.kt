package com.reka.remoteplay.core.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

data class ScreenSpecs(val widthPx: Int, val heightPx: Int, val refreshRate: Float)

object ScreenSpecDetector {
    fun detect(context: Context): ScreenSpecs {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return ScreenSpecs(1920, 1080, 60f)
        val currentMode = display.mode

        // Find max refresh rate among supported modes at the same resolution.
        // Android adaptive refresh may report current mode as 120Hz even when
        // device supports 144Hz — use the highest available.
        val maxHz = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxOfOrNull { it.refreshRate }
            ?: currentMode.refreshRate

        return ScreenSpecs(
            widthPx = currentMode.physicalWidth,
            heightPx = currentMode.physicalHeight,
            refreshRate = maxHz
        )
    }
}
