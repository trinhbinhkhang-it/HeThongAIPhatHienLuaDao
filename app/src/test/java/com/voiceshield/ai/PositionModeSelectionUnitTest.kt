package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for developer_setting_page.md §5, "Đổi chế độ dựa trên vị trí bubble".
 *
 * The spec splits into two halves and both are covered here:
 *  - *geometry* — which edge a released bubble comes to rest on ([nearestEdgeX], the existing
 *    enable_floating_bubble.md snap rule) and which mode that edge selects ([bubbleEdgeOf],
 *    [modeForEdge]);
 *  - *gating* — the conditions under which a completed snap may actually move the mode
 *    ([shouldApplyEdgeMode]).
 *
 * The one thing that cannot be expressed purely is "The mode changes only after the snap is
 * complete": that is a property of the ValueAnimator, and FloatingBubbleService enforces it by
 * calling [shouldApplyEdgeMode] only from the animator's end callback (and immediately, when the
 * bubble was already resting on its target edge so there is nothing to animate).
 */
class PositionModeSelectionUnitTest {

    // Realistic geometry: a 1080 px wide display, the app's 100 dp bubble container and
    // 14 dp edge margin at 2.75 density.
    private val screenWidth = 1080
    private val containerWidth = 275
    private val edgeMargin = 38

    private val leftEdgeX get() = nearestEdgeX(0, containerWidth, screenWidth, edgeMargin)
    private val rightEdgeX get() = nearestEdgeX(screenWidth, containerWidth, screenWidth, edgeMargin)

    private fun snapTarget(dragX: Int) =
        nearestEdgeX(dragX, containerWidth, screenWidth, edgeMargin)

    private fun edgeMode(
        edge: BubbleEdge,
        currentMode: ScanMode,
        autoSelection: AutoModeSelection = AutoModeSelection.BUBBLE_POSITION,
        popupVisible: Boolean = false,
        bubbleActive: Boolean = false
    ) = shouldApplyEdgeMode(autoSelection, popupVisible, bubbleActive, currentMode, edge)

    // ── Geometry: where a released bubble lands ──────────────────────────────

    @Test
    fun theSnapTargetsAreTheTwoEdges() {
        assertEquals(edgeMargin, leftEdgeX)
        assertEquals(screenWidth - containerWidth - edgeMargin, rightEdgeX)
        assertEquals(BubbleEdge.LEFT, bubbleEdgeOf(leftEdgeX, edgeMargin))
        assertEquals(BubbleEdge.RIGHT, bubbleEdgeOf(rightEdgeX, edgeMargin))
    }

    /** Releasing in the left half lands on the left edge, which selects Safe Mode. */
    @Test
    fun aReleaseInTheLeftHalfSelectsSafeMode() {
        val crossover = (screenWidth - containerWidth) / 2
        listOf(0, 1, 100, leftEdgeX, 300, crossover).forEach { dragX ->
            val target = snapTarget(dragX)
            assertEquals("released at $dragX", leftEdgeX, target)
            assertEquals("released at $dragX", BubbleEdge.LEFT, bubbleEdgeOf(target, edgeMargin))
            assertEquals(
                "released at $dragX",
                ScanMode.SAFE,
                modeForEdge(bubbleEdgeOf(target, edgeMargin))
            )
        }
    }

    /** Releasing in the right half lands on the right edge, which selects Warning Mode. */
    @Test
    fun aReleaseInTheRightHalfSelectsWarningMode() {
        val crossover = (screenWidth - containerWidth) / 2
        listOf(crossover + 1, 700, 900, rightEdgeX, screenWidth).forEach { dragX ->
            val target = snapTarget(dragX)
            assertEquals("released at $dragX", rightEdgeX, target)
            assertEquals("released at $dragX", BubbleEdge.RIGHT, bubbleEdgeOf(target, edgeMargin))
            assertEquals(
                "released at $dragX",
                ScanMode.WARNING,
                modeForEdge(bubbleEdgeOf(target, edgeMargin))
            )
        }
    }

    /** The crossover is a single pixel wide, so there is no ambiguous band in the middle. */
    @Test
    fun theCrossoverBetweenTheTwoEdgesIsExactlyOnePixel() {
        val crossover = (screenWidth - containerWidth) / 2
        assertEquals(BubbleEdge.LEFT, bubbleEdgeOf(snapTarget(crossover), edgeMargin))
        assertEquals(BubbleEdge.RIGHT, bubbleEdgeOf(snapTarget(crossover + 1), edgeMargin))
    }

    // ── §5 "Moving the bubble vertically along the same edge does not change the mode" ──

