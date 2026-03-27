package com.reka.remoteplay.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderResolutionCalculatorTest {

    @Test
    fun testAlignDown() {
        assertEquals(1920, EncoderResolutionCalculator.alignDown(1925, 128))
        assertEquals(1280, EncoderResolutionCalculator.alignDown(1300, 128))
        assertEquals(1080, EncoderResolutionCalculator.alignDown(1080, 8))
    }

    @Test
    fun testCalculateQualityPreset() {
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Quality)
        
        assertTrue(w % 16 == 0)
        assertTrue(h % 16 == 0)
        // Current implementation results for 1920x1080 Quality:
        assertEquals(1792, w) 
        assertEquals(1024, h)
    }

    @Test
    fun testCalculatePerformancePreset() {
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Performance)
        
        assertEquals(1152, w)
        assertEquals(640, h)
        assertTrue(w % 16 == 0)
        assertTrue(h % 16 == 0)
    }

    @Test
    fun testCalculateBalancedPreset() {
        val (w, h) = EncoderResolutionCalculator.calculate(1920, 1080, QualityPreset.Balanced)
        
        assertEquals(1152, w)
        assertEquals(640, h)
        assertTrue(w % 16 == 0)
        assertTrue(h % 16 == 0)
    }

    @Test
    fun testNonStandardAspectRatio() {
        val (w, h) = EncoderResolutionCalculator.calculate(2400, 1080, QualityPreset.Quality)
        
        assertTrue(w % 16 == 0)
        assertTrue(h % 16 == 0)
        
        val sourceAspect = 2400f / 1080f
        val calculatedAspect = w.toFloat() / h.toFloat()
        val error = Math.abs(calculatedAspect - sourceAspect) / sourceAspect
        assertTrue("Aspect error ${error} should be < 0.02", error < 0.02)
    }
}
