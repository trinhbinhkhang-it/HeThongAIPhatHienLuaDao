package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for developer_setting_page.md.
 *
 * Every rule the spec defines is a top-level Android-free function in ScanModeInteraction.kt,
 * so the whole interaction model — the §1 opening methods, the §2 hold duration, §4's swipe
 * selection, §5's edge selection, §6's session lock and §7's feedback — is verified here
 * without Robolectric or an emulator.
 *
 * The hold primitive itself ([isVersionLongPress]) and the triple tap are covered by
 * [VersionFooterGestureUnitTest]; this file covers the decisions built on top of them.
 */
class ScanModeInteractionUnitTest {

    // ── Acceptance criteria: the four defaults ───────────────────────────────

    /** "The default opening method is single click." */
    @Test
    fun defaultOpeningMethodIsSingleClick() {
        assertEquals(ScanModeEntry.SINGLE_CLICK, DEFAULT_SCAN_MODE_ENTRY)
        assertEquals(ScanModeEntry.SINGLE_CLICK, DEFAULT_SCAN_MODE_INTERACTION.entry)
    }

    /** "The default hold duration is 1 second." */
    @Test
    fun defaultHoldDurationIsOneSecond() {
        assertEquals(HoldDuration.ONE_SECOND, DEFAULT_HOLD_DURATION)
        assertEquals(1_000L, DEFAULT_HOLD_DURATION.milliseconds)
        assertEquals(HoldDuration.ONE_SECOND, DEFAULT_SCAN_MODE_INTERACTION.holdDuration)
    }

    /** "The default automatic mode-selection method is disabled." */
    @Test
    fun defaultAutoModeSelectionIsDisabled() {
        assertEquals(AutoModeSelection.DISABLED, DEFAULT_AUTO_MODE_SELECTION)
        assertEquals(AutoModeSelection.DISABLED, DEFAULT_SCAN_MODE_INTERACTION.autoSelection)
    }

    /** "The default mode-change feedback is disabled." */
    @Test
    fun defaultModeFeedbackIsDisabled() {
        assertEquals(ModeFeedback.DISABLED, DEFAULT_MODE_FEEDBACK)
        assertEquals(ModeFeedback.DISABLED, DEFAULT_SCAN_MODE_INTERACTION.feedback)
    }

    // ── "Exactly one opening method" / "At most one automatic method" ────────

    /**
     * Both criteria are satisfied *by construction*: each setting is a single enum value, so
     * there is no representable state in which two methods are on at once. These assertions pin
     * the shape — one value per setting, and the exact option lists the spec gives.
     */
    @Test
    fun eachSettingHoldsExactlyOneOption_andTheOptionListsMatchTheSpec() {
        assertEquals(3, ScanModeEntry.values().size)
        assertEquals(3, HoldDuration.values().size)
        // "Disabled", the swipe method and the bubble-position method — and nothing else.
        assertEquals(3, AutoModeSelection.values().size)
        assertEquals(3, ModeFeedback.values().size)

        // A bundle always carries one value per setting; there is no "both" state to build.
        val settings = ScanModeInteractionSettings(
            entry = ScanModeEntry.PRESS_AND_HOLD,
            holdDuration = HoldDuration.TWO_SECONDS,
            autoSelection = AutoModeSelection.SWIPE_LEFT_RIGHT,
            feedback = ModeFeedback.TOAST
        )
        assertEquals(1, listOf(settings.entry).size)
        assertEquals(1, listOf(settings.autoSelection).size)
    }

    // ── Persistence shape: enum names round-trip, junk falls back ────────────

    @Test
    fun everyOptionRoundTripsThroughItsStoredName() {
        ScanModeEntry.values().forEach { assertEquals(it, scanModeEntryFrom(it.name)) }
        HoldDuration.values().forEach { assertEquals(it, holdDurationFrom(it.name)) }
        AutoModeSelection.values().forEach { assertEquals(it, autoModeSelectionFrom(it.name)) }
        ModeFeedback.values().forEach { assertEquals(it, modeFeedbackFrom(it.name)) }
    }

