package com.reka.remoteplay.core.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quality presets for encoder resolution selection.
 * Each preset calculates an encoder-friendly resolution optimized for hardware encoders.
 */
enum class QualityPreset {
    Performance,
    Balanced,
    Quality;

    val displayName: String
        get() = name
}

/**
 * Calculates encoder-friendly resolutions for optimal hardware encoder performance.
 *
 * Two strategies:
 * 1. Standard heights (1080, 720, 900, 1440, etc.) are universally optimized by encoders → use directly.
 * 2. Non-standard screens (e.g., 2800×1260) → align both dims to 128/64/32/16.
 *
 * Example: Standard 1920×1080 (16:9)
 * - Quality:     1920×1080 (standard, use directly)
 * - Balanced:    1600×900  (standard, use directly)
 * - Performance: 1280×720  (standard, use directly)
 *
 * Example: Phone screen 2800×1260 (20:9)
 * - Quality:     2560×1152 (both ÷128 ✓, closest to native)
 * - Balanced:    2000×900  (standard height between Q and P)
 * - Performance: 1600×720  (720p standard height)
 */
object EncoderResolutionCalculator {

    private val ALIGNMENTS = intArrayOf(128, 64, 32, 16)
    private const val MIN_WIDTH = 640
    private const val MAX_ASPECT_ERROR = 0.02f // 2% tolerance
    private const val MAX_SEARCH_STEPS = 2 // Max candidates per alignment before fallback
    private const val MAX_QUALITY_HEIGHT = 1440 // Cap Quality at 1440p to prevent GPU overload

    /** Standard display heights universally optimized by hardware encoders. */
    private val ENCODER_FRIENDLY_HEIGHTS = setOf(2160, 1440, 1200, 1080, 900, 720, 540, 480, 360)

    /**
     * Round [value] down to the nearest multiple of [alignment].
     */
    fun alignDown(value: Int, alignment: Int): Int = (value / alignment) * alignment

    /**
     * Round [value] to the nearest multiple of [alignment].
     */
    private fun alignNearest(value: Int, alignment: Int): Int =
        ((value + alignment / 2) / alignment) * alignment

    /**
     * Find an encoder-friendly resolution pair (width, height) for the given target.
     *
     * Strategy:
     * 1. If target maps to a standard encoder-friendly height → use directly.
     * 2. Otherwise, search for resolution where both dims are aligned (128 > 64 > 32 > 16).
     *
     * @param screenWidth  Native screen width (landscape)
     * @param screenHeight Native screen height (landscape)
     * @param targetWidth  Desired target width to search near
     * @return Pair(width, height)
     */
    fun findAlignedResolution(screenWidth: Int, screenHeight: Int, targetWidth: Int): Pair<Int, Int> {
        val sourceAspect = screenWidth.toFloat() / screenHeight.toFloat()

        // Fast path: standard encoder-friendly height
        val idealHeight = (targetWidth / sourceAspect).roundToInt()
        if (idealHeight in ENCODER_FRIENDLY_HEIGHTS && targetWidth >= MIN_WIDTH) {
            val aspectError = abs(targetWidth.toFloat() / idealHeight - sourceAspect) / sourceAspect
            if (aspectError < MAX_ASPECT_ERROR) {
                return targetWidth to idealHeight
            }
        }

        // Alignment search: both dims divisible by alignment
        for (alignment in ALIGNMENTS) {
            var candidateWidth = alignDown(targetWidth, alignment)
            var steps = 0

            while (candidateWidth >= MIN_WIDTH && steps < MAX_SEARCH_STEPS) {
                val candidateHeight = alignNearest(
                    (candidateWidth / sourceAspect).roundToInt(), alignment
                )

                if (candidateHeight > 0) {
                    val aspectError =
                        abs(candidateWidth.toFloat() / candidateHeight - sourceAspect) / sourceAspect

                    if (aspectError < MAX_ASPECT_ERROR) {
                        return candidateWidth to candidateHeight
                    }
                }

                candidateWidth -= alignment
                steps++
            }
        }

        // Fallback: align to 16 (least strict)
        val fallbackW = alignDown(targetWidth, 16).coerceAtLeast(MIN_WIDTH)
        val fallbackH = alignDown((fallbackW / sourceAspect).roundToInt(), 16).coerceAtLeast(16)
        return fallbackW to fallbackH
    }

    /**
     * Calculate encoder-friendly resolution for a given screen size and quality preset.
     *
     * @param screenWidth  Native screen width (always pass landscape: max of w,h)
     * @param screenHeight Native screen height (always pass landscape: min of w,h)
     * @param preset       Quality preset
     * @return Pair(width, height)
     */
    fun calculate(screenWidth: Int, screenHeight: Int, preset: QualityPreset, maxQualityHeight: Int = MAX_QUALITY_HEIGHT): Pair<Int, Int> {
        // Ensure landscape orientation (width > height)
        val w = maxOf(screenWidth, screenHeight)
        val h = minOf(screenWidth, screenHeight)
        val sourceAspect = w.toFloat() / h.toFloat()

        return when (preset) {
            QualityPreset.Quality -> {
                val targetW = if (h > maxQualityHeight) {
                    (maxQualityHeight * sourceAspect).roundToInt()
                } else w
                findAlignedResolution(w, h, targetW)
            }

            QualityPreset.Performance -> {
                val targetWidth = (720 * sourceAspect).roundToInt().coerceAtMost(w)
                findAlignedResolution(w, h, targetWidth)
            }

            QualityPreset.Balanced -> {
                val qualityTargetW = if (h > maxQualityHeight) {
                    (maxQualityHeight * sourceAspect).roundToInt()
                } else w
                val qualityRes = findAlignedResolution(w, h, qualityTargetW)
                val perfTargetW = (720 * sourceAspect).roundToInt().coerceAtMost(w)
                val perfRes = findAlignedResolution(w, h, perfTargetW)

                // Pick standard height between Performance and Quality, closest to midpoint
                val midHeight = (qualityRes.second + perfRes.second) / 2
                val bestHeight = ENCODER_FRIENDLY_HEIGHTS
                    .filter { it > perfRes.second && it < qualityRes.second }
                    .minByOrNull { abs(it - midHeight) }

                if (bestHeight != null) {
                    (bestHeight * sourceAspect).roundToInt() to bestHeight
                } else {
                    // Fallback: midpoint width, aligned search
                    val midWidth = (qualityRes.first + perfRes.first) / 2
                    findAlignedResolution(w, h, midWidth)
                }
            }
        }
    }
}
