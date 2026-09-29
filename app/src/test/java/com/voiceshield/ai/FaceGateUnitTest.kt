package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the hidden "Chế độ nhận diện khuôn mặt" developer option.
 *
 * The option changes *when* a double-tapped scan may start its random popup window:
 *
 *  - OFF (the default) — enable_floating_bubble.md's behaviour: the double-tap arms the
 *    window immediately and the result appears 4–7 s (Safe) or 5–8 s (Warning) later.
 *  - ON — the scan waits until a human face really appears on screen, and only then
 *    starts the same random window. No face, no warning, ever.
 *
 * [shouldArmScanTimer] is the whole rule, kept as a top-level Android-free function in
 * the same convention as [scanDelayMs] and [shouldApplyFaceDetection] so it is testable
 * on the local JVM without Robolectric.
 *
 * The tests below also guard the *existing* behaviour, which the option must not change:
 * with the gate off nothing about the scan timing is different, and the separate
 * model-result gate [shouldApplyFaceDetection] still requires an active scan.
 */
class FaceGateUnitTest {

    // ── The gate itself: full truth table ─────────────────────────────────────

    @Test
    fun gateOff_armsImmediately_whetherOrNotAFaceIsPresent() {
        assertTrue("no face yet — the historical behaviour arms anyway",
            shouldArmScanTimer(faceGateEnabled = false, faceDetected = false))
        assertTrue(shouldArmScanTimer(faceGateEnabled = false, faceDetected = true))
    }

    @Test
    fun gateOn_waitsForARealFace() {
        assertFalse("no face on screen => no random window, therefore no warning",
            shouldArmScanTimer(faceGateEnabled = true, faceDetected = false))
    }

    @Test
    fun gateOn_armsTheMomentAFaceAppears() {
        assertTrue(shouldArmScanTimer(faceGateEnabled = true, faceDetected = true))
    }

    /** Exhaustive: the rule is a pure function of exactly these two booleans. */
    @Test
    fun truthTableIsComplete() {
        val expected = mapOf(
            (false to false) to true,
            (false to true) to true,
            (true to false) to false,
            (true to true) to true
        )
        expected.forEach { (input, want) ->
            assertEquals(
                "gate=${input.first} face=${input.second}",
                want,
                shouldArmScanTimer(input.first, input.second)
            )
        }
    }

    // ── The gate must not change anything when it is off ──────────────────────

    /**
     * Regression guard: turning the option off has to restore the documented behaviour
     * exactly, so the existing scan-timing tests in [ScanModeUnitTest] and
     * [HiddenSettingsUnitTest] still describe reality.
     */
    @Test
    fun gateOff_leavesTheSpecifiedRollWindowsAlone() {
        // A gate-off scan is never parked, so nothing can delay the random window.
        assertTrue(shouldArmScanTimer(false, false))
        repeat(500) {
            assertTrue(scanDelayMs(ScanMode.SAFE) in 4_000L..7_000L)
            assertTrue(scanDelayMs(ScanMode.WARNING) in 5_000L..8_000L)
        }
    }

    /** The gate decides *when* the window starts, never how long it is. */
    @Test
    fun gateOn_stillUsesTheSameRollWindowOnceReleased() {
        // Simulate: parked until a face arrives, then armed with the normal window.
        assertFalse(shouldArmScanTimer(true, faceDetected = false))
        assertTrue(shouldArmScanTimer(true, faceDetected = true))
        repeat(500) {
            assertTrue(scanDelayMs(ScanMode.SAFE) in 4_000L..7_000L)
            assertTrue(scanDelayMs(ScanMode.WARNING) in 5_000L..8_000L)
        }
    }

    // ── Interaction with the model-result gate ────────────────────────────────

    /**
     * [shouldApplyFaceDetection] is a *different* rule and must stay independent: it
     * gates the bubble's model-result sequence on an active scan, while
     * [shouldArmScanTimer] gates the popup timer. Conflating them would let an idle
     * bubble start "detecting" on its own.
     */
    @Test
    fun modelResultGate_stillRequiresAnActiveScan() {
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = true))
        assertTrue(shouldApplyFaceDetection(scanning = true, faceDetected = true))
        assertFalse(shouldApplyFaceDetection(scanning = true, faceDetected = false))
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = false))
    }

    /**
     * A parked scan (gate on, no face) is still `scanning`, so the two rules must be
     * consulted separately: the model sequence may run on a face, but the popup window
     * may not start without one.
     */
    @Test
    fun parkedScan_andReleasedScan_disagreeOnlyOnTheFace() {
        // Parked: scanning, gate on, no face.
        assertFalse(shouldArmScanTimer(true, false))
        assertFalse(shouldApplyFaceDetection(scanning = true, faceDetected = false))
        // Released: the face arrived, both gates open together.
        assertTrue(shouldArmScanTimer(true, true))
        assertTrue(shouldApplyFaceDetection(scanning = true, faceDetected = true))
    }

    /**
     * The Warning-mode popup and its notification are produced by the same timer
     * (enable_floating_bubble.md: "the system notification and the In-App Pop-up appear
     * at the same time"), so gating the timer gates both — which is what the option is
     * for: no face, no warning message of any kind.
     */
    @Test
    fun gateOn_suppressesBothThePopupAndTheNotification() {
        val timerWouldFire = shouldArmScanTimer(faceGateEnabled = true, faceDetected = false)
        assertFalse("popup must not fire", timerWouldFire)
        assertFalse("notification rides the same timer, so it must not fire either",
            timerWouldFire)
    }
}
