package com.voiceshield.ai

import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

/**
 * Pure-JVM tests for the Phase D scan-popup timing window (spec §3).
 *
 * scanDelayMs() is a top-level function in ProtectionState.kt with no Android
 * types, so it runs on the local JVM without Robolectric or an emulator.
 */
class ScanModeUnitTest {

    @Test
    fun scanMode_hasExactlyTwoValues() {
        assertEquals(2, ScanMode.values().size)
        assertEquals(ScanMode.SAFE, ScanMode.valueOf("SAFE"))
        assertEquals(ScanMode.WARNING, ScanMode.valueOf("WARNING"))
    }

    @Test
    fun safeDelay_staysInsideEightToTwentyFiveSeconds() {
        (1..10_000).forEach { seed ->
            val delay = scanDelayMs(ScanMode.SAFE, Random(seed))
            assertTrue("SAFE delay $delay must be >= 8_000", delay >= 8_000L)
            assertTrue("SAFE delay $delay must be <= 25_000", delay <= 25_000L)
            assertEquals("delay must be whole seconds", 0L, delay % 1_000L)
        }
    }

    @Test
    fun warningDelay_staysInsideSevenToTwentySeconds() {
        (1..10_000).forEach { seed ->
            val delay = scanDelayMs(ScanMode.WARNING, Random(seed))
            assertTrue("WARNING delay $delay must be >= 7_000", delay >= 7_000L)
            assertTrue("WARNING delay $delay must be <= 20_000", delay <= 20_000L)
            assertEquals("delay must be whole seconds", 0L, delay % 1_000L)
        }
    }

    /** Both bounds are inclusive, so the extremes of each window must be reachable. */
    @Test
    fun windowBounds_areInclusive() {
        val random = Random(424242)
        var safeMin = Long.MAX_VALUE
        var safeMax = Long.MIN_VALUE
        var warningMin = Long.MAX_VALUE
        var warningMax = Long.MIN_VALUE

        repeat(200_000) {
            val safe = scanDelayMs(ScanMode.SAFE, random)
            if (safe < safeMin) safeMin = safe
            if (safe > safeMax) safeMax = safe

            val warning = scanDelayMs(ScanMode.WARNING, random)
            if (warning < warningMin) warningMin = warning
            if (warning > warningMax) warningMax = warning
        }

        assertEquals("SAFE window must start at 8 s", 8_000L, safeMin)
        assertEquals("SAFE window must end at 25 s", 25_000L, safeMax)
        assertEquals("WARNING window must start at 7 s", 7_000L, warningMin)
        assertEquals("WARNING window must end at 20 s", 20_000L, warningMax)
    }

    /** The roll must actually vary — a constant would defeat the "random" spec. */
    @Test
    fun rollsAreRandom_notConstant() {
        val distinctSafe = (1..1_000).map { scanDelayMs(ScanMode.SAFE) }.distinct()
        assertTrue("expected a spread of SAFE delays, got $distinctSafe", distinctSafe.size > 5)

        val distinctWarning = (1..1_000).map { scanDelayMs(ScanMode.WARNING) }.distinct()
        assertTrue(
            "expected a spread of WARNING delays, got $distinctWarning",
            distinctWarning.size > 5
        )
    }

    /** The production call site uses the default Random source, not an injected one. */
    @Test
    fun defaultRandomSource_staysInsideWindow() {
        repeat(5_000) {
            assertTrue(scanDelayMs(ScanMode.SAFE) in 8_000L..25_000L)
            assertTrue(scanDelayMs(ScanMode.WARNING) in 7_000L..20_000L)
        }
    }
}