    /**
     * A missing key (first run after an upgrade) or a hand-edited value must degrade to the spec
     * default rather than crash the gesture that reads it — the same posture [sanitizeInterval]
     * takes towards the roll windows.
     */
    @Test
    fun missingOrCorruptValuesFallBackToTheSpecDefaults() {
        listOf(null, "", "SINGLE_TAP", "single_click", "3", "PRESS_HOLD").forEach { raw ->
            assertEquals("entry=$raw", DEFAULT_SCAN_MODE_ENTRY, scanModeEntryFrom(raw))
            assertEquals("hold=$raw", DEFAULT_HOLD_DURATION, holdDurationFrom(raw))
            assertEquals("auto=$raw", DEFAULT_AUTO_MODE_SELECTION, autoModeSelectionFrom(raw))
            assertEquals("feedback=$raw", DEFAULT_MODE_FEEDBACK, modeFeedbackFrom(raw))
        }
    }

    // ── §2 The hold duration is the *only* thing that varies per setting ─────

    /** "The selected duration applies to every gesture that requires pressing and holding." */
    @Test
    fun theSelectedDurationDrivesEveryHoldGesture() {
        HoldDuration.values().forEach { duration ->
            val required = duration.milliseconds
            // §1 option B (press and hold), §1 option C and §4 all arm on this one threshold:
            // the same function, fed the same configured number.
            assertTrue("$duration at its threshold", isVersionLongPress(required, false, required))
            assertFalse(
                "$duration short of its threshold counts for no gesture",
                isVersionLongPress(required - 1, false, required)
            )
        }
    }

    /**
     * "The hold duration controls only the initial press-and-hold threshold. The required swipe
     * distance is separate and must not depend on the selected hold duration."
     *
     * [swipeMinDistancePx] takes no [HoldDuration] at all, so the property is checked by
     * asserting the distance comes out identical for all three settings.
     */
    @Test
    fun swipeDistanceIsIndependentOfTheHoldDuration() {
        val distances = HoldDuration.values().map {
            swipeMinDistancePx(MODE_SWIPE_MIN_DISTANCE_DP, density = 2.75f)
        }.toSet()
        assertEquals("one distance, whatever the hold setting", 1, distances.size)
        assertEquals(MODE_SWIPE_MIN_DISTANCE_DP * 2.75f, distances.single(), 0.0001f)
    }

    @Test
    fun swipeDistanceConvertsFromDpAndRejectsNonsense() {
        assertEquals(128f, swipeMinDistancePx(64f, 2f), 0.0001f)
        assertEquals(0f, swipeMinDistancePx(-5f, 2f), 0.0001f)
        assertEquals(0f, swipeMinDistancePx(64f, -1f), 0.0001f)
    }

    // ── §4 Swipe geometry ────────────────────────────────────────────────────

    /** 64 dp on a 2.0 density display — the numbers below are all in pixels. */
    private val minPx = swipeMinDistancePx(MODE_SWIPE_MIN_DISTANCE_DP, density = 2f)

    @Test
    fun leftAndRightSwipesAreClassifiedBySign() {
        assertEquals(SwipeDirection.LEFT, swipeDirection(-200f, 0f, minPx))
        assertEquals(SwipeDirection.RIGHT, swipeDirection(200f, 0f, minPx))
        assertEquals(SwipeDirection.UP, swipeDirection(0f, -200f, minPx))
        assertEquals(SwipeDirection.DOWN, swipeDirection(0f, 200f, minPx))
    }

    /** "A swipe shorter than the minimum distance does not change the mode." */
    @Test
    fun aSwipeShortOfTheMinimumDistanceIsNoSwipeAtAll() {
        assertNull(swipeDirection(0f, 0f, minPx))
        assertNull(swipeDirection(-1f, 0f, minPx))
        assertNull(swipeDirection(minPx - 0.5f, 0f, minPx))
        assertNull(swipeDirection(-(minPx - 0.5f), 0f, minPx))
        // The boundary itself is inclusive: exactly the minimum does count.
        assertEquals(SwipeDirection.LEFT, swipeDirection(-minPx, 0f, minPx))
    }

