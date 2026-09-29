package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the "Version 1.0.1" footer gestures on Page 1.
 *
 * Two deliberate gestures, and no accidental one:
 *  - press-and-hold for [VERSION_LONG_PRESS_MS] -> Page 2 (Settings), where the scan mode
 *    is configured. This *replaces* the single tap that used to navigate there.
 *  - triple tap -> the hidden developer page ([isTripleClick]).
 *
 * [isVersionLongPress] is a top-level Android-free function in the same convention as
 * [isTripleClick], so both gestures are testable on the local JVM.
 */
class VersionFooterGestureUnitTest {

    // ── The two-second hold ───────────────────────────────────────────────────

    /** The requirement is two seconds, and it is inclusive at exactly 2000 ms. */
    @Test
    fun holdOpensSettings_atExactlyTheRequiredDuration() {
        assertEquals(2_000L, VERSION_LONG_PRESS_MS)
        assertTrue(isVersionLongPress(VERSION_LONG_PRESS_MS, movedPastSlop = false))
    }

    @Test
    fun holdLongerThanRequired_stillOpensSettings() {
        assertTrue(isVersionLongPress(2_001L, false))
        assertTrue(isVersionLongPress(5_000L, false))
        assertTrue(isVersionLongPress(60_000L, false))
    }

    /** Anything short of the full hold must not navigate — that is the whole point. */
    @Test
    fun holdShorterThanRequired_doesNotOpenSettings() {
        assertFalse(isVersionLongPress(0L, false))
        assertFalse(isVersionLongPress(1L, false))
        assertFalse(isVersionLongPress(499L, false))
        assertFalse(isVersionLongPress(1_999L, false))
    }

    /** A single tap is now inert: it must neither open Settings nor the hidden page. */
    @Test
    fun singleTap_navigatesNowhere() {
        assertFalse("a tap is far short of the hold", isVersionLongPress(80L, false))
        assertFalse("and one tap is far short of a triple", isTripleClick(1, 0L))
    }

    /** Movement voids the hold however long the finger stays down. */
    @Test
    fun movementPastTheSlop_voidsTheHold() {
        assertFalse(isVersionLongPress(VERSION_LONG_PRESS_MS, movedPastSlop = true))
        assertFalse(isVersionLongPress(10_000L, movedPastSlop = true))
        assertFalse(isVersionLongPress(Long.MAX_VALUE, movedPastSlop = true))
    }

    /** Movement alone is not enough to fail — the duration still has to be checked. */
    @Test
    fun stationaryButShort_stillFails() {
        assertFalse(isVersionLongPress(1_999L, movedPastSlop = false))
    }

    /** The required duration is a parameter, so the rule is reusable and testable. */
    @Test
    fun requiredDurationIsConfigurable() {
        assertTrue(isVersionLongPress(600L, false, requiredMs = 500L))
        assertFalse(isVersionLongPress(400L, false, requiredMs = 500L))
        assertTrue(isVersionLongPress(0L, false, requiredMs = 0L))
    }

    // ── The hold must not fight the platform long-press ───────────────────────

    /**
     * ViewConfiguration.getLongPressTimeout() is ~500 ms. The footer hold has to be
     * comfortably longer, or the platform's own long-press semantics would feel like they
     * fire early and the "two seconds" requirement would not be observable.
     */
    @Test
    fun holdIsLongerThanThePlatformLongPressTimeout() {
        assertTrue(
            "VERSION_LONG_PRESS_MS must exceed the ~500 ms platform timeout",
            VERSION_LONG_PRESS_MS > 500L
        )
    }

    // ── The hold must not swallow the triple tap ──────────────────────────────

    /**
     * The two gestures share one view, so their windows must not overlap: three taps have
     * to fit inside [TRIPLE_CLICK_WINDOW_MS], and the hold must not fire before that
     * burst can complete — otherwise the third tap would land on Page 2 instead of the
     * footer.
     */
    @Test
    fun holdOutlastsTheTripleTapBurst_soTheTwoGesturesCannotCollide() {
        assertTrue(
            "hold=${VERSION_LONG_PRESS_MS}ms burst=${TRIPLE_CLICK_WINDOW_MS}ms",
            VERSION_LONG_PRESS_MS > TRIPLE_CLICK_WINDOW_MS
        )
        // Each of the three taps is short, so none of them can be mistaken for a hold.
        val perTap = TRIPLE_CLICK_WINDOW_MS / TRIPLE_CLICK_COUNT
        assertFalse(isVersionLongPress(perTap, false))
        assertTrue(isTripleClick(TRIPLE_CLICK_COUNT, TRIPLE_CLICK_WINDOW_MS))
    }

    // ── Regression: the hidden-page gesture is unchanged ──────────────────────

    @Test
    fun tripleTap_stillOpensTheHiddenPage() {
        assertTrue(isTripleClick(3, 0L))
        assertTrue(isTripleClick(3, TRIPLE_CLICK_WINDOW_MS))
        assertTrue(isTripleClick(4, 100L))
    }

    @Test
    fun slowOrPartialTaps_stillDoNotOpenTheHiddenPage() {
        assertFalse(isTripleClick(2, 0L))
        assertFalse(isTripleClick(3, TRIPLE_CLICK_WINDOW_MS + 1))
    }

    /**
     * The burst window no longer has to be wide enough to accommodate a *deferred*
     * single-tap navigation, but it does still have to fit three deliberate human taps.
     */
    @Test
    fun burstWindowStillFitsThreeHumanTaps() {
        assertEquals(3, TRIPLE_CLICK_COUNT)
        assertTrue(TRIPLE_CLICK_WINDOW_MS >= 3 * 200L)
        assertTrue(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS))
        assertFalse(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS + 1))
    }
}
