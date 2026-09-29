package com.voiceshield.ai

import kotlin.math.abs

/**
 * developer_setting_page.md — the developer settings that decide *how* the scan mode is
 * reached, *how* it may be selected automatically, and *whether* the app says anything
 * when it changes.
 *
 * Every rule in this file is a top-level, Android-free function in the same convention as
 * [scanDelayMs], [shouldArmScanTimer] and [isTripleClick], so the whole interaction model is
 * unit-testable on the local JVM without Robolectric. Only the persistence lives in
 * [ProtectionState] and only the touch plumbing lives in [MainActivity] /
 * [FloatingBubbleService].
 */

// ── §1 Opening "Chế độ quét" ─────────────────────────────────────────────────

/**
 * How the "Chế độ quét" page (Page 2, [SettingsActivity]) is opened from the
 * "Version 1.0.1" footer on Page 1.
 *
 * A single enum — not three booleans — is what makes the spec's "Only one option can be
 * selected" true *by construction*: there is no representable state in which two opening
 * methods are on at once.
 */
enum class ScanModeEntry {
    /** One tap on the footer. The spec default. */
    SINGLE_CLICK,

    /** Press and hold for [HoldDuration]. */
    PRESS_AND_HOLD,

    /** Press and hold for [HoldDuration], then swipe up. */
    PRESS_HOLD_SWIPE_UP
}

/** Spec default for §1: "Single click (default)". */
val DEFAULT_SCAN_MODE_ENTRY = ScanModeEntry.SINGLE_CLICK

// ── §2 Hold duration ─────────────────────────────────────────────────────────

/**
 * The press-and-hold threshold shared by *every* gesture this spec defines: the two
 * hold-based opening methods (§1) and the hold that arms a swipe-based mode change (§4).
 *
 * Carrying the milliseconds on the enum is deliberate — it makes "the selected duration
 * applies consistently to all hold gestures" a property of the type rather than of each
 * call site, so no gesture can accidentally hard-code its own number.
 */
enum class HoldDuration(val milliseconds: Long) {
    HALF_SECOND(500L),
    ONE_SECOND(1_000L),
    TWO_SECONDS(2_000L)
}

/** Spec default for §2: "1 second (default)". */
val DEFAULT_HOLD_DURATION = HoldDuration.ONE_SECOND

// ── §3 Automatic mode selection ──────────────────────────────────────────────

/**
 * The *one* automatic way the scan mode may be selected before a detection session.
 *
 * Again a single enum rather than a pair of switches: the spec's "Only one automatic
 * mode-selection method can be active at a time" (and the acceptance criterion "At most
 * one … can be selected") cannot be violated, because two methods cannot be stored.
 */
enum class AutoModeSelection {
    /** No automatic selection. The spec default. */
    DISABLED,

    /** "Đổi chế độ bằng việc vuốt sang trái hoặc phải" — §4. */
    SWIPE_LEFT_RIGHT,

    /** "Đổi chế độ dựa trên vị trí bubble" — §5. */
    BUBBLE_POSITION
}

/** Spec default for §3: "Disabled (default)". */
val DEFAULT_AUTO_MODE_SELECTION = AutoModeSelection.DISABLED

// ── §7 Mode-change feedback ──────────────────────────────────────────────────

/** What the app does after the scan mode has *actually* changed. */
enum class ModeFeedback {
    /** Say nothing. The spec default. */
    DISABLED,
    TOAST,
    TOAST_AND_VIBRATION
}

/** Spec default for §7: "Disabled (default)". */
val DEFAULT_MODE_FEEDBACK = ModeFeedback.DISABLED

/**
 * The four §1–§3/§7 settings as one value.
 *
 * Note what is *not* in here: the selected [ScanMode]. That absence is the structural
 * guarantee behind the acceptance criterion "Changing the hold duration does not change the
 * selected scan mode" — the interaction settings and the mode are stored separately, so no
 * write to one can touch the other.
 */
data class ScanModeInteractionSettings(
    val entry: ScanModeEntry = DEFAULT_SCAN_MODE_ENTRY,
    val holdDuration: HoldDuration = DEFAULT_HOLD_DURATION,
    val autoSelection: AutoModeSelection = DEFAULT_AUTO_MODE_SELECTION,
    val feedback: ModeFeedback = DEFAULT_MODE_FEEDBACK
)

/** Every spec default in one place; "Khôi phục mặc định" writes exactly this. */
val DEFAULT_SCAN_MODE_INTERACTION = ScanModeInteractionSettings()