    /** The dominant axis decides, so a mostly-vertical drag is never read as a mode change. */
    @Test
    fun theDominantAxisDecidesTheDirection() {
        assertEquals(SwipeDirection.UP, swipeDirection(-40f, -300f, minPx))
        assertEquals(SwipeDirection.DOWN, swipeDirection(40f, 300f, minPx))
        assertEquals(SwipeDirection.LEFT, swipeDirection(-300f, 40f, minPx))
        assertEquals(SwipeDirection.RIGHT, swipeDirection(300f, -40f, minPx))
        // A tie goes to the horizontal axis, which is the one §4 uses.
        assertEquals(SwipeDirection.RIGHT, swipeDirection(200f, 200f, minPx))
        assertEquals(SwipeDirection.LEFT, swipeDirection(-200f, -200f, minPx))
    }

    /** Even a long diagonal counts only if its *dominant* axis cleared the threshold. */
    @Test
    fun aLongDiagonalBelowTheThresholdIsStillNoSwipe() {
        val short = minPx - 1f
        assertNull(swipeDirection(short, short, minPx))
        assertNull(swipeDirection(-short, short, minPx))
    }

    // ── §4 / §5 direction and edge -> mode ───────────────────────────────────

    @Test
    fun swipeLeftSelectsSafeAndSwipeRightSelectsWarning() {
        assertEquals(ScanMode.SAFE, modeForSwipe(SwipeDirection.LEFT))
        assertEquals(ScanMode.WARNING, modeForSwipe(SwipeDirection.RIGHT))
    }

    /** Up belongs to §1's opening gesture and down means nothing; neither selects a mode. */
    @Test
    fun verticalSwipesDoNotSelectAMode() {
        assertNull(modeForSwipe(SwipeDirection.UP))
        assertNull(modeForSwipe(SwipeDirection.DOWN))
        assertNull(modeForSwipe(null))
    }

    @Test
    fun leftEdgeSelectsSafeAndRightEdgeSelectsWarning() {
        assertEquals(ScanMode.SAFE, modeForEdge(BubbleEdge.LEFT))
        assertEquals(ScanMode.WARNING, modeForEdge(BubbleEdge.RIGHT))
    }

    /**
     * §5 reads the edge back off the x that [nearestEdgeX] produced, so the two functions have
     * to agree on every screen shape — including a degenerate one narrower than the bubble.
     */
    @Test
    fun bubbleEdgeAgreesWithTheSnapTargetOnEveryScreenShape() {
        val container = 100
        val margin = 14
        listOf(1080 to 2400, 720 to 1280, 1440 to 3200, 100 to 100, 40 to 40).forEach { (w, _) ->
            val left = nearestEdgeX(0, container, w, margin)
            val right = nearestEdgeX(w, container, w, margin)
            assertEquals("$w px wide: left target", BubbleEdge.LEFT, bubbleEdgeOf(left, margin))
            if (right > left) {
                assertEquals("$w px wide: right target", BubbleEdge.RIGHT, bubbleEdgeOf(right, margin))
            }
        }
    }

    /** A negative margin must not turn the left edge into the right one. */
    @Test
    fun bubbleEdgeToleratesANegativeMargin() {
        assertEquals(BubbleEdge.LEFT, bubbleEdgeOf(0, -5))
        assertEquals(BubbleEdge.RIGHT, bubbleEdgeOf(900, -5))
    }

    // ── §1 The three opening methods ─────────────────────────────────────────
    // Every case below is an inactive bubble with no pop-up, and automatic selection off
    // unless the test says otherwise — i.e. the opening gesture in isolation.

    private fun release(
        entry: ScanModeEntry,
        autoSelection: AutoModeSelection = AutoModeSelection.DISABLED,
        holdSatisfied: Boolean = false,
        swipe: SwipeDirection? = null,
        bubbleActive: Boolean = false,
        popupVisible: Boolean = false
    ) = resolveFooterRelease(entry, autoSelection, holdSatisfied, swipe, bubbleActive, popupVisible)

    /** The default method: a tap counts, and the click listener owns the navigation. */
    @Test
    fun singleClick_aTapCountsAsATap() {
        assertEquals(FooterAction.COUNT_TAP, release(ScanModeEntry.SINGLE_CLICK))
    }

