package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the "Version 1.0.1" footer gestures on Page 1.
 *
 * developer_setting_page.md made the hold a *setting*, so these tests are written against
 * every [HoldDuration] rather than one hard-coded number:
 *  - §2's press-and-hold threshold, which drives both §1's hold-based opening methods and
 *    §4's hold-then-swipe mode selection ([isVersionLongPress]);
 *  - the triple tap that opens the hidden developer page ([isTripleClick]), unchanged.
 *
 * The gesture *decision* built on top of these primitives — which opening method wins, and
 * when a swipe outranks it — lives in [resolveFooterRelease] and is covered by
 * [ScanModeInteractionUnitTest].
 */
class VersionFooterGestureUnitTest {

    // ── §2 The configurable hold ─────────────────────────────────────────────

    /** The spec's three options, and 1 second as the default. */
    @Test
    fun holdDurationOffersExactlyTheSpecifiedOptions() {
        assertEquals(
            listOf(500L, 1_000L, 2_000L),
            HoldDuration.values().map { it.milliseconds }
        )
        assertEquals(HoldDuration.ONE_SECOND, DEFAULT_HOLD_DURATION)
        assertEquals(1_000L, DEFAULT_VERSION_HOLD_MS)
    }

    /** Each option is inclusive at exactly its own threshold, and nothing shorter. */
    @Test
    fun everyHoldOptionFiresAtItsOwnThresholdAndNotBefore() {
        HoldDuration.values().forEach { duration ->
            val required = duration.milliseconds
            assertTrue(
                "$duration at exactly $required ms",
                isVersionLongPress(required, false, required)
            )
            assertTrue(
                "$duration beyond $required ms",
                isVersionLongPress(required + 1, false, required)
            )
            assertTrue(
                "$duration far beyond $required ms",
                isVersionLongPress(60_000L, false, required)
            )
            assertFalse("$duration at zero", isVersionLongPress(0L, false, required))
            assertFalse(
                "$duration one ms short",
                isVersionLongPress(required - 1, false, required)
            )
        }
    }

    /**
     * The acceptance criterion "Releasing before the configured duration does not trigger the
     * action", checked against the default and both other options.
     */
    @Test
    fun releasingEarlyNeverTriggersTheAction() {
        assertFalse(isVersionLongPress(80L, false, HoldDuration.HALF_SECOND.milliseconds))
        assertFalse(isVersionLongPress(499L, false, HoldDuration.HALF_SECOND.milliseconds))
        assertFalse(isVersionLongPress(999L, false, HoldDuration.ONE_SECOND.milliseconds))
        assertFalse(isVersionLongPress(1_999L, false, HoldDuration.TWO_SECONDS.milliseconds))
    }

    /** Movement voids the hold however long the finger stays down. */
    @Test
    fun movementPastTheSlop_voidsTheHold() {
        HoldDuration.values().forEach { duration ->
            assertFalse(isVersionLongPress(duration.milliseconds, true, duration.milliseconds))
            assertFalse(isVersionLongPress(Long.MAX_VALUE, true, duration.milliseconds))
        }
    }

    /** The default parameter is the default setting, so a bare call matches the spec. */
    @Test
    fun theDefaultParameterIsTheDefaultSetting() {
        assertEquals(DEFAULT_HOLD_DURATION.milliseconds, DEFAULT_VERSION_HOLD_MS)
        assertTrue(isVersionLongPress(DEFAULT_HOLD_DURATION.milliseconds, movedPastSlop = false))
        assertFalse(isVersionLongPress(DEFAULT_HOLD_DURATION.milliseconds - 1, movedPastSlop = false))
    }

    /** The required duration is a parameter, so the rule is reusable and testable. */
    @Test
    fun requiredDurationIsConfigurable() {
        assertTrue(isVersionLongPress(600L, false, requiredMs = 500L))
        assertFalse(isVersionLongPress(400L, false, requiredMs = 500L))
        assertTrue(isVersionLongPress(0L, false, requiredMs = 0L))
    }

    // ── The hold must not fire on an ordinary tap ────────────────────────────

    /**
     * ViewConfiguration.getLongPressTimeout() is ~500 ms and a real tap is far shorter than
     * either. Even the shortest §2 option therefore cannot be triggered by an accidental touch.
     */
    @Test
    fun shortestHoldOptionIsStillLongerThanAnOrdinaryTap() {
        val ordinaryTapMs = 120L
        val shortest = HoldDuration.values().minOf { it.milliseconds }
        assertTrue("shortest hold=$shortest ms tap=$ordinaryTapMs ms", ordinaryTapMs < shortest)
        assertFalse(isVersionLongPress(ordinaryTapMs, false, shortest))
    }

    // ── The hold must not swallow the triple tap ──────────────────────────────

    /**
     * The two gestures share one view. Three taps have to fit inside [TRIPLE_CLICK_WINDOW_MS],
     * and none of them may be long enough to be mistaken for a hold at *any* §2 setting —
     * otherwise tap 2 or 3 would open Page 2 instead of reaching the footer.
     */
    @Test
    fun tripleTapBurstStillFits_andNoTapInsideItLooksLikeAHold() {
        assertTrue(isTripleClick(TRIPLE_CLICK_COUNT, TRIPLE_CLICK_WINDOW_MS))
        val perTap = TRIPLE_CLICK_WINDOW_MS / TRIPLE_CLICK_COUNT
        HoldDuration.values().forEach { duration ->
            assertFalse(
                "$perTap ms per tap must not read as a $duration hold",
                isVersionLongPress(perTap, false, duration.milliseconds)
            )
        }
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
     * The burst window now does double duty: it is also how long §1's single-click navigation
     * is deferred, so that taps 2 and 3 still land on the footer. It has to stay wide enough
     * for three human taps and short enough that a single click does not feel broken.
     */
    @Test
    fun burstWindowStillFitsThreeHumanTaps() {
        assertEquals(3, TRIPLE_CLICK_COUNT)
        assertTrue(TRIPLE_CLICK_WINDOW_MS >= 3 * 200L)
        assertTrue(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS))
        assertFalse(isClickBurstOpen(TRIPLE_CLICK_WINDOW_MS + 1))
    }
}