// ── Defensive parsing ────────────────────────────────────────────────────────
// SharedPreferences is a flat file anyone with adb can edit, and an enum name can be
// renamed by a refactor. Every read therefore falls back to the spec default instead of
// throwing — the same posture [sanitizeInterval] takes towards the roll windows.

/** Parses a persisted §1 selection; unknown or absent values fall back to the default. */
fun scanModeEntryFrom(raw: String?): ScanModeEntry =
    ScanModeEntry.values().firstOrNull { it.name == raw } ?: DEFAULT_SCAN_MODE_ENTRY

/** Parses a persisted §2 selection; unknown or absent values fall back to the default. */
fun holdDurationFrom(raw: String?): HoldDuration =
    HoldDuration.values().firstOrNull { it.name == raw } ?: DEFAULT_HOLD_DURATION

/** Parses a persisted §3 selection; unknown or absent values fall back to the default. */
fun autoModeSelectionFrom(raw: String?): AutoModeSelection =
    AutoModeSelection.values().firstOrNull { it.name == raw } ?: DEFAULT_AUTO_MODE_SELECTION

/** Parses a persisted §7 selection; unknown or absent values fall back to the default. */
fun modeFeedbackFrom(raw: String?): ModeFeedback =
    ModeFeedback.values().firstOrNull { it.name == raw } ?: DEFAULT_MODE_FEEDBACK

// ── §2 Swipe distance is independent of the hold duration ────────────────────

/**
 * Minimum travel, in dp, before a footer swipe counts as a mode selection (§4: "A swipe
 * shorter than the minimum distance does not change the mode").
 *
 * Deliberately a stand-alone constant with no [HoldDuration] anywhere near it: the spec
 * requires that "The required swipe distance is separate and must not depend on the
 * selected hold duration."
 */
const val MODE_SWIPE_MIN_DISTANCE_DP = 64f

/** Converts [MODE_SWIPE_MIN_DISTANCE_DP] (or any dp) into pixels for a display density. */
fun swipeMinDistancePx(minDistanceDp: Float, density: Float): Float =
    minDistanceDp.coerceAtLeast(0f) * density.coerceAtLeast(0f)

// ── Swipe / edge geometry ────────────────────────────────────────────────────

/** The four cardinal directions a footer swipe can be classified as. */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Classifies a swipe from its total travel.
 *
 * Returns null when the *dominant* axis never reached [minDistancePx] — that is §4's "a
 * swipe shorter than the minimum distance does not change the mode". The dominant axis is
 * decided first, so a mostly-vertical drag is never misread as a left/right mode change.
 *
 * Screen coordinates grow rightwards and downwards, hence `dx < 0` is LEFT and `dy < 0` is
 * UP. A tie goes to the horizontal axis, which is the one §4 actually uses.
 */
fun swipeDirection(dxPx: Float, dyPx: Float, minDistancePx: Float): SwipeDirection? {
    val threshold = minDistancePx.coerceAtLeast(0f)
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < threshold && ady < threshold) return null
    return if (adx >= ady) {
        if (dxPx < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
    } else {
        if (dyPx < 0f) SwipeDirection.UP else SwipeDirection.DOWN
    }
}

/** Which horizontal screen edge a bubble is resting on after a snap. */
enum class BubbleEdge { LEFT, RIGHT }

/**
 * Reads the edge back off the x produced by [nearestEdgeX].
 *
 * [nearestEdgeX] only ever returns the left inset or the right inset, so comparing against
 * the left inset is enough — and a degenerate display, where the two collapse onto the same
 * value, resolves to LEFT rather than reporting an edge that does not exist.
 */
fun bubbleEdgeOf(snappedX: Int, edgeMargin: Int): BubbleEdge =
    if (snappedX <= edgeMargin.coerceAtLeast(0)) BubbleEdge.LEFT else BubbleEdge.RIGHT

/** §4: swiping left selects "Chế độ an toàn", swiping right selects "Chế độ cảnh báo". */
fun modeForSwipe(direction: SwipeDirection?): ScanMode? = when (direction) {
    SwipeDirection.LEFT -> ScanMode.SAFE
    SwipeDirection.RIGHT -> ScanMode.WARNING
    // Up is the "open the page" gesture (§1) and down means nothing at all.
    SwipeDirection.UP, SwipeDirection.DOWN, null -> null
}

/** §5: the left edge selects "Chế độ an toàn", the right edge selects "Chế độ cảnh báo". */
fun modeForEdge(edge: BubbleEdge): ScanMode = when (edge) {
    BubbleEdge.LEFT -> ScanMode.SAFE
    BubbleEdge.RIGHT -> ScanMode.WARNING
}