    /**
     * A hold in single-click mode is still a superset of a tap, so it counts too — the user
     * who pressed and did not move meant to click.
     */
    @Test
    fun singleClick_aHoldWithNoSwipeStillCountsAsATap() {
        assertEquals(
            FooterAction.COUNT_TAP,
            release(ScanModeEntry.SINGLE_CLICK, holdSatisfied = true)
        )
    }

    /** A swipe is not a click: counting it would open Page 2 by accident. */
    @Test
    fun singleClick_aSwipeIsNotATap() {
        SwipeDirection.values().forEach { direction ->
            assertEquals(
                "$direction",
                FooterAction.NONE,
                release(ScanModeEntry.SINGLE_CLICK, swipe = direction)
            )
        }
    }

    /** The default method never opens the page from the touch handler itself. */
    @Test
    fun singleClick_neverOpensThePageDirectly() {
        val holds = listOf(false, true)
        val swipes = listOf(null) + SwipeDirection.values().toList()
        holds.forEach { held ->
            swipes.forEach { swipe ->
                val action = release(ScanModeEntry.SINGLE_CLICK, holdSatisfied = held, swipe = swipe)
                assertFalse("$held/$swipe", action == FooterAction.OPEN_SCAN_MODE_PAGE)
            }
        }
    }

    @Test
    fun pressAndHold_opensOnlyWhenTheHoldCompleted() {
        assertEquals(
            FooterAction.OPEN_SCAN_MODE_PAGE,
            release(ScanModeEntry.PRESS_AND_HOLD, holdSatisfied = true)
        )
        assertEquals(
            "releasing early does not open the page",
            FooterAction.COUNT_TAP,
            release(ScanModeEntry.PRESS_AND_HOLD, holdSatisfied = false)
        )
        assertEquals(
            "and a tap is not a hold — but it is still a tap the counter must see",
            FooterAction.COUNT_TAP,
            release(ScanModeEntry.PRESS_AND_HOLD, holdSatisfied = false, swipe = null)
        )
    }

    /** A hold that ended in a swipe is not the plain press-and-hold gesture. */
    @Test
    fun pressAndHold_aTrailingSwipeSuppressesTheOpen() {
        SwipeDirection.values().forEach { direction ->
            assertEquals(
                "$direction",
                FooterAction.NONE,
                release(ScanModeEntry.PRESS_AND_HOLD, holdSatisfied = true, swipe = direction)
            )
        }
    }

    @Test
    fun pressHoldSwipeUp_opensOnlyOnACompletedHoldFinishedWithAnUpSwipe() {
        assertEquals(
            FooterAction.OPEN_SCAN_MODE_PAGE,
            release(ScanModeEntry.PRESS_HOLD_SWIPE_UP, holdSatisfied = true, swipe = SwipeDirection.UP)
        )
    }

    @Test
    fun pressHoldSwipeUp_everyIncompleteVariantDoesNothing() {
        // No swipe at all: the gesture was never finished.
        assertEquals(
            FooterAction.NONE,
            release(ScanModeEntry.PRESS_HOLD_SWIPE_UP, holdSatisfied = true, swipe = null)
        )
        // Hold released early, whatever the swipe. The swipe makes it not-a-tap, so it is
        // ignored rather than counted; a *clean* early release is counted (see below).
        SwipeDirection.values().forEach { direction ->
            assertEquals(
                "early release + $direction",
                FooterAction.NONE,
                release(ScanModeEntry.PRESS_HOLD_SWIPE_UP, holdSatisfied = false, swipe = direction)
            )
        }
        // Completed hold, but the swipe went somewhere other than up.
        listOf(SwipeDirection.DOWN, SwipeDirection.LEFT, SwipeDirection.RIGHT).forEach { direction ->
            assertEquals(
                "hold + $direction",
                FooterAction.NONE,
                release(ScanModeEntry.PRESS_HOLD_SWIPE_UP, holdSatisfied = true, swipe = direction)
            )
        }
    }

