package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the optional accessibility capture backend: the polling interval (which
 * must respect the system's screenshot rate limit) and the enabled-service check.
 */
class CaptureBackendUnitTest {

    // ── Polling interval ──

    @Test
    fun interval_neverDropsBelowTheSystemLimit() {
        // Asking for the fastest rate still yields the floor (200 ms < 700 ms).
        assertEquals(ACCESSIBILITY_MIN_INTERVAL_MS, accessibilityIntervalMs(5f))
        assertEquals(ACCESSIBILITY_MIN_INTERVAL_MS, accessibilityIntervalMs(2f))
    }

    @Test
    fun interval_tracksSlowRates() {
        // 1 fps -> 1000 ms, already above the floor.
        assertEquals(1000L, accessibilityIntervalMs(1f))
        // 0.5 fps -> one frame every 2 s.
        assertEquals(2000L, accessibilityIntervalMs(0.5f))
    }

    @Test
    fun interval_fallsBackForUnusableInput() {
        // NaN sanitises to 1 fps -> 1000 ms.
        assertEquals(1000L, accessibilityIntervalMs(Float.NaN))
    }

    // ── Enabled-service check ──

    private val flat = "com.voiceshield.ai/com.voiceshield.ai.ScreenshotAccessibilityService"
    private val short = "com.voiceshield.ai/.ScreenshotAccessibilityService"

    @Test
    fun enabled_matchesFlatOrShortFormAndIgnoresCase() {
        assertTrue(accessibilityServiceEnabledIn(flat, flat, short))
        assertTrue(accessibilityServiceEnabledIn(short, flat, short))
        assertTrue(accessibilityServiceEnabledIn(flat.uppercase(), flat, short))
    }

    @Test
    fun enabled_handlesColonSeparatedList() {
        val setting = "com.example.digitaldextox/.AntiCheatService:$short"
        assertTrue(accessibilityServiceEnabledIn(setting, flat, short))
    }

    @Test
    fun disabled_whenAbsentOrBlank() {
        assertFalse(accessibilityServiceEnabledIn(null, flat, short))
        assertFalse(accessibilityServiceEnabledIn("", flat, short))
        assertFalse(accessibilityServiceEnabledIn("com.other/.Service", flat, short))
    }

    @Test
    fun backendDefaultsToMediaProjection() {
        // The enum exists with the documented default member; ProtectionState.getCaptureBackend
        // degrades to this value for anything unrecognised.
        assertEquals("MEDIA_PROJECTION", CaptureBackend.valueOf("MEDIA_PROJECTION").name)
        assertEquals(2, CaptureBackend.values().size)
    }
}
