package com.reka.remoteplay.core.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quality presets for encoder resolution selection.
 * Each preset calculates an encoder-aligned resolution where BOTH width and height
 * are divisible by an alignment factor (128 preferred > 64 > 32 > 16).
 */
enum class QualityPreset {
    Performance,
    Balanced,
    Quality;

    val label: String
        get() = when (this) {
            Performance -> "P"
            Balanced -> "B"
            Quality -> "Q"
        }

    val displayName: String
        get() = name
}

/**
 * Calculates encoder-aligned resolutions for optimal hardware encoder performance.
 *
 * Encoder alignment requirements:
 * - Hardware encoders (NVENC, AMF, QSV) work most efficiently when both width and height
 *   are divisible by their internal macroblock size (typically 16, 32, 64, or 128).
 * - Non-aligned dimensions force the encoder to pad, wasting GPU resources.
 *
 * Example: Phone screen 2800×1260 (aspect ratio 20:9)
 * - Quality:     2560×1152 (both ÷128 ✓, closest to native)
 * - Balanced:    2240×1008 (Quality - 320 width, re-aligned)
 * - Performance: 1280×576  (720p-class, re-aligned)
 */
object EncoderResolutionCalculator {

    private val ALIGNMENTS = intArrayOf(128, 64, 32, 16)
    private const val MIN_WIDTH = 640
    private const val MAX_ASPECT_ERROR = 0.02f // 2% tolerance

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
     * Find an encoder-aligned resolution pair (width, height) where:
     * - Both width and height are divisible by alignment (128 > 64 > 32 > 16)
     * - The aspect ratio matches the source within [MAX_ASPECT_ERROR]
     * - Width is as close as possible to [targetWidth] (searching downward)
     *
     * @param screenWidth  Native screen width (landscape)
     * @param screenHeight Native screen height (landscape)
     * @param targetWidth  Desired target width to search near
     * @return Pair(alignedWidth, alignedHeight)
     */
    fun findAlignedResolution(screenWidth: Int, screenHeight: Int, targetWidth: Int): Pair<Int, Int> {
        val sourceAspect = screenWidth.toFloat() / screenHeight.toFloat()

        for (alignment in ALIGNMENTS) {
            // Start from the aligned width closest to targetWidth, search downward
            var candidateWidth = alignDown(targetWidth, alignment)

            while (candidateWidth >= MIN_WIDTH) {
                val idealHeight = candidateWidth.toFloat() / sourceAspect
                val candidateHeight = alignNearest(idealHeight.roundToInt(), alignment)

                if (candidateHeight > 0) {
                    val candidateAspect = candidateWidth.toFloat() / candidateHeight.toFloat()
                    val aspectError = abs(candidateAspect - sourceAspect) / sourceAspect

                    if (aspectError < MAX_ASPECT_ERROR) {
                        return candidateWidth to candidateHeight
                    }
                }

                candidateWidth -= alignment
            }
        }

        // Fallback: align to 16 (least strict)
        val fallbackW = alignDown(targetWidth, 16).coerceAtLeast(MIN_WIDTH)
        val fallbackH = alignDown((fallbackW / sourceAspect).roundToInt(), 16).coerceAtLeast(16)
        return fallbackW to fallbackH
    }

    /**
     * Calculate encoder-aligned resolution for a given screen size and quality preset.
     *
     * @param screenWidth  Native screen width (always pass landscape: max of w,h)
     * @param screenHeight Native screen height (always pass landscape: min of w,h)
     * @param preset       Quality preset
     * @return Pair(alignedWidth, alignedHeight)
     */
    fun calculate(screenWidth: Int, screenHeight: Int, preset: QualityPreset): Pair<Int, Int> {
        // Ensure landscape orientation (width > height)
        val w = maxOf(screenWidth, screenHeight)
        val h = minOf(screenWidth, screenHeight)

        return when (preset) {
            QualityPreset.Quality -> findAlignedResolution(w, h, w)
            QualityPreset.Balanced -> {
                val qualityRes = findAlignedResolution(w, h, w)
                findAlignedResolution(w, h, qualityRes.first - 320)
            }
            QualityPreset.Performance -> findAlignedResolution(w, h, 1280)
        }
    }
}