    /**
     * Regression guard for the developer-page lockout.
     *
     * The hidden page is the *only* place §1 can be changed, so a triple tap has to reach it
     * under every §1 method. Feeding the counter only for [ScanModeEntry.SINGLE_CLICK] meant
     * that choosing either hold-based method silently starved it: three taps produced three
     * [FooterAction.NONE]s, the count never moved off zero, and the page — along with the very
     * setting that caused the problem — became permanently unreachable.
     */
    @Test
    fun aCleanTapIsCountedUnderEveryOpeningMethod() {
        ScanModeEntry.values().forEach { entry ->
            assertEquals(
                "$entry must still count a plain tap",
                FooterAction.COUNT_TAP,
                release(entry, holdSatisfied = false, swipe = null)
            )
        }
    }

    /**
     * The other half of the same rule: counting is for *taps*. A drag off the footer is not one,
     * under any method, or a stray swipe would advance a burst the user never meant to start.
     */
    @Test
    fun aSwipeIsNeverCountedAsATapUnderAnyOpeningMethod() {
        ScanModeEntry.values().forEach { entry ->
            SwipeDirection.values().forEach { direction ->
                assertNotEquals(
                    "$entry/$direction",
                    FooterAction.COUNT_TAP,
                    release(entry, holdSatisfied = false, swipe = direction)
                )
            }
        }
    }

    // ── §1 Whether the hold may fire while the finger is still down ──────────

    @Test
    fun aPlainPressAndHoldMayOpenImmediately() {
        assertTrue(
            holdCompletesImmediately(ScanModeEntry.PRESS_AND_HOLD, AutoModeSelection.DISABLED)
        )
        assertTrue(
            holdCompletesImmediately(ScanModeEntry.PRESS_AND_HOLD, AutoModeSelection.BUBBLE_POSITION)
        )
    }

    /**
     * With a footer swipe armed the hold must stay undecided until the finger lifts, or the
     * page would open before the swipe that was meant to override it ever happened.
     */
    @Test
    fun anArmedFooterSwipeDefersTheHoldDecision() {
        assertFalse(
            holdCompletesImmediately(
                ScanModeEntry.PRESS_AND_HOLD,
                AutoModeSelection.SWIPE_LEFT_RIGHT
            )
        )
    }

    @Test
    fun theOtherOpeningMethodsNeverOpenOnTheHoldAlone() {
        AutoModeSelection.values().forEach { auto ->
            assertFalse("single click + $auto",
                holdCompletesImmediately(ScanModeEntry.SINGLE_CLICK, auto))
            assertFalse("swipe up + $auto",
                holdCompletesImmediately(ScanModeEntry.PRESS_HOLD_SWIPE_UP, auto))
        }
    }

    // ── §4 Swipe-based mode selection ────────────────────────────────────────

    @Test
    fun swipeSelection_leftIsSafeAndRightIsWarning() {
        assertEquals(
            FooterAction.SELECT_MODE_SAFE,
            release(
                ScanModeEntry.SINGLE_CLICK,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = SwipeDirection.LEFT
            )
        )
        assertEquals(
            FooterAction.SELECT_MODE_WARNING,
            release(
                ScanModeEntry.PRESS_AND_HOLD,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = SwipeDirection.RIGHT
            )
        )
    }

    /** "Releasing before the configured hold duration does not trigger the action." */
    @Test
    fun swipeSelection_needsTheFullHoldFirst() {
        listOf(SwipeDirection.LEFT, SwipeDirection.RIGHT).forEach { direction ->
            assertEquals(
                "$direction without the hold",
                FooterAction.NONE,
                release(
                    ScanModeEntry.PRESS_AND_HOLD,
                    AutoModeSelection.SWIPE_LEFT_RIGHT,
                    holdSatisfied = false,
                    swipe = direction
                )
            )
        }
    }

    /** §4 outranks §1: the same gesture would otherwise have opened the page. */
    @Test
    fun swipeSelection_outranksTheOpeningMethod() {
        ScanModeEntry.values().forEach { entry ->
            val action = release(
                entry,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = SwipeDirection.LEFT
            )
            assertEquals("$entry", FooterAction.SELECT_MODE_SAFE, action)
        }
    }

