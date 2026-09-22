package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the geometry and state rules behind the bubble gestures.
 *
 * - [isOverDismissTarget] (disable_floating_bubble.md): the X target is only
 *   "hit" once the bubble centre comes within the overlap threshold, which is
 *   what gates the shrink + haptic feedback and the dismiss-on-release.
 * - [nearestEdgeX] (enable_floating_bubble.md): the bubble is freely draggable
 *   and, on release, springs to whichever screen edge is nearer.
 * - [dismissTargetWindowSize] / [dismissTargetCenterY]: the X target's host window
 *   must be big enough for the *expanded* circle, or WindowManager clips it into
 *   a square and the hit zone drifts away from what the user sees.
 * - [isProtectionEffective] (enable_floating_bubble.md): the Home card may only
 *   claim "Đang bảo vệ" while a bubble really is on screen.
 *
 * All are top-level functions with no Android dependencies, so they run on the
 * local JVM exactly like scanDelayMs() (see [ScanModeUnitTest]).
 */
class BubbleGeometryUnitTest {

    // ── X-target overlap ─────────────────────────────────────────────────────

    @Test
    fun bubbleExactlyOnTarget_isOver() {
        assertTrue(isOverDismissTarget(540f, 2_200f, 540f, 2_200f, THRESHOLD))
    }

    @Test
    fun bubbleInsideThreshold_isOver() {
        assertTrue(isOverDismissTarget(570f, 2_200f, 540f, 2_200f, THRESHOLD))
        assertTrue(isOverDismissTarget(540f, 2_230f, 540f, 2_200f, THRESHOLD))
    }

    /** The comparison is inclusive, so landing exactly on the radius still counts. */
    @Test
    fun bubbleOnThresholdBoundary_isOver() {
        assertTrue(isOverDismissTarget(580f, 2_200f, 540f, 2_200f, THRESHOLD))
        assertTrue(isOverDismissTarget(540f, 2_240f, 540f, 2_200f, THRESHOLD))
    }

    @Test
    fun bubbleJustOutsideThreshold_isNotOver() {
        assertFalse(isOverDismissTarget(581f, 2_200f, 540f, 2_200f, THRESHOLD))
        assertFalse(isOverDismissTarget(540f, 2_241f, 540f, 2_200f, THRESHOLD))
    }

    /** Euclidean, not per-axis: 30px on both axes is ~42px apart, so it misses. */
    @Test
    fun diagonalDistance_isMeasuredEuclidean() {
        assertFalse(isOverDismissTarget(570f, 2_230f, 540f, 2_200f, THRESHOLD))
        assertTrue(isOverDismissTarget(568f, 2_228f, 540f, 2_200f, THRESHOLD))
    }

    /**
     * The real gesture: the X sits at the bottom-centre of a 1080x2340 screen,
     * so a bubble still parked on the left edge must not register as a hit.
     */
    @Test
    fun bubbleOnLeftEdge_neverHitsBottomCentreTarget() {
        assertFalse(isOverDismissTarget(140f, 2_200f, 540f, 2_246f, THRESHOLD))
    }

    // ── Nearest-edge snap ────────────────────────────────────────────────────

    @Test
    fun bubbleInLeftHalf_snapsToLeftEdge() {
        assertEquals(LEFT, nearestEdgeX(LEFT, CONTAINER, SCREEN_W, MARGIN))
        assertEquals(LEFT, nearestEdgeX(300, CONTAINER, SCREEN_W, MARGIN))
    }

    @Test
    fun bubbleInRightHalf_snapsToRightEdge() {
        assertEquals(RIGHT, nearestEdgeX(RIGHT, CONTAINER, SCREEN_W, MARGIN))
        assertEquals(RIGHT, nearestEdgeX(700, CONTAINER, SCREEN_W, MARGIN))
    }

    /** A dead-centre drop is a tie; it resolves to the left edge deterministically. */
    @Test
    fun bubbleExactlyAtCentre_snapsToLeftEdge() {
        assertEquals(LEFT, nearestEdgeX(440, CONTAINER, SCREEN_W, MARGIN))
        assertEquals(RIGHT, nearestEdgeX(441, CONTAINER, SCREEN_W, MARGIN))
    }

    /** The snap target is always one of the two edges, whatever the drag offset. */
    @Test
    fun snapAlwaysLandsOnAnEdge() {
        (-500..2_000).forEach { x ->
            val snapped = nearestEdgeX(x, CONTAINER, SCREEN_W, MARGIN)
            assertTrue("x=$x snapped to $snapped", snapped == LEFT || snapped == RIGHT)
        }
    }

    @Test
    fun outOfRangeOffsets_areClampedBeforeSnapping() {
        assertEquals(LEFT, nearestEdgeX(-9_999, CONTAINER, SCREEN_W, MARGIN))
        assertEquals(RIGHT, nearestEdgeX(99_999, CONTAINER, SCREEN_W, MARGIN))
    }

    /** A display narrower than the bubble container must not yield a negative right edge. */
    @Test
    fun degenerateScreen_collapsesToLeftEdge() {
        assertEquals(40, nearestEdgeX(0, containerWidth = 200, screenWidth = 100, edgeMargin = 40))
        assertEquals(40, nearestEdgeX(999, containerWidth = 200, screenWidth = 100, edgeMargin = 40))
    }

