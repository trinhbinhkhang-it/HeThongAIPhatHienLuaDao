package com.voiceshield.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression tests for Component 6 (enable_floating_bubble.md Lifecycle
 * Rules).
 *
 * These pin the bug where the bubble vanished on unlock while the Home badge kept
 * reading "Đang bảo vệ". Two defects compounded: the lock gate was latched on
 * broadcast *edges* (ACTION_SCREEN_OFF hides, ACTION_USER_PRESENT shows) and
 * ACTION_USER_PRESENT is never sent when the device has no secure lock screen; and
 * the liveness flag counted an attached-but-GONE window as rendered.
 *
 * Every rule here is a top-level Android-free function, so this runs on the local
 * JVM without Robolectric or an emulator.
 */
class LockVisibilityUnitTest {

    // ── Lifecycle Rules, verbatim ──

    /** "When the device is locked, the bubble is hidden." */
    @Test
    fun lockedDevice_hidesTheBubble() {
        assertFalse(bubbleShouldBeVisible(protectionActive = true, keyguardShowing = true))
    }

    /** "After the device is unlocked, the bubble becomes visible again..." */
    @Test
    fun unlockedDevice_restoresTheBubble_whenProtectionRemainsEnabled() {
        assertTrue(bubbleShouldBeVisible(protectionActive = true, keyguardShowing = false))
    }

    /** "...if protection remains enabled." */
    @Test
    fun unlockedDevice_keepsTheBubbleHidden_whenProtectionWasDisabled() {
        assertFalse(bubbleShouldBeVisible(protectionActive = false, keyguardShowing = false))
        assertFalse(bubbleShouldBeVisible(protectionActive = false, keyguardShowing = true))
    }

    /**
     * The reported failure, expressed as the state transition it should have been.
     *
     * Because the gate is a pure function of the *current* keyguard state rather than
     * of broadcast edges, re-deriving it recovers the bubble with no unlock event at
     * all — which is what the watchdog and MainActivity.onResume both rely on.
     */
    @Test
    fun reDerivingFromKeyguardState_recoversWithoutTheUnlockBroadcast() {
        assertFalse(
            "locked => hidden",
            bubbleShouldBeVisible(true, isDeviceLockedForBubble(false, true, true))
        )
        assertTrue(
            "awake and unlocked => shown again, no broadcast needed",
            bubbleShouldBeVisible(true, isDeviceLockedForBubble(true, false, false))
        )
    }

    // ── isBubbleOnScreen: an attached window is not necessarily a drawn one ──

    @Test
    fun hiddenForLock_doesNotCountAsOnScreen() {
        assertFalse(isBubbleOnScreen(windowAttached = true, hiddenByLock = true))
    }

    @Test
    fun attachedAndDrawn_countsAsOnScreen() {
        assertTrue(isBubbleOnScreen(windowAttached = true, hiddenByLock = false))
    }

    @Test
    fun detachedWindow_neverCountsAsOnScreen() {
        assertFalse(isBubbleOnScreen(windowAttached = false, hiddenByLock = false))
        assertFalse(isBubbleOnScreen(windowAttached = false, hiddenByLock = true))
    }

    // ── The badge postcondition the bug violated ──

    /** "The app must not display 'Đang bảo vệ' unless the bubble has been rendered." */
    @Test
    fun homeBadge_neverClaimsProtection_overALockHiddenBubble() {
        val persistedActive = true
        val onScreen = isBubbleOnScreen(windowAttached = true, hiddenByLock = true)
        assertFalse(isProtectionEffective(persistedActive, onScreen))
    }

    /** "The interface shouldn't become 'Đang bảo vệ' if the bubble is not displayed." */
    @Test
    fun homeBadge_neverClaimsProtection_overADetachedBubble() {
        val onScreen = isBubbleOnScreen(windowAttached = false, hiddenByLock = false)
        assertFalse(isProtectionEffective(persistedActive = true, onScreen))
    }

    @Test
    fun homeBadge_claimsProtection_onlyOnceTheBubbleIsDrawn() {
        val onScreen = isBubbleOnScreen(windowAttached = true, hiddenByLock = false)
        assertTrue(isProtectionEffective(persistedActive = true, bubbleVisible = onScreen))
    }

    // ── isDeviceLockedForBubble: each signal alone must hide the bubble ──

    @Test
    fun nonInteractiveDisplay_aloneMeansLocked() {
        assertTrue(isDeviceLockedForBubble(false, keyguardLocked = false, deviceLocked = false))
    }

    @Test
    fun keyguardShowing_aloneMeansLocked() {
        assertTrue(isDeviceLockedForBubble(true, keyguardLocked = true, deviceLocked = false))
    }

    @Test
    fun secureDeviceLock_aloneMeansLocked() {
        assertTrue(isDeviceLockedForBubble(true, keyguardLocked = false, deviceLocked = true))
    }

    /**
     * The device class that broke the edge-triggered version: no secure lock screen,
     * hence no keyguard and no ACTION_USER_PRESENT ever. The display state has to
     * carry the "locked" half of the rule on its own.
     */
    @Test
    fun awakeWithNoKeyguard_meansUnlocked() {
        assertFalse(isDeviceLockedForBubble(true, keyguardLocked = false, deviceLocked = false))
    }
}
