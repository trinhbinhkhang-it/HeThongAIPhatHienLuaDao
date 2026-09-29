package com.voiceshield.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression tests for the bubble's *liveness* reporting — the signal the Home
 * status card is allowed to trust.
 *
 * Two reported defects live here:
 *
 *  1. "The app doesn't automatically detect the bubble is visible or not, which sometimes
 *     leads to a confused state where the interface says 'Đang bảo vệ - Tắt ứng dụng'
 *     while there is no bubble on the screen." The liveness latch was only ever cleared in
 *     `onDestroy()`, so any teardown that skipped it — the system reclaiming the overlay, a
 *     swallowed `removeView()`, an exception half-way through teardown — left the latch
 *     true forever and the card kept lying. [isOverlayWindowAttached] makes the live View
 *     the authority instead.
 *
 *  2. The same latch, read too eagerly, would be just as wrong in the other direction:
 *     `WindowManager.addView()` returns *before* the first traversal, so
 *     `View.isAttachedToWindow` is still false on a perfectly healthy window.
 *     [canVerifyWindowAttachment] holds verification off until the attach settles, so a
 *     bubble that was just shown is not torn down by its own health check.
 *
 * Both rules are top-level and Android-free, in the same convention as
 * [isProtectionEffective] (see [LockVisibilityUnitTest]).
 */
class BubbleVisibilityUnitTest {

    // ── isOverlayWindowAttached: the View is the authority ────────────────────

    @Test
    fun aRealWindow_countsAsAttached() {
        assertTrue(isOverlayWindowAttached(
            viewInitialized = true, attachedToWindow = true, hasWindowToken = true
        ))
    }

    /** Every single missing signal on its own is enough to say "not attached". */
    @Test
    fun anyMissingSignal_countsAsDetached() {
        assertFalse(isOverlayWindowAttached(false, true, true))
        assertFalse(isOverlayWindowAttached(true, false, true))
        assertFalse(isOverlayWindowAttached(true, true, false))
        assertFalse(isOverlayWindowAttached(false, false, false))
    }

    /**
     * A half-removed window — detached but still holding a stale token, or vice versa —
     * must not be believed. This is the shape a swallowed `removeView()` leaves behind.
     */
    @Test
    fun halfRemovedWindow_countsAsDetached() {
        assertFalse(isOverlayWindowAttached(true, false, true))
        assertFalse(isOverlayWindowAttached(true, true, false))
    }

    // ── canVerifyWindowAttachment: do not read the View too early ─────────────

    @Test
    fun verificationNeedsBothTheSettleWindowAndAnInitialisedView() {
        assertTrue(canVerifyWindowAttachment(attachSettled = true, viewInitialized = true))
        assertFalse(canVerifyWindowAttachment(attachSettled = false, viewInitialized = true))
        assertFalse(canVerifyWindowAttachment(attachSettled = true, viewInitialized = false))
        assertFalse(canVerifyWindowAttachment(attachSettled = false, viewInitialized = false))
    }

    // ── End to end: what the Home card is allowed to print ────────────────────

    /**
     * Defect 1, verbatim: the persisted switch says active and the latch still says a
     * bubble exists, but the window is gone. The card must read "Chưa kích hoạt".
     */
    @Test
    fun staleLatch_overAGoneWindow_noLongerClaimsProtection() {
        assertFalse(badgeShowsProtecting(
            persistedActive = true,
            latchSet = true,
            hiddenByLock = false,
            viewInitialized = true,
            attachedToWindow = false,
            hasWindowToken = false,
            attachSettled = true
        ))
    }

    /** The healthy case must keep working — the fix may not make the card timid. */
    @Test
    fun healthyBubble_claimsProtection() {
        assertTrue(badgeShowsProtecting(
            persistedActive = true,
            latchSet = true,
            hiddenByLock = false,
            viewInitialized = true,
            attachedToWindow = true,
            hasWindowToken = true,
            attachSettled = true
        ))
    }

    /**
     * Defect 2: a bubble shown a moment ago has not finished its first traversal yet, so
     * the View still reports "not attached". Trusting that would tear down a session the
     * user just started — and flip the card straight back to "Chưa kích hoạt".
     */
    @Test
    fun justShownBubble_isNotTornDownByItsOwnHealthCheck() {
        assertTrue(badgeShowsProtecting(
            persistedActive = true,
            latchSet = true,
            hiddenByLock = false,
            viewInitialized = true,
            attachedToWindow = false,
            hasWindowToken = false,
            attachSettled = false
        ))
    }

    /**
     * The X-target dismiss: `stopProtection()` clears the persisted switch and
     * `onDestroy()` clears the latch, so the card must follow even when the broadcast that
     * would have told it was dropped because the activity was stopped in another app.
     */
    @Test
    fun bubbleDismissedThroughTheXTarget_neverClaimsProtection() {
        assertFalse(badgeShowsProtecting(
            persistedActive = false,
            latchSet = false,
            hiddenByLock = false,
            viewInitialized = true,
            attachedToWindow = false,
            hasWindowToken = false,
            attachSettled = true
        ))
        // The teardown gap in between: switch already off, window not yet removed.
        assertFalse(badgeShowsProtecting(
            persistedActive = false,
            latchSet = true,
            hiddenByLock = false,
            viewInitialized = true,
            attachedToWindow = true,
            hasWindowToken = true,
            attachSettled = true
        ))
    }

    /** enable_floating_bubble.md Lifecycle Rules: a lock-hidden bubble is not on screen. */
    @Test
    fun lockHiddenBubble_neverClaimsProtection() {
        assertFalse(badgeShowsProtecting(
            persistedActive = true,
            latchSet = true,
            hiddenByLock = true,
            viewInitialized = true,
            attachedToWindow = true,
            hasWindowToken = true,
            attachSettled = true
        ))
    }

    /**
     * Force-stop / reboot: the process-local latch resets with the process while the
     * persisted switch survives — exactly the stale-flag case the card self-heals.
     */
    @Test
    fun processDeath_resetsTheLatch_andTheCardFollows() {
        assertFalse(badgeShowsProtecting(
            persistedActive = true,
            latchSet = false,
            hiddenByLock = false,
            viewInitialized = false,
            attachedToWindow = false,
            hasWindowToken = false,
            attachSettled = false
        ))
    }

    /**
     * Models one MainActivity.syncProtectionState() pass: correct the latch against the
     * live View when that is allowed, then AND the persisted switch with what is really on
     * screen. Mirrors the production composition of [canVerifyWindowAttachment],
     * [isOverlayWindowAttached], [isBubbleOnScreen] and [isProtectionEffective].
     */
    private fun badgeShowsProtecting(
        persistedActive: Boolean,
        latchSet: Boolean,
        hiddenByLock: Boolean,
        viewInitialized: Boolean,
        attachedToWindow: Boolean,
        hasWindowToken: Boolean,
        attachSettled: Boolean
    ): Boolean {
        val attached = if (canVerifyWindowAttachment(attachSettled, viewInitialized)) {
            isOverlayWindowAttached(viewInitialized, attachedToWindow, hasWindowToken)
        } else {
            latchSet
        }
        return isProtectionEffective(persistedActive, isBubbleOnScreen(attached, hiddenByLock))
    }
}
