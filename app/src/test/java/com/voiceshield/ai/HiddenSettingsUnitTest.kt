package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests for the hidden developer page: the triple-tap gate on the version
 * footer, and the Safe/Warning roll windows it edits.
 *
 * All of these are top-level Android-free functions (same convention as
 * [scanDelayMs] and [isScreenCaptureGranted]), so the gesture and the input
 * normalisation are testable without Robolectric or an emulator.
 */
class HiddenSettingsUnitTest {

    // ── Triple-tap gate on "Version 1.0.1" ──

    @Test
    fun threeTapsInsideTheWindow_openTheHiddenPage() {
        assertTrue(isTripleClick(3, 0L))
        assertTrue(isTripleClick(3, TRIPLE_CLICK_WINDOW_MS))
        assertTrue("extra taps must not undo the gesture", isTripleClick(4, 100L))
    }

    @Test
    fun fewerThanThreeTaps_doNotOpenTheHiddenPage() {
        assertFalse(isTripleClick(0, 0L))
        assertFalse(isTripleClick(1, 0L))
        assertFalse(isTripleClick(2, 0L))
    }

    @Test
    fun threeSlowTaps_doNotOpenTheHiddenPage() {
        assertFalse(isTripleClick(3, TRIPLE_CLICK_WINDOW_MS + 1))
        assertFalse(isTripleClick(3, 10_000L))
    }