    @Test
    fun negativeMargin_isTreatedAsZero() {
        assertEquals(0, nearestEdgeX(0, CONTAINER, SCREEN_W, edgeMargin = -8))
    }

    // ── X-target host window sizing ──────────────────────────────────────────
    // WindowManager clips a window's drawing to the window rectangle, so the host
    // has to be at least as large as the *expanded* circle or the circle's
    // extremes are cut off and it renders as a square.

    /** 72dp at density 3 = 216px, expanded 1.5x = 324px — an exact fit, no clipping. */
    @Test
    fun hostWindow_fitsTheExpandedCircle() {
        assertEquals(324, dismissTargetWindowSize(216, EXPANDED_SCALE))
    }

    /** Fractional products round *up*: rounding down would clip the circle again. */
    @Test
    fun hostWindow_roundsUpFractionalSizes() {
        assertEquals(152, dismissTargetWindowSize(101, EXPANDED_SCALE))
        assertEquals(3, dismissTargetWindowSize(3, 0.9f))
    }

    /** The window is never smaller than the circle it hosts, whatever the scale. */
    @Test
    fun hostWindow_isNeverSmallerThanTheCircle() {
        assertEquals(216, dismissTargetWindowSize(216, 1f))
        assertEquals(216, dismissTargetWindowSize(216, 0.5f))
        assertEquals(216, dismissTargetWindowSize(216, 0f))
    }

    /** The collapsed state (bubble_size / target_size) is well inside the window. */
    @Test
    fun collapsedCircle_stillFitsTheHostWindow() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val collapsed = TARGET_PX * (BUBBLE_PX.toFloat() / TARGET_PX.toFloat())
        assertTrue("collapsed=$collapsed window=$window", collapsed <= window)
        assertTrue(window >= TARGET_PX * EXPANDED_SCALE)
    }

    @Test
    fun degenerateCircle_yieldsNonNegativeWindow() {
        assertEquals(0, dismissTargetWindowSize(0, EXPANDED_SCALE))
    }

    // ── X-target centre ──────────────────────────────────────────────────────

    /**
     * The circle is centred in its host window, so the *window* height decides the
     * centre. Using the circle height instead would put the hit zone 54px below
     * what the user actually sees.
     */
    @Test
    fun centerY_usesTheHostWindowHeight() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE) // 324
        assertEquals(2_340 - (MARGIN_BOTTOM_PX + window / 2f),
            dismissTargetCenterY(2_340, MARGIN_BOTTOM_PX, window), DELTA)
        assertEquals(2_034f, dismissTargetCenterY(2_340, MARGIN_BOTTOM_PX, window), DELTA)
    }

    /** A window whose height equals its bottom margin centres exactly on the fold. */
    @Test
    fun centerY_isMeasuredFromTheBottomEdgeUpwards() {
        assertEquals(1_000f, dismissTargetCenterY(1_100, 100, 0), DELTA)
        assertEquals(1_050f, dismissTargetCenterY(1_100, 0, 100), DELTA)
    }

    /**
     * End-to-end: with the real tokens, a bubble dropped on the visible X centre
     * must register as a hit, and one parked on the left edge must not.
     */
    @Test
    fun realTokenGeometry_hitZoneMatchesWhatIsDrawn() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val targetY = dismissTargetCenterY(SCREEN_H, MARGIN_BOTTOM_PX, window)
        val targetX = SCREEN_W / 2f

        assertTrue(isOverDismissTarget(targetX, targetY, targetX, targetY, THRESHOLD))
        assertFalse(isOverDismissTarget(140f, targetY, targetX, targetY, THRESHOLD))
    }

    // ── Effective protection state ───────────────────────────────────────────

    /** "Đang bảo vệ" requires *both* the persisted switch and a bubble on screen. */
    @Test
    fun protectionIsEffective_onlyWhenPersistedAndBubbleVisible() {
        assertTrue(isProtectionEffective(persistedActive = true, bubbleVisible = true))
    }

    /** The stale-flag case: pref says active but nothing is rendered. */
    @Test
    fun persistedFlagAlone_isNotProtection() {
        assertFalse(isProtectionEffective(persistedActive = true, bubbleVisible = false))
    }

    /** A live bubble with the switch off (mid-teardown) must not claim protection. */
    @Test
    fun liveBubbleWithoutTheSwitch_isNotProtection() {
        assertFalse(isProtectionEffective(persistedActive = false, bubbleVisible = true))
        assertFalse(isProtectionEffective(persistedActive = false, bubbleVisible = false))
    }

    private companion object {
        // 1080x2340-ish screen in pixels: container 200, margin 40.
        const val SCREEN_W = 1_080
        const val SCREEN_H = 2_340
        const val CONTAINER = 200
        const val MARGIN = 40
        const val LEFT = MARGIN                          // 40
        const val RIGHT = SCREEN_W - CONTAINER - MARGIN  // 840
        const val THRESHOLD = 40f
        /** JUnit 4's assertEquals(float, float) is deprecated; every float check needs a delta. */
        const val DELTA = 0.001f

        // dismiss_target_size = 72dp and bubble_size = 64dp at density 3.
        const val TARGET_PX = 216
        const val BUBBLE_PX = 192
        const val MARGIN_BOTTOM_PX = 144                 // dismiss_target_bottom_margin 48dp
        const val EXPANDED_SCALE = 1.5f
    }
}