/**
 * The whole §5 gate in one pure function, so the edge-selection rule is as testable as §4's.
 *
 * A completed snap may only pick a mode when *all* of these hold:
 *  - "Đổi chế độ dựa trên vị trí bubble" is the selected automatic method (§3);
 *  - no In-App Pop-up is visible (§8);
 *  - the bubble is inactive (§5's last bullet, via [canSelectModeNow]);
 *  - the edge's mode differs from the one already selected, which is what makes "Moving the
 *    bubble vertically along the same edge does not change the mode" true and stops a drag up
 *    and down one edge from re-announcing a mode that never moved.
 *
 * Note what is *not* in here: whether the snap has finished. That is a timing property of the
 * animation, and FloatingBubbleService enforces it by only calling this from the animator's
 * end callback.
 */
fun shouldApplyEdgeMode(
    autoSelection: AutoModeSelection,
    popupVisible: Boolean,
    bubbleActive: Boolean,
    currentMode: ScanMode,
    edge: BubbleEdge
): Boolean =
    autoSelection == AutoModeSelection.BUBBLE_POSITION &&
        canSelectModeWithPopup(popupVisible) &&
        canSelectModeNow(bubbleActive) &&
        isModeChange(currentMode, modeForEdge(edge))

// ── §4 / §5 / §8 gates ───────────────────────────────────────────────────────

/**
 * §4 and §5 both end with "The change applies only when the bubble is inactive", and §6
 * defines *active* as "a detection session is running" — the state a double tap starts.
 *
 * An inactive bubble has no session to protect, so the selected mode is free to move.
 */
fun canSelectModeNow(bubbleActive: Boolean): Boolean = !bubbleActive

/**
 * §8: "Mode-selection gestures do not work while an In-App Pop-up is visible." The same
 * restriction disable_floating_bubble.md already puts on the bubble itself.
 */
fun canSelectModeWithPopup(popupVisible: Boolean): Boolean = !popupVisible

// ── §1 + §4: the version-footer gesture ──────────────────────────────────────

/** What a released footer gesture turned out to mean. */
enum class FooterAction {
    /** Nothing: an incomplete, invalid or blocked gesture. */
    NONE,

    /**
     * Count this release as a tap. The caller hands it to the click listener, which owns
     * both the triple tap (hidden developer page) and the deferred §1 single-click
     * navigation.
     */
    COUNT_TAP,

    /** Open the "Chế độ quét" page now. */
    OPEN_SCAN_MODE_PAGE,

    /** §4: the swipe selected "Chế độ an toàn". */
    SELECT_MODE_SAFE,

    /** §4: the swipe selected "Chế độ cảnh báo". */
    SELECT_MODE_WARNING
}

/** Maps a §4 swipe onto the [FooterAction] that applies it. */
fun selectModeAction(mode: ScanMode): FooterAction = when (mode) {
    ScanMode.SAFE -> FooterAction.SELECT_MODE_SAFE
    ScanMode.WARNING -> FooterAction.SELECT_MODE_WARNING
}

/**
 * Whether completing the hold may open the page *while the finger is still down*.
 *
 * Only plain [ScanModeEntry.PRESS_AND_HOLD] can, and only when no footer swipe is armed:
 * once §4's swipe selection is enabled, or the opening method needs a swipe-up to finish,
 * the hold has to stay undecided until the finger lifts — otherwise the page would open
 * before the swipe that was supposed to override it ever happened.
 */
fun holdCompletesImmediately(
    entry: ScanModeEntry,
    autoSelection: AutoModeSelection
): Boolean =
    entry == ScanModeEntry.PRESS_AND_HOLD &&
        autoSelection != AutoModeSelection.SWIPE_LEFT_RIGHT

/**
 * The single authority on what a footer release means, given the configured §1 opening
 * method, the configured §3 automatic selection, and what the finger actually did.
 *
 * [holdSatisfied] is the *whole* §2 rule: the finger stayed inside the touch slop for at
 * least the configured duration. [swipe] is null when the travel never reached
 * [MODE_SWIPE_MIN_DISTANCE_DP].
 *
 * Ordering matters and encodes the spec:
 *  1. §8 — a visible In-App Pop-up blocks everything.
 *  2. §4 — hold-then-swipe is the most specific gesture, so it outranks the opening method,
 *     but only while the bubble is inactive.
 *  3. §1 — otherwise the configured opening method decides.
 *
 * A swipe that is *not* a mode swipe suppresses the single-click tap rather than being
 * ignored: dragging off the footer is not a click, and treating it as one would open Page 2
 * by accident.
 */
