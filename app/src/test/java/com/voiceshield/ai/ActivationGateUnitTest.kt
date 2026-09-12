package com.voiceshield.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the two activation gates.
 *
 * - [shouldApplyFaceDetection] (FloatingBubbleService.kt): the bubble may only
 *   react to a detected face while a double-tap scan is running, so an idle
 *   bubble can never start "detecting" on its own.
 * - [isScreenCaptureGranted] (MainActivity.kt): protection may only start when
 *   the system screen-share dialog was accepted with a usable payload; pressing
 *   "Cancel" must leave the app untouched.
 *
 * Both are top-level functions with no Android dependencies, so they run on the
 * local JVM exactly like scanDelayMs() (see [ScanModeUnitTest]).
 */
class ActivationGateUnitTest {

    // ── Face detection only drives a running scan ────────────────────────────

    @Test
    fun faceDetectedWhileIdle_isIgnored() {
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = true))
    }

    @Test
    fun faceDetectedWhileScanning_isApplied() {
        assertTrue(shouldApplyFaceDetection(scanning = true, faceDetected = true))
    }

    @Test
    fun noFace_neverStartsTheResultSequence() {
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = false))
        assertFalse(shouldApplyFaceDetection(scanning = true, faceDetected = false))
    }

    // ── Screen-share consent ─────────────────────────────────────────────────

    @Test
    fun grantedConsent_activatesProtection() {
        assertTrue(isScreenCaptureGranted(RESULT_OK, hasData = true))
    }

    @Test
    fun cancelledConsent_doesNotActivateProtection() {
        assertFalse(isScreenCaptureGranted(RESULT_CANCELED, hasData = true))
        assertFalse(isScreenCaptureGranted(RESULT_CANCELED, hasData = false))
    }

    @Test
    fun grantedWithoutPayload_doesNotActivateProtection() {
        assertFalse(isScreenCaptureGranted(RESULT_OK, hasData = false))
    }

    /** Covers OEM dialogs that dismiss with a non-standard result code. */
    @Test
    fun anyOtherResult_doesNotActivateProtection() {
        assertFalse(isScreenCaptureGranted(RESULT_FIRST_USER, hasData = true))
        assertFalse(isScreenCaptureGranted(0, hasData = true))
    }

    private companion object {
        // android.app.Activity result codes. Re-declared as literals so this
        // test stays free of android.jar (whose mockable fields are zeroed).
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
        const val RESULT_FIRST_USER = 1
    }
}