    /**
     * A vertical drag never changes x, so the snap target is the edge the bubble already sits
     * on and the gate reports nothing to do — whatever height it was dragged to.
     */
    @Test
    fun verticalMovementAlongTheSameEdgeNeverChangesTheMode() {
        listOf(BubbleEdge.LEFT to ScanMode.SAFE, BubbleEdge.RIGHT to ScanMode.WARNING)
            .forEach { (edge, alreadySelected) ->
                // Any height: the vertical position is irrelevant to the edge, and therefore
                // to the mode.
                listOf(0, 120, 480, 900, 1_500, 2_200).forEach { height ->
                    assertFalse(
                        "$edge at height $height with $alreadySelected already selected",
                        edgeMode(edge, alreadySelected)
                    )
                }
            }
    }

    /**
     * A drag that lifts the bubble off its edge and drops it back still snaps to the *same*
     * edge, so the mode is unchanged and nothing is re-announced.
     */
    @Test
    fun aWobbleThatSnapsBackToTheSameEdgeDoesNotChangeTheMode() {
        val wobble = snapTarget(leftEdgeX + 40)
        assertEquals("a wobble still springs back to the left edge", leftEdgeX, wobble)
        assertFalse(edgeMode(bubbleEdgeOf(wobble, edgeMargin), ScanMode.SAFE))
    }

    /** Crossing to the other edge *is* a change, and picks the other mode. */
    @Test
    fun crossingToTheOtherEdgeDoesChangeTheMode() {
        assertTrue(edgeMode(BubbleEdge.RIGHT, ScanMode.SAFE))
        assertEquals(ScanMode.WARNING, modeForEdge(BubbleEdge.RIGHT))
        assertTrue(edgeMode(BubbleEdge.LEFT, ScanMode.WARNING))
        assertEquals(ScanMode.SAFE, modeForEdge(BubbleEdge.LEFT))
    }

    // ── §3 The method has to be the selected one ─────────────────────────────

    /** With any other automatic method chosen, the very same snap must do nothing. */
    @Test
    fun edgeSelectionOnlyRunsWhenItIsTheSelectedMethod() {
        listOf(AutoModeSelection.DISABLED, AutoModeSelection.SWIPE_LEFT_RIGHT).forEach { auto ->
            BubbleEdge.values().forEach { edge ->
                ScanMode.values().forEach { current ->
                    assertFalse(
                        "$auto/$edge/$current",
                        edgeMode(edge, current, autoSelection = auto)
                    )
                }
            }
        }
    }

    // ── §8 A visible In-App Pop-up blocks the gesture ────────────────────────

    @Test
    fun aVisiblePopupBlocksEdgeSelection() {
        BubbleEdge.values().forEach { edge ->
            ScanMode.values().forEach { current ->
                assertFalse(
                    "$edge/$current with a pop-up up",
                    edgeMode(edge, current, popupVisible = true)
                )
            }
        }
    }

    // ── §5 / §6 "The change applies only when the bubble is inactive" ────────

    /**
     * While a detection session runs the mode is locked (§6), so dragging the bubble to the
     * other edge must not move it — "Moving the bubble while it is active does not change the
     * current session mode."
     */
    @Test
    fun edgeSelectionIsRefusedWhileTheBubbleIsActive() {
        BubbleEdge.values().forEach { edge ->
            ScanMode.values().forEach { current ->
                assertFalse(
                    "$edge/$current while active",
                    edgeMode(edge, current, bubbleActive = true)
                )
            }
        }
    }

    // ── The gate is exactly the conjunction of its four conditions ───────────

    /**
     * Exhaustive: the gate is true only when the method is selected, no pop-up is up, the
     * bubble is inactive *and* the edge asks for a different mode. Pinning the full truth table
     * stops a future edit from loosening any one condition by accident.
     */
    @Test
    fun theGateIsExactlyTheConjunctionOfItsConditions() {
        AutoModeSelection.values().forEach { auto ->
            listOf(false, true).forEach { popup ->
                listOf(false, true).forEach { active ->
                    ScanMode.values().forEach { current ->
                        BubbleEdge.values().forEach { edge ->
                            val expected = auto == AutoModeSelection.BUBBLE_POSITION &&
                                !popup && !active && current != modeForEdge(edge)
                            assertEquals(
                                "$auto/popup=$popup/active=$active/$current/$edge",
                                expected,
                                edgeMode(edge, current, auto, popup, active)
                            )
                        }
                    }
                }
            }
        }
    }

    /** §7: the feedback owed for a change the gate allowed through. */
    @Test
    fun anAllowedEdgeChangeCarriesTheConfiguredFeedback() {
        ModeFeedback.values().forEach { setting ->
            assertEquals(
                "$setting",
                setting,
                feedbackForModeChange(ScanMode.SAFE, modeForEdge(BubbleEdge.RIGHT), setting)
            )
            assertEquals(
                "$setting on a no-op must stay silent",
                ModeFeedback.DISABLED,
                feedbackForModeChange(ScanMode.SAFE, modeForEdge(BubbleEdge.LEFT), setting)
            )
        }
    }
}