    /** Up is §1's swipe, so it must not be swallowed as a mode change. */
    @Test
    fun swipeSelection_leavesTheUpSwipeToTheOpeningMethod() {
        assertEquals(
            FooterAction.OPEN_SCAN_MODE_PAGE,
            release(
                ScanModeEntry.PRESS_HOLD_SWIPE_UP,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = SwipeDirection.UP
            )
        )
        assertEquals(
            "and a down swipe means nothing at all",
            FooterAction.NONE,
            release(
                ScanModeEntry.PRESS_AND_HOLD,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = SwipeDirection.DOWN
            )
        )
    }

    /** "The change applies only when the bubble is inactive." */
    @Test
    fun swipeSelection_isRefusedWhileTheBubbleIsActive() {
        listOf(SwipeDirection.LEFT, SwipeDirection.RIGHT).forEach { direction ->
            assertEquals(
                "$direction",
                FooterAction.NONE,
                release(
                    ScanModeEntry.PRESS_AND_HOLD,
                    AutoModeSelection.SWIPE_LEFT_RIGHT,
                    holdSatisfied = true,
                    swipe = direction,
                    bubbleActive = true
                )
            )
        }
        assertTrue(canSelectModeNow(bubbleActive = false))
        assertFalse(canSelectModeNow(bubbleActive = true))
    }

    /** With the method disabled, the very same finger movement must do nothing. */
    @Test
    fun swipeSelection_doesNothingWhenTheMethodIsNotSelected() {
        listOf(AutoModeSelection.DISABLED, AutoModeSelection.BUBBLE_POSITION).forEach { auto ->
            assertEquals(
                "$auto",
                FooterAction.NONE,
                release(
                    ScanModeEntry.PRESS_AND_HOLD,
                    auto,
                    holdSatisfied = true,
                    swipe = SwipeDirection.LEFT
                )
            )
        }
    }

    // ── §8 A visible In-App Pop-up blocks every mode-selection gesture ───────

    @Test
    fun aVisiblePopupBlocksEverything() {
        assertFalse(canSelectModeWithPopup(popupVisible = true))
        assertTrue(canSelectModeWithPopup(popupVisible = false))

        ScanModeEntry.values().forEach { entry ->
            AutoModeSelection.values().forEach { auto ->
                listOf(null, SwipeDirection.LEFT, SwipeDirection.RIGHT, SwipeDirection.UP)
                    .forEach { swipe ->
                        assertEquals(
                            "$entry/$auto/$swipe",
                            FooterAction.NONE,
                            release(
                                entry,
                                auto,
                                holdSatisfied = true,
                                swipe = swipe,
                                popupVisible = true
                            )
                        )
                    }
            }
        }
    }

    // ── §6 Session mode rules ────────────────────────────────────────────────

    /** "When the bubble becomes active, the current scan mode is copied into the session." */
    @Test
    fun aLiveSessionDisplaysItsOwnCopiedMode() {
        ScanMode.values().forEach { session ->
            ScanMode.values().forEach { selected ->
                assertEquals(
                    "session=$session selected=$selected",
                    session,
                    displayedScanMode(sessionActive = true, session, selected)
                )
            }
        }
    }

    /** Once the bubble is inactive the page goes back to showing the selected mode. */
    @Test
    fun anInactiveBubbleDisplaysTheSelectedMode() {
        ScanMode.values().forEach { selected ->
            assertEquals(
                selected,
                displayedScanMode(sessionActive = false, sessionMode = null, selected)
            )
            // A stale session value must not leak through once the session has ended.
            val other = if (selected == ScanMode.SAFE) ScanMode.WARNING else ScanMode.SAFE
            assertEquals(
                "a stale session mode is ignored",
                selected,
                displayedScanMode(sessionActive = false, sessionMode = other, selected)
            )
        }
    }

    /** An active session with no recorded mode falls back rather than showing nothing. */
    @Test
    fun anActiveSessionWithNoRecordedModeFallsBackToTheSelection() {
        ScanMode.values().forEach { selected ->
            assertEquals(selected, displayedScanMode(true, null, selected))
        }
    }

