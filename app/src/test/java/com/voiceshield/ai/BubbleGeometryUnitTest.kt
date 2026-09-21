package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the two geometry rules behind the bubble drag gesture.
 *
 * - [isOverDismissTarget] (disable_floating_bubble.md): the X target is only
 *   "hit" once the bubble centre comes within the overlap threshold, which is
 *   what gates the shrink + haptic feedback and the dismiss-on-release.
 * - [nearestEdgeX] (enable_floating_bubble.md): the bubble is freely draggable
 *   and, on release, springs to whichever screen edge is nearer.
 *
 * Both are top-level functions with no Android dependencies, so they run on the
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

    private companion object {
        // 1080x2340-ish screen in pixels: container 200, margin 40.
        const val SCREEN_W = 1_080
        const val CONTAINER = 200
        const val MARGIN = 40
        const val LEFT = MARGIN                          // 40
        const val RIGHT = SCREEN_W - CONTAINER - MARGIN  // 840
        const val THRESHOLD = 40f
    }
}
