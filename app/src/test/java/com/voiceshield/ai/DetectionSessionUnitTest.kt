package com.voiceshield.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for UC-02 (face_detection_flow.md) — the detection-session state.
 *
 * Two things the use case names as *system behaviour* are pure rules and therefore testable
 * here without Robolectric or an emulator:
 *
 *  - **Detection status** — enable_floating_bubble.md lists exactly three (Idle, Detecting,
 *    Result displayed), and UC-02 pins the transitions: step 4 starts at Detecting, step 7
 *    moves to "Result displayed", step 9 returns to Idle. [detectionStatusOf] is the whole
 *    mapping.
 *  - **Saving the face into the session** — step 5 saves it, A3 says nothing is saved when no
 *    face was detected, and step 10 removes it. The save gate is [shouldApplyFaceDetection],
 *    the same rule that already gates the model-result sequence.
 *
 * Both are top-level Android-free functions in FloatingBubbleService.kt, in the same
 * convention as [shouldArmScanTimer] (see [FaceGateUnitTest]) and [scanDelayMs].
 */
class DetectionSessionUnitTest {

    // ── Detection status: the full truth table ────────────────────────────────

    /**
     * The rule is a pure function of exactly these two booleans, so every combination is
     * pinned rather than sampled.
     */
    @Test
    fun statusTruthTableIsComplete() {
        val expected = mapOf(
            (false to false) to DetectionStatus.IDLE,
            (false to true) to DetectionStatus.IDLE,
            (true to false) to DetectionStatus.DETECTING,
            (true to true) to DetectionStatus.RESULT_DISPLAYED
        )
        expected.forEach { (input, want) ->
            assertEquals(
                "scanning=${input.first} resultDisplayed=${input.second}",
                want,
                detectionStatusOf(input.first, input.second)
            )
        }
    }

    /** UC-02 step 9 / A2 / A6 / A7: no session running => Idle, whatever else is true. */
    @Test
    fun anInactiveBubbleIsAlwaysIdle() {
        assertEquals(DetectionStatus.IDLE, detectionStatusOf(scanning = false, resultDisplayed = false))
        assertEquals(DetectionStatus.IDLE, detectionStatusOf(scanning = false, resultDisplayed = true))
    }

    /** UC-02 step 4: a fresh double-tap is Detecting until the result is shown. */
    @Test
    fun aRunningSessionStartsOutDetecting() {
        assertEquals(
            DetectionStatus.DETECTING,
            detectionStatusOf(scanning = true, resultDisplayed = false)
        )
    }

    /** UC-02 step 7: showing the result moves the status, and it stays there. */
    @Test
    fun showingTheResultIsStickyUntilDeactivation() {
        // Step 4 — the double-tap has started the session.
        assertEquals(
            DetectionStatus.DETECTING,
            detectionStatusOf(scanning = true, resultDisplayed = false)
        )

        // Steps 6/7 — the pop-up (and, in Warning Mode, the notification) has been shown.
        assertEquals(
            DetectionStatus.RESULT_DISPLAYED,
            detectionStatusOf(scanning = true, resultDisplayed = true)
        )

        // A1 — the user presses "Đóng". Closing the pop-up is *not* a deactivation, so the
        // postcondition "the detection status is Result displayed" still holds.
        assertEquals(
            DetectionStatus.RESULT_DISPLAYED,
            detectionStatusOf(scanning = true, resultDisplayed = true)
        )

        // Steps 8/9 — only the second double-click returns it to Idle.
        assertEquals(
            DetectionStatus.IDLE,
            detectionStatusOf(scanning = false, resultDisplayed = false)
        )
    }

    /**
     * The status can never report Idle while the bubble is active — Idle and "active" are
     * mutually exclusive by construction, which is what makes A1's "the bubble stays active"
     * an observable fact rather than a claim.
     */
    @Test
    fun anActiveSessionIsNeverIdle() {
        listOf(false, true).forEach { resultDisplayed ->
            val status = detectionStatusOf(scanning = true, resultDisplayed = resultDisplayed)
            assertTrue(
                "scanning=true offered $status",
                status == DetectionStatus.DETECTING || status == DetectionStatus.RESULT_DISPLAYED
            )
            assertFalse(status == DetectionStatus.IDLE)
        }
    }

    /** enable_floating_bubble.md §"Detection status" lists exactly these three, in this order. */
    @Test
    fun thereAreExactlyTheThreeDocumentedStatuses() {
        assertEquals(3, DetectionStatus.values().size)
        assertEquals(
            listOf(
                DetectionStatus.IDLE,
                DetectionStatus.DETECTING,
                DetectionStatus.RESULT_DISPLAYED
            ),
            DetectionStatus.values().toList()
        )
    }

    // ── Saving the face into the session (step 5 / A3 / step 10) ──────────────

    /**
     * A3: "No face detected: nothing is saved to the session". The gate is the same
     * [shouldApplyFaceDetection] the model-result sequence uses, so the two can never
     * disagree about whether a face was consumed.
     */
    @Test
    fun noFaceMeansNothingIsSavedIntoTheSession() {
        assertFalse(shouldApplyFaceDetection(scanning = true, faceDetected = false))
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = false))
    }

    /** Step 5: a face reported during the run *is* saved into the session. */
    @Test
    fun aFaceSeenByARunningSessionIsSaved() {
        assertTrue(shouldApplyFaceDetection(scanning = true, faceDetected = true))
    }

    /**
     * A face reported while the bubble is idle must not be carried into a session that has not
     * started: step 5 says the *session* saves the face, and there is no session yet.
     */
    @Test
    fun aFaceSeenWhileIdleIsNotSaved() {
        assertFalse(shouldApplyFaceDetection(scanning = false, faceDetected = true))
    }

    /**
     * A face-gated scan (the hidden "Chế độ nhận diện khuôn mặt" option) parks with the bubble
     * already active, so the save gate and the pop-up gate are read separately: the parked
     * session saves a face as soon as one appears, while the random window stays closed until
     * then.
     */
    @Test
    fun aParkedSessionSavesAFaceButDoesNotYetArmThePopup() {
        // Parked: active, gate on, no face yet.
        assertFalse(shouldArmScanTimer(faceGateEnabled = true, faceDetected = false))
        assertFalse(shouldApplyFaceDetection(scanning = true, faceDetected = false))
        // The face arrives: the session saves it and the window opens together.
        assertTrue(shouldApplyFaceDetection(scanning = true, faceDetected = true))
        assertTrue(shouldArmScanTimer(faceGateEnabled = true, faceDetected = true))
    }
}