package com.reka.remoteplay.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EncoderResolutionCalculatorTest {

    @Test
    fun testAlignDown() {
        assertEquals(1920, EncoderResolutionCalculator.alignDown(1925, 128))
        assertEquals(1280, EncoderResolutionCalculator.alignDown(1300, 128))
        assertEquals(1080, EncoderResolutionCalculator.alignDown(1080, 8))
    }

    @Test
    fun testCalculateQualityPreset() {
        // 1920x1080 is standard → use directly
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Quality)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun testCalculatePerformancePreset() {
        // Performance targets 720p → 1280x720 is standard
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Performance)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun testCalculateBalancedPreset() {
        // Quality=1920, target=1600 → 1600x900 is standard
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Balanced)
        assertEquals(1600, w)
        assertEquals(900, h)
    }

    @Test
    fun testNonStandardAspectRatio() {
        // 2400x1080 (20:9) Quality → 2400x1080 is standard height
        val (w, h) = EncoderResolutionCalculator.calculate(2400, 1080, QualityPreset.Quality)
        assertEquals(2400, w)
        assertEquals(1080, h)

        val sourceAspect = 2400f / 1080f
        val calculatedAspect = w.toFloat() / h.toFloat()
        val error = abs(calculatedAspect - sourceAspect) / sourceAspect
        assertTrue("Aspect error $error should be < 0.02", error < 0.02)
    }

    @Test
    fun testPhoneScreen2800x1260() {
        // 2800x1260 (20:9) — non-standard height, needs alignment
        val (qW, qH) = EncoderResolutionCalculator.calculate(2800, 1260, QualityPreset.Quality)
        assertEquals(2560, qW)
        assertEquals(1152, qH)

        val (bW, bH) = EncoderResolutionCalculator.calculate(2800, 1260, QualityPreset.Balanced)
        // Balanced picks standard height 900 (between Q=1152 and P=720)
        assertEquals(900, bH)
        assertTrue("Balanced width $bW should be < Quality width $qW", bW < qW)

        // Performance: 720 is standard → direct resolution
        val (pW, pH) = EncoderResolutionCalculator.calculate(2800, 1260, QualityPreset.Performance)
        assertEquals(720, pH)
        assertTrue("Performance width $pW should be < Balanced width $bW", pW < bW)
    }

    @Test
    fun testBalancedBetweenQualityAndPerformance() {
        listOf(1920 to 1080, 2800 to 1260, 2400 to 1080).forEach { (sw, sh) ->
            val (qW, _) = EncoderResolutionCalculator.calculate(sw, sh, QualityPreset.Quality)
            val (bW, _) = EncoderResolutionCalculator.calculate(sw, sh, QualityPreset.Balanced)
            val (pW, _) = EncoderResolutionCalculator.calculate(sw, sh, QualityPreset.Performance)
            assertTrue("$sw x $sh: Q=$qW > B=$bW > P=$pW", qW > bW && bW > pW)
        }
    }

    @Test
    fun testQualityCappedAt1440p() {
        // 4K screen: Quality capped at 1440p
        val (qW, qH) = EncoderResolutionCalculator.calculate(3840, 2160, QualityPreset.Quality)
        assertEquals(2560, qW)
        assertEquals(1440, qH)

        // Balanced: standard height between 720 and 1440 → 1080
        val (bW, bH) = EncoderResolutionCalculator.calculate(3840, 2160, QualityPreset.Balanced)
        assertEquals(1080, bH)

        // Performance: 720p
        val (pW, pH) = EncoderResolutionCalculator.calculate(3840, 2160, QualityPreset.Performance)
        assertEquals(720, pH)
    }

    @Test
    fun testAspectRatioPreserved() {
        listOf(1920 to 1080, 2800 to 1260, 2400 to 1080, 3840 to 2160).forEach { (sw, sh) ->
            val sourceAspect = sw.toFloat() / sh.toFloat()
            QualityPreset.entries.forEach { preset ->
                val (w, h) = EncoderResolutionCalculator.calculate(sw, sh, preset)
                val error = abs(w.toFloat() / h.toFloat() - sourceAspect) / sourceAspect
                assertTrue("$sw x $sh $preset: aspect error $error >= 0.02", error < 0.02f)
            }
        }
    }
}
