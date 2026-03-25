package com.reka.remoteplay.core.util

/**
 * Build available FPS options based on hardware max refresh rate.
 *
 * Standard tiers: 30, 60, 75, 90, 120, 144.
 * Only includes tiers <= maxHz.
 * If maxHz doesn't match a standard tier (e.g., 74.97Hz), appends it as-is.
 */
fun buildFpsOptions(maxHz: Float): List<Int> {
    val standardTiers = listOf(30, 60, 75, 90, 120, 144)
    val maxHzInt = maxHz.toInt() // 74.97 → 74, 144.0 → 144

    val options = standardTiers.filter { it <= maxHzInt }.toMutableList()

    // If maxHz doesn't match any standard tier, add it as the hardware cap
    // e.g., 74.97Hz → add 74; 165Hz → add 165
    if (maxHzInt !in standardTiers && maxHzInt > 0) {
        options.add(maxHzInt)
        options.sort()
    }

    return if (options.isEmpty()) listOf(30, 60) else options
}