    /** The burst window closes exactly at the limit, so a stale count can't leak in. */
    @Test
    fun burstClosesExactlyAtTheWindow() {
        assertTrue(isClickBurstOpen(0L))
        assertTrue(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS))
        assertFalse(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS + 1))
    }

    /**
     * A single tap must still reach Page 2 (Settings). Because that navigation is
     * deferred until the window closes, the window has to be wide enough for three
     * deliberate human taps yet short enough that Settings never feels stuck.
     */
    @Test
    fun windowIsWideEnoughForThreeHumanTaps() {
        assertEquals(3, TRIPLE_CLICK_COUNT)
        assertTrue("window too tight for three taps", TRIPLE_CLICK_WINDOW_MS >= 3 * 200L)
        assertTrue("window too long, Settings would feel stuck", TRIPLE_CLICK_WINDOW_MS <= 1_500L)
    }

    // ── Defaults match enable_floating_bubble.md ──

    @Test
    fun defaultsMatchTheSpec() {
        assertEquals(ScanInterval(4, 7), DEFAULT_SAFE_INTERVAL)
        assertEquals(ScanInterval(5, 8), DEFAULT_WARNING_INTERVAL)
    }

    // ── sanitizeInterval: free-form input must never reach the timer raw ──

    @Test
    fun sanitize_keepsAValidWindowUntouched() {
        assertEquals(ScanInterval(4, 7), sanitizeInterval(4, 7))
        assertEquals(ScanInterval(1, 600), sanitizeInterval(1, 600))
        assertEquals(
            ScanInterval(SCAN_INTERVAL_FLOOR_SECONDS, SCAN_INTERVAL_CEILING_SECONDS),
            sanitizeInterval(SCAN_INTERVAL_FLOOR_SECONDS, SCAN_INTERVAL_CEILING_SECONDS)
        )
    }

    @Test
    fun sanitize_swapsAReversedWindow() {
        assertEquals(ScanInterval(5, 12), sanitizeInterval(12, 5))
        assertEquals(ScanInterval(1, 600), sanitizeInterval(9_999, -20))
    }

    @Test
    fun sanitize_clampsOutOfRangeInput() {
        assertEquals(ScanInterval(1, 1), sanitizeInterval(-50, 0))
        assertEquals(ScanInterval(600, 600), sanitizeInterval(5_000, 9_000))
        assertEquals(ScanInterval(3, 600), sanitizeInterval(3, 5_000))
        assertEquals(ScanInterval(1, 40), sanitizeInterval(0, 40))
    }

    /**
     * The invariant the scan timer depends on: `max - min + 1` must be >= 1, or
     * `Random.nextLong` throws IllegalArgumentException inside the service.
     */
    @Test
    fun sanitize_guaranteesARollableSpan() {
        listOf(0 to 0, -5 to -1, 999 to 1, 7 to 7, 1_000 to 2_000, 60 to 5).forEach { (min, max) ->
            val interval = sanitizeInterval(min, max)
            assertTrue(
                "$min..$max -> $interval must have max >= min",
                interval.maxSeconds >= interval.minSeconds
            )
            assertTrue(
                "$min..$max -> $interval must stay inside the hard bounds",
                interval.minSeconds >= SCAN_INTERVAL_FLOOR_SECONDS &&
                    interval.maxSeconds <= SCAN_INTERVAL_CEILING_SECONDS
            )
        }
    }

    // ── intervalFor ──

    @Test
    fun intervalForPicksTheWindowMatchingTheMode() {
        val safe = ScanInterval(2, 3)
        val warning = ScanInterval(9, 11)
        assertEquals(safe, intervalFor(ScanMode.SAFE, safe, warning))
        assertEquals(warning, intervalFor(ScanMode.WARNING, safe, warning))
    }

    // ── scanDelayMs honours the configured windows ──

    @Test
    fun customWindowsDriveTheRoll() {
        val safe = ScanInterval(2, 3)
        val warning = ScanInterval(9, 11)
        repeat(2_000) {
            assertTrue(scanDelayMs(ScanMode.SAFE, safe, warning) in 2_000L..3_000L)
            assertTrue(scanDelayMs(ScanMode.WARNING, safe, warning) in 9_000L..11_000L)
        }
    }

    /** Both bounds stay inclusive after the window becomes configurable. */
    @Test
    fun customWindowBoundsAreInclusive() {
        val safe = ScanInterval(2, 3)
        val rolled = (1..2_000)
            .map { scanDelayMs(ScanMode.SAFE, safe, random = Random(7 * it)) }
            .toSet()
        assertEquals(setOf(2_000L, 3_000L), rolled)
    }

    @Test
    fun widenedWindowReachesItsNewExtremes() {
        val warning = ScanInterval(1, 600)
        val rolled = (1..5_000).map { scanDelayMs(ScanMode.WARNING, random = Random(it)) }
        // With the default window still in force for SAFE, WARNING must be the one
        // that spans 1..600 s once overridden.
        val overridden = (1..5_000)
            .map { scanDelayMs(ScanMode.WARNING, DEFAULT_SAFE_INTERVAL, warning, Random(it)) }
            .toSet()
        assertTrue(rolled.all { it in 5_000L..8_000L })
        assertEquals(1_000L, overridden.min())
        assertEquals(600_000L, overridden.max())
    }

    /** A degenerate single-value window must roll a constant, not throw. */
    @Test
    fun degenerateWindowProducesAConstantDelay() {
        val fixed = ScanInterval(6, 6)
        assertEquals(6_000L, scanDelayMs(ScanMode.SAFE, fixed, fixed))
        assertEquals(6_000L, scanDelayMs(ScanMode.WARNING, fixed, fixed))
        assertEquals(6_000L, sanitizeInterval(6, 6).let { scanDelayMs(ScanMode.SAFE, it, it) })
    }

    @Test
    fun rollsStayOnWholeSeconds() {
        val safe = ScanInterval(2, 9)
        val warning = ScanInterval(3, 12)
        repeat(2_000) {
            assertEquals(0L, scanDelayMs(ScanMode.SAFE, safe, warning) % 1_000L)
            assertEquals(0L, scanDelayMs(ScanMode.WARNING, safe, warning) % 1_000L)
        }
    }

    /** The modes must stay independently configurable. */
    @Test
    fun changingOneWindowLeavesTheOtherAlone() {
        val safe = ScanInterval(20, 21)
        repeat(500) {
            assertTrue(scanDelayMs(ScanMode.SAFE, safe, DEFAULT_WARNING_INTERVAL) in 20_000L..21_000L)
            assertTrue(scanDelayMs(ScanMode.WARNING, safe, DEFAULT_WARNING_INTERVAL) in 5_000L..8_000L)
        }
    }
}
