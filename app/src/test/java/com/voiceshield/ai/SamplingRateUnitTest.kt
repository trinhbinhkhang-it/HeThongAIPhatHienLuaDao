package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the hidden frame-analysis rate: the clamp and the "frames per second to
 * milliseconds between frames" rule the capture loop gates on.
 */
class SamplingRateUnitTest {

    @Test
    fun sanitize_clampsIntoTheSupportedRange() {
        assertEquals(0.5f, sanitizeSamplingFps(0.5f))
        assertEquals(0.5f, sanitizeSamplingFps(0.1f), 0f)
        assertEquals(1f, sanitizeSamplingFps(1f))
        assertEquals(2.5f, sanitizeSamplingFps(2.5f))
        assertEquals(SAMPLING_FPS_MAX, sanitizeSamplingFps(10f), 0f)
    }

    @Test
    fun sanitize_fallsBackToDefaultForUnusableInput() {
        assertEquals(DEFAULT_SAMPLING_FPS, sanitizeSamplingFps(0f), 0f)
        assertEquals(DEFAULT_SAMPLING_FPS, sanitizeSamplingFps(-3f), 0f)
        assertEquals(DEFAULT_SAMPLING_FPS, sanitizeSamplingFps(Float.NaN), 0f)
    }

    @Test
    fun interval_isOneThousandOverFps() {
        assertEquals(2000L, samplingIntervalMs(0.5f))
        assertEquals(1000L, samplingIntervalMs(1f))
        assertEquals(500L, samplingIntervalMs(2f))
        assertEquals(200L, samplingIntervalMs(5f))
    }

    /** An out-of-range rate is sanitised first, so the interval still tracks the real rate. */
    @Test
    fun interval_honoursTheClamp() {
        assertEquals(2000L, samplingIntervalMs(0.1f))
        assertEquals(200L, samplingIntervalMs(100f))
        assertEquals(1000L, samplingIntervalMs(Float.NaN))
    }

    @Test
    fun interval_isNeverZero() {
        // The clamp caps fps at 5, so the gap bottoms out at 200 ms and can never collapse to
        // zero (which would gate nothing and analyse every display frame).
        assertEquals(200L, samplingIntervalMs(100_000f))
    }
}
