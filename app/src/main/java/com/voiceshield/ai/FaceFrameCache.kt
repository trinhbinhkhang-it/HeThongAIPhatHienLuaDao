package com.voiceshield.ai

/**
 * Single-slot holder for the most recent screen frame that actually contained a face.
 *
 * The screenshot feature wants "the newest image that still has a face in it", which is
 * inherently cross-component: [ScreenCaptureService] is the only place that has the frame
 * and runs face detection, while [FloatingBubbleService] is the only place that knows when
 * an alert fires. Both live in the same app process, so a tiny process-wide singleton is the
 * natural handoff — the same shape as the companion mirrors already used elsewhere.
 *
 * Deliberately just a slot. It does not decide *whether* to capture (the developer toggle in
 * [ProtectionState] does) and it does not decide *when* to send (the alert does). A null
 * [latestJpeg] means no face-bearing frame has been cached, and the alert is then sent
 * without an image rather than with an unrelated one.
 *
 * `@Volatile` because the writer is the capture thread and the reader is the service main
 * thread.
 */
object FaceFrameCache {
    /** JPEG bytes of the newest face-bearing frame, or null when none is cached. */
    @Volatile
    var latestJpeg: ByteArray? = null
        private set

    /** Wall-clock time the cached frame was captured, for diagnostics. */
    @Volatile
    var latestAtMs: Long = 0L
        private set

    fun store(jpeg: ByteArray) {
        latestJpeg = jpeg
        latestAtMs = System.currentTimeMillis()
    }

    /** Drops the cached frame — called when capture stops so a stale image can't be sent. */
    fun clear() {
        latestJpeg = null
        latestAtMs = 0L
    }
}
