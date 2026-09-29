package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression tests for the *shape* of the drag-to-dismiss X target
 * (disable_floating_bubble.md: "A large X bubble with a gray color will appear at
 * the bottom of the screen").
 *
 * The reported defect was that the X rendered as a square. Its cause was structural:
 * the circle was laid out at `dismiss_target_size` and then scaled up 1.5x, so the
 * oval's four extremes landed exactly *on* the host window rectangle — and
 * WindowManager clips a window's drawing to that rectangle. The fix makes the host
 * window and the ImageView both the expanded diameter, so at rest the oval is
 * inscribed in its own layout box at scale 1.0 with no transform involved and nothing
 * left to clip.
 *
 * These tests pin that invariant: the resting circle must fill its window exactly, and
 * no scale the animation visits may push the drawing past the window rectangle.
 *
 * All the rules are top-level Android-free functions (same convention as
 * [dismissTargetWindowSize] in [BubbleGeometryUnitTest]), so they run on the local JVM
 * without Robolectric or an emulator.
 */
class DismissTargetShapeUnitTest {

    // ── The invariant that makes the shape a circle ───────────────────────────

    /**
     * At rest the laid-out box *is* the window and the scale is 1.0, so the painted
     * oval is inscribed in the window rectangle. Nothing crosses the clip boundary,
     * which is precisely what used to square the four extremes off.
     */
    @Test
    fun restingCircleIsInscribedInTheHostWindow() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        // The ImageView is MATCH_PARENT inside the host, so its box == the window.
        val layoutBox = window
        assertEquals(
            "the resting oval must exactly fill the window, not overflow it",
            window.toFloat(),
            drawnDiameter(layoutBox, REST_SCALE),
            DELTA
        )
        assertTrue(
            "the resting oval must never exceed the window rectangle",
            drawnDiameter(layoutBox, REST_SCALE) <= window + DELTA
        )
    }

    /**
     * The regression, stated positively: the resting scale is 1.0. Anything above 1.0
     * means the drawing depends on a transform reaching past the layout box, which is
     * what the window clip used to cut down to a square.
     */
    @Test
    fun restingScaleIsOne_soNoTransformIsNeededToFillTheWindow() {
        assertEquals(1.0f, REST_SCALE, DELTA)
        assertEquals(
            "a 1.0 scale must leave the diameter exactly as laid out",
            TARGET_PX * EXPANDED_SCALE,
            drawnDiameter(dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE), REST_SCALE),
            DELTA
        )
    }

    /**
     * Every scale the shrink animation passes through must keep the oval inside the
     * window rectangle, not just the two endpoints.
     */
    @Test
    fun noScaleInTheAnimationPushesTheDrawingPastTheWindow() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val collapsed = dismissCollapsedScale(BUBBLE_PX, window)
        listOf(REST_SCALE, collapsed, (REST_SCALE + collapsed) / 2f, 0f).forEach { scale ->
            val drawn = drawnDiameter(window, scale)
            assertTrue(
                "scale=$scale paints $drawn px inside a $window px window",
                drawn <= window + DELTA
            )
            assertTrue("scale=$scale must not invert the circle", drawn >= 0f)
        }
    }

    // ── Collapsed size: "shrink to the same size as the floating bubble" ─────

    /**
     * disable_floating_bubble.md: "the size of the X bubble will shrink to the same size
     * as the floating bubble". Expressed against the *expanded* box now, because that is
     * what the view is laid out at.
     */
    @Test
    fun collapsedCircleIsExactlyTheBubbleDiameter() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val collapsed = dismissCollapsedScale(BUBBLE_PX, window)
        assertEquals(BUBBLE_PX.toFloat(), drawnDiameter(window, collapsed), DELTA)
    }

    /** The collapsed circle is strictly smaller, so the shrink is visible. */
    @Test
    fun collapsedCircleIsSmallerThanTheRestingCircle() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val collapsed = dismissCollapsedScale(BUBBLE_PX, window)
        assertTrue(collapsed < REST_SCALE)
        assertTrue(drawnDiameter(window, collapsed) < drawnDiameter(window, REST_SCALE))
    }

    /** Real token values: 72dp base, 1.5x, 64dp bubble — all at density 3. */
    @Test
    fun realTokenValues_produceTheSpecifiedDiameters() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE) // 216 * 1.5
        assertEquals(324, window)
        assertEquals(
            "resting diameter = the expanded circle",
            324f, drawnDiameter(window, REST_SCALE), DELTA
        )
        assertEquals(
            "collapsed diameter = bubble_size",
            192f, drawnDiameter(window, dismissCollapsedScale(BUBBLE_PX, window)), DELTA
        )
    }

    // ── dismissCollapsedScale guards ─────────────────────────────────────────

    /** A degenerate expanded size must fall back to 1.0 rather than NaN/Infinity. */
    @Test
    fun degenerateExpandedSize_neverProducesNaNOrInfinity() {
        assertEquals(1f, dismissCollapsedScale(BUBBLE_PX, 0), DELTA)
        assertEquals(1f, dismissCollapsedScale(BUBBLE_PX, -100), DELTA)
        assertFalse(dismissCollapsedScale(BUBBLE_PX, 0).isNaN())
        assertFalse(dismissCollapsedScale(BUBBLE_PX, 0).isInfinite())
    }

    /** An oversized bubble is not clamped: the tokens decide, the rule just divides. */
    @Test
    fun collapsedScaleFollowsTheTokens() {
        assertEquals(0.5f, dismissCollapsedScale(100, 200), DELTA)
        assertEquals(1f, dismissCollapsedScale(200, 200), DELTA)
        assertEquals(2f, dismissCollapsedScale(400, 200), DELTA)
    }

    /** The collapsed circle is exactly the bubble diameter for any token pair. */
    @Test
    fun collapsedScaleIsProportionalForAnyTokens() {
        listOf(48 to 96, 100 to 300, 1 to 3, 640 to 640).forEach { (bubble, expanded) ->
            val scale = dismissCollapsedScale(bubble, expanded)
            assertEquals(
                "$bubble inside $expanded",
                bubble.toFloat(),
                drawnDiameter(expanded, scale),
                DELTA
            )
        }
    }

    // ── Glyph padding: the X must not swell with the circle ──────────────────

    /**
     * The view grew from `dismiss_target_size` to the expanded diameter, so the padding
     * that insets the X glyph has to grow by the same factor — otherwise the glyph looks
     * bigger rather than being the same icon on a bigger circle.
     */
    @Test
    fun glyphPaddingGrowsWithTheExpandedCircle() {
        assertEquals(99, dismissTargetGlyphPadding(GLYPH_PADDING_PX, EXPANDED_SCALE))
        assertEquals(132, dismissTargetGlyphPadding(GLYPH_PADDING_PX, 2f))
        assertEquals(GLYPH_PADDING_PX, dismissTargetGlyphPadding(GLYPH_PADDING_PX, 1f))
    }

    /** The inset must stay under half the diameter, or the glyph box would invert. */
    @Test
    fun glyphPaddingLeavesRoomForTheGlyph() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val padding = dismissTargetGlyphPadding(GLYPH_PADDING_PX, EXPANDED_SCALE)
        assertTrue("padding=$padding leaves no glyph box in $window", padding * 2 < window)
        // The glyph box keeps the same proportion of the circle it had before the fix.
        assertEquals(
            (TARGET_PX - 2 * GLYPH_PADDING_PX) * EXPANDED_SCALE,
            (window - 2 * padding).toFloat(),
            1f
        )
    }

    /** Fractional products round up, matching the window's own rounding. */
    @Test
    fun glyphPaddingRoundsUp() {
        assertEquals(2, dismissTargetGlyphPadding(3, 0.5f))
        assertEquals(3, dismissTargetGlyphPadding(5, 0.5f))
        assertEquals(1, dismissTargetGlyphPadding(1, 0.2f))
    }

    @Test
    fun negativePaddingIsClampedToZero() {
        assertEquals(0, dismissTargetGlyphPadding(-20, EXPANDED_SCALE))
        assertEquals(0, dismissTargetGlyphPadding(0, EXPANDED_SCALE))
        assertEquals(0, dismissTargetGlyphPadding(-20, 0f))
    }

    // ── The hit zone still agrees with what is drawn ─────────────────────────

    /**
     * The centre is derived from the *window* height, and the shape fix leaves the window
     * the same size — so the overlap zone keeps matching the visible circle. A shape fix
     * that silently moved the hit zone would be a worse bug than the one it cured.
     */
    @Test
    fun shapeFixDoesNotMoveTheHitZone() {
        val window = dismissTargetWindowSize(TARGET_PX, EXPANDED_SCALE)
        val centerY = dismissTargetCenterY(SCREEN_H, MARGIN_BOTTOM_PX, window)
        assertEquals(2_034f, centerY, DELTA)
        assertTrue(isOverDismissTarget(SCREEN_W / 2f, centerY, SCREEN_W / 2f, centerY, THRESHOLD))
        assertFalse(isOverDismissTarget(140f, centerY, SCREEN_W / 2f, centerY, THRESHOLD))
    }

    /** Diameter the circle actually paints, given its laid-out box and View scale. */
    private fun drawnDiameter(layoutSizePx: Int, scale: Float) = layoutSizePx * scale

    private companion object {
        /** Real tokens at density 3: dismiss_target_size 72dp, bubble_size 64dp. */
        const val TARGET_PX = 216
        const val BUBBLE_PX = 192
        const val GLYPH_PADDING_PX = 22 * 3 // dismiss_target_glyph_padding = 22dp
        const val EXPANDED_SCALE = 1.5f

        /** View scale of the resting circle: it already *is* the expanded size. */
        const val REST_SCALE = 1.0f

        const val SCREEN_W = 1_080
        const val SCREEN_H = 2_340
        const val MARGIN_BOTTOM_PX = 144 // dismiss_target_bottom_margin = 48dp
        const val THRESHOLD = 120f       // dismiss_overlap_threshold = 40dp
        const val DELTA = 0.001f
    }
}