    /** "The session mode remains unchanged until the bubble becomes inactive." */
    @Test
    fun theModeIsLockedForExactlyAsLongAsTheSessionRuns() {
        assertTrue(isScanModeLocked(sessionActive = true))
        assertFalse(isScanModeLocked(sessionActive = false))
    }

    /**
     * "A new mode applies only to the next detection session." The running session keeps what
     * it copied, whatever the user selects afterwards — including selecting twice in a row.
     */
    @Test
    fun aNewSelectionNeverReachesARunningSession() {
        ScanMode.values().forEach { running ->
            ScanMode.values().forEach { newlySelected ->
                assertEquals(
                    "running=$running new=$newlySelected",
                    running,
                    sessionModeAfterSelectionChange(running, newlySelected)
                )
            }
        }
        // …and a session that has not started yet simply has nothing to protect.
        assertNull(sessionModeAfterSelectionChange(null, ScanMode.WARNING))
    }

    /**
     * "Moving the bubble while it is active does not change the current session mode": §5's
     * edge selection is gated on the same [canSelectModeNow] that §4 uses, so an active bubble
     * refuses it too. The edge->mode mapping itself stays well defined either way.
     */
    @Test
    fun edgeSelectionIsRefusedWhileASessionRuns() {
        assertFalse(canSelectModeNow(bubbleActive = true))
        BubbleEdge.values().forEach { edge ->
            assertEquals(
                if (edge == BubbleEdge.LEFT) ScanMode.SAFE else ScanMode.WARNING,
                modeForEdge(edge)
            )
        }
    }


    // ── §7 Mode-change feedback ──────────────────────────────────────────────

    @Test
    fun feedbackIsOnlyOwedForAnActualChange() {
        assertTrue(isModeChange(ScanMode.SAFE, ScanMode.WARNING))
        assertTrue(isModeChange(ScanMode.WARNING, ScanMode.SAFE))
        assertFalse(isModeChange(ScanMode.SAFE, ScanMode.SAFE))
        assertFalse(isModeChange(ScanMode.WARNING, ScanMode.WARNING))
    }

    /** A real change passes the configured feedback straight through. */
    @Test
    fun aRealChangeGetsTheConfiguredFeedback() {
        ModeFeedback.values().forEach { setting ->
            assertEquals(
                "$setting",
                setting,
                feedbackForModeChange(ScanMode.SAFE, ScanMode.WARNING, setting)
            )
        }
    }

    /**
     * Re-selecting the mode already in force is silent, whatever the setting. This is what
     * keeps §5's "Moving the bubble vertically along the same edge does not change the mode"
     * from firing a toast on every release.
     */
    @Test
    fun reSelectingTheSameModeIsAlwaysSilent() {
        ModeFeedback.values().forEach { setting ->
            ScanMode.values().forEach { mode ->
                assertEquals(
                    "$setting/$mode",
                    ModeFeedback.DISABLED,
                    feedbackForModeChange(mode, mode, setting)
                )
            }
        }
    }

    @Test
    fun theDefaultSettingProducesNoFeedbackAtAll() {
        assertFalse(feedbackUsesToast(ModeFeedback.DISABLED))
        assertFalse(feedbackUsesVibration(ModeFeedback.DISABLED))
    }

    /** Both non-disabled options toast; only the third one vibrates as well. */
    @Test
    fun toastOptionsAreDistinguishedFromTheVibrationOption() {
        assertTrue(feedbackUsesToast(ModeFeedback.TOAST))
        assertTrue(feedbackUsesToast(ModeFeedback.TOAST_AND_VIBRATION))
        assertFalse("plain toast must not vibrate", feedbackUsesVibration(ModeFeedback.TOAST))
        assertTrue(feedbackUsesVibration(ModeFeedback.TOAST_AND_VIBRATION))
    }