fun resolveFooterRelease(
    entry: ScanModeEntry,
    autoSelection: AutoModeSelection,
    holdSatisfied: Boolean,
    swipe: SwipeDirection?,
    bubbleActive: Boolean,
    popupVisible: Boolean
): FooterAction {
    if (!canSelectModeWithPopup(popupVisible)) return FooterAction.NONE

    if (autoSelection == AutoModeSelection.SWIPE_LEFT_RIGHT &&
        holdSatisfied &&
        canSelectModeNow(bubbleActive)
    ) {
        modeForSwipe(swipe)?.let { return selectModeAction(it) }
    }

    return when (entry) {
        ScanModeEntry.SINGLE_CLICK ->
            if (swipe == null) FooterAction.COUNT_TAP else FooterAction.NONE

        ScanModeEntry.PRESS_AND_HOLD ->
            if (holdSatisfied && swipe == null) {
                FooterAction.OPEN_SCAN_MODE_PAGE
            } else {
                FooterAction.NONE
            }

        ScanModeEntry.PRESS_HOLD_SWIPE_UP ->
            if (holdSatisfied && swipe == SwipeDirection.UP) {
                FooterAction.OPEN_SCAN_MODE_PAGE
            } else {
                FooterAction.NONE
            }
    }
}

// ── §6 Session mode rules ────────────────────────────────────────────────────

/**
 * True while the selected mode may not be edited or applied: §6 "The session mode remains
 * unchanged until the bubble becomes inactive."
 */
fun isScanModeLocked(sessionActive: Boolean): Boolean = sessionActive

/**
 * The mode the "Chế độ quét" page must display.
 *
 * While a session runs the page shows *that* session's locked mode, per the acceptance
 * criterion "If the bubble is active, the page shows the current session mode". A session
 * that somehow has no recorded mode (a torn-down service read from a stale flag) falls back
 * to the selected mode rather than showing nothing.
 */
fun displayedScanMode(
    sessionActive: Boolean,
    sessionMode: ScanMode?,
    selectedMode: ScanMode
): ScanMode = if (sessionActive) (sessionMode ?: selectedMode) else selectedMode

/**
 * §6 "A new mode applies only to the next detection session": a running session keeps the
 * mode it was started with, whatever the user selects afterwards.
 *
 * Written as a function that *ignores* [newSelectedMode] on purpose — the signature makes the
 * rule visible at every call site and the unit test pins it, so a future edit cannot quietly
 * start leaking a new selection into a live session.
 */
@Suppress("UNUSED_PARAMETER")
fun sessionModeAfterSelectionChange(
    runningSessionMode: ScanMode?,
    newSelectedMode: ScanMode
): ScanMode? = runningSessionMode

// ── §7 Feedback ──────────────────────────────────────────────────────────────

/**
 * Whether applying [requested] on top of [current] is a *change* at all.
 *
 * This is what keeps §5's "Moving the bubble vertically along the same edge does not change
 * the mode" honest: re-snapping onto an edge the bubble already sits on requests the mode
 * that is already selected, which is not a change and therefore neither re-writes the
 * preference nor re-announces it.
 */
fun isModeChange(current: ScanMode, requested: ScanMode): Boolean = current != requested

/**
 * The feedback owed for a mode selection.
 *
 * §7: "feedback is shown only after the mode changes successfully" and "Feedback is not shown
 * for an invalid or incomplete gesture". An invalid gesture never reaches this function —
 * [resolveFooterRelease] and [canSelectModeNow] reject it first — and a request for the mode
 * that is already selected is not a change, so it is silent too. Without that second half,
 * dragging the bubble up and down one edge would fire a toast on every release.
 */
fun feedbackForModeChange(
    current: ScanMode,
    requested: ScanMode,
    setting: ModeFeedback
): ModeFeedback = if (isModeChange(current, requested)) setting else ModeFeedback.DISABLED

/** Whether [feedback] wants a toast at all (both non-disabled options do). */
fun feedbackUsesToast(feedback: ModeFeedback): Boolean = feedback != ModeFeedback.DISABLED

/** Whether [feedback] wants the extra vibration pulse. */
fun feedbackUsesVibration(feedback: ModeFeedback): Boolean =
    feedback == ModeFeedback.TOAST_AND_VIBRATION