    /**
     * "Feedback is not shown for an invalid or incomplete gesture."
     *
     * An invalid gesture is rejected by [resolveFooterRelease] and so never reaches
     * [feedbackForModeChange] at all. What "invalid" means here is precise: a too-short swipe
     * can never *select a mode*, and an early release can never do *anything*.
     */
    @Test
    fun anInvalidGestureNeverSelectsAMode() {
        // A swipe short of the minimum is classified as no swipe at all.
        val shortSwipe = swipeDirection(-10f, 0f, minPx)
        assertNull(shortSwipe)
        assertNull(modeForSwipe(shortSwipe))

        ScanModeEntry.values().forEach { entry ->
            val action = release(
                entry,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = shortSwipe
            )
            assertTrue(
                "$entry must not select a mode from a too-short swipe, got $action",
                action != FooterAction.SELECT_MODE_SAFE && action != FooterAction.SELECT_MODE_WARNING
            )
        }

        // Released before the configured duration: nothing happens, for any method or swipe.
        listOf(SwipeDirection.LEFT, SwipeDirection.RIGHT).forEach { direction ->
            ScanModeEntry.values().forEach { entry ->
                assertEquals(
                    "$entry released early + $direction",
                    FooterAction.NONE,
                    release(
                        entry,
                        AutoModeSelection.SWIPE_LEFT_RIGHT,
                        holdSatisfied = false,
                        swipe = direction
                    )
                )
            }
        }
    }

    /**
     * The deliberate counterpart to the test above: a too-short swipe is *no swipe*, so §1's
     * completed press-and-hold still opens the page. The spec only says such a swipe "does not
     * change the mode" — it does not cancel the opening gesture the user already completed.
     */
    @Test
    fun aTooShortSwipeStillHonoursACompletedOpeningHold() {
        assertEquals(
            FooterAction.OPEN_SCAN_MODE_PAGE,
            release(
                ScanModeEntry.PRESS_AND_HOLD,
                AutoModeSelection.SWIPE_LEFT_RIGHT,
                holdSatisfied = true,
                swipe = swipeDirection(-10f, 0f, minPx)
            )
        )
    }


    // ── §8 "Disabling the bubble does not reset the selected mode" ───────────

    /**
     * The mode lives in its own preference key and none of the interaction settings carry it,
     * so tearing the bubble down — or resetting every developer option — cannot move it.
     *
     * This also pins "Changing the hold duration does not change the selected scan mode": the
     * bundle has no mode field to change, so a hold-duration edit leaves every other value
     * intact by construction.
     */
    @Test
    fun theInteractionSettingsNeverCarryAMode() {
        val before = ScanModeInteractionSettings(
            entry = ScanModeEntry.PRESS_HOLD_SWIPE_UP,
            holdDuration = HoldDuration.HALF_SECOND,
            autoSelection = AutoModeSelection.BUBBLE_POSITION,
            feedback = ModeFeedback.TOAST_AND_VIBRATION
        )
        val after = before.copy(holdDuration = HoldDuration.TWO_SECONDS)
        assertEquals(before.entry, after.entry)
        assertEquals(before.autoSelection, after.autoSelection)
        assertEquals(before.feedback, after.feedback)
        assertEquals(HoldDuration.TWO_SECONDS, after.holdDuration)

        // Restoring the defaults likewise cannot produce or clear a mode.
        assertEquals(
            DEFAULT_SCAN_MODE_INTERACTION,
            before.copy(
                entry = DEFAULT_SCAN_MODE_ENTRY,
                holdDuration = DEFAULT_HOLD_DURATION,
                autoSelection = DEFAULT_AUTO_MODE_SELECTION,
                feedback = DEFAULT_MODE_FEEDBACK
            )
        )
    }

    /**
     * Every §1 method keeps working with every §3 method selected — the two settings are
     * orthogonal, so no combination may throw or produce an unexpected action.
     */
    @Test
    fun everySettingCombinationResolvesToAKnownAction() {
        val swipes = listOf(null) + SwipeDirection.values().toList()
        ScanModeEntry.values().forEach { entry ->
            AutoModeSelection.values().forEach { auto ->
                listOf(false, true).forEach { held ->
                    swipes.forEach { swipe ->
                        listOf(false, true).forEach { active ->
                            val action = release(entry, auto, held, swipe, active)
                            assertTrue(
                                "$entry/$auto/held=$held/$swipe/active=$active -> $action",
                                action in FooterAction.values()
                            )
                        }
                    }
                }
            }
        }
    }
}

