package com.voiceshield.ai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.app.KeyguardManager
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import java.lang.ref.WeakReference

/** A UI-only boundary: replace FakeDetectionResult with the model result later. */
data class DetectionResult(val score: Int, val riskLevel: RiskLevel)

enum class RiskLevel { NO_RESULT, NORMAL, SUSPICIOUS, HIGH_RISK }

/**
 * ScreenCaptureService tracks faces for as long as the projection runs, but its
 * results may only drive the bubble while a double-tap scan is active — an idle
 * bubble must never start "detecting" on its own.
 *
 * Pure top-level function (no Android types) so the rule is JVM-testable.
 */
fun shouldApplyFaceDetection(scanning: Boolean, faceDetected: Boolean): Boolean =
    scanning && faceDetected

/**
 * enable_floating_bubble.md §"Detection status" — the three states a detection session can
 * report while protection is running.
 *
 * UC-02 names them explicitly: step 4 ("the system starts face detection (status:
 * Detecting)"), step 7 ("the detection status becomes 'Result displayed'") and step 9
 * ("the system changes the bubble to inactive and stops detection (status: Idle)").
 */
enum class DetectionStatus { IDLE, DETECTING, RESULT_DISPLAYED }

/**
 * Derives the UC-02 Detection status from the two flags the bubble already tracks, instead
 * of storing a third one that could drift out of step with them.
 *
 *  - [scanning] false                  -> [DetectionStatus.IDLE]
 *  - scanning, no result shown yet     -> [DetectionStatus.DETECTING]
 *  - scanning, result already shown    -> [DetectionStatus.RESULT_DISPLAYED]
 *
 * The last case is what keeps the UC-02 postcondition "the bubble stays active, detection
 * status is 'Result displayed'" true after [ACTION_UP] on the pop-up's "Đóng" (A1): closing
 * the In-App Pop-up is not a deactivation, so only a second double-click returns the status
 * to Idle.
 *
 * Pure and Android-free, in the same convention as [shouldApplyFaceDetection] and
 * [shouldArmScanTimer], so the state machine is JVM-unit-testable.
 */
fun detectionStatusOf(scanning: Boolean, resultDisplayed: Boolean): DetectionStatus = when {
    !scanning -> DetectionStatus.IDLE
    resultDisplayed -> DetectionStatus.RESULT_DISPLAYED
    else -> DetectionStatus.DETECTING
}

/**
 * Pure geometry gate for the drag-to-dismiss gesture
 * (disable_floating_bubble.md): the X target counts as "hit" once the bubble
 * centre lands within [thresholdPx] of the target centre.
 *
 * Compared on squared distances so no sqrt/rounding enters the rule. Top-level
 * and Android-free, exactly like [shouldApplyFaceDetection], so it is
 * JVM-unit-testable.
 */
fun isOverDismissTarget(
    bubbleCenterX: Float,
    bubbleCenterY: Float,
    targetCenterX: Float,
    targetCenterY: Float,
    thresholdPx: Float
): Boolean {
    val dx = bubbleCenterX - targetCenterX
    val dy = bubbleCenterY - targetCenterY
    return dx * dx + dy * dy <= thresholdPx * thresholdPx
}

/**
 * Pure edge-snap rule (enable_floating_bubble.md): the bubble is freely
 * draggable and, on release, springs to whichever screen edge is nearer while
 * preserving its vertical position.
 *
 * [currentX] is the Gravity.START horizontal offset of a [containerWidth]-wide
 * overlay window on a [screenWidth]-wide display. A degenerate display (narrower
 * than the bubble container) collapses to the left edge instead of producing a
 * negative right edge.
 */
fun nearestEdgeX(currentX: Int, containerWidth: Int, screenWidth: Int, edgeMargin: Int): Int {
    val left = edgeMargin.coerceAtLeast(0)
    val right = (screenWidth - containerWidth - left).coerceAtLeast(left)
    val clamped = currentX.coerceIn(left, right)
    val center = clamped + containerWidth / 2f
    return if (center <= screenWidth / 2f) left else right
}

/**
 * Pure sizing rule for the overlay window that hosts the X target.
 *
 * WindowManager clips a window's drawing to the window rectangle, so a circle
 * resting at [expandedScale] inside a window only [circleSizePx] wide has its
 * four extremes cut off and renders as a *square*. The host window therefore has
 * to be at least as large as the fully expanded circle.
 *
 * Top-level and Android-free so the rule is JVM-unit-testable.
 */
fun dismissTargetWindowSize(circleSizePx: Int, expandedScale: Float): Int =
    ceil(circleSizePx * expandedScale).toInt().coerceAtLeast(circleSizePx)

/**
 * Pure geometry: the screen-space Y (top-left origin) of the X circle's centre.
 *
 * The window is anchored [bottomMarginPx] above the bottom edge and the circle is
 * centred *in the window*, so the window height — not the circle's — decides where
 * the centre sits. Getting this wrong shifts the hit zone away from what the user
 * sees (disable_floating_bubble.md).
 */
fun dismissTargetCenterY(screenHeightPx: Int, bottomMarginPx: Int, windowSizePx: Int): Float =
    screenHeightPx - (bottomMarginPx + windowSizePx / 2f)

/**
 * Pure glyph-insetting rule for the X target.
 *
 * The circle is laid out at its *expanded* diameter (see [dismissTargetWindowSize])
 * and drawn at scale 1.0, so the padding that used to sit on the small base circle
 * has to be grown by the same factor — otherwise the X glyph would swell along with
 * it and stop looking like the same icon at a different size.
 *
 * Top-level and Android-free so it is JVM-unit-testable.
 */
fun dismissTargetGlyphPadding(basePaddingPx: Int, expandedScale: Float): Int =
    ceil(basePaddingPx.coerceAtLeast(0) * expandedScale).toInt().coerceAtLeast(0)

/**
 * Pure collapsed-scale rule: the factor that shrinks the expanded X circle down to
 * exactly the floating bubble's diameter, which is what disable_floating_bubble.md
 * asks for ("the size of the X bubble will shrink to the same size as the floating
 * bubble").
 *
 * Expressed against the *expanded* size because that is now the circle's laid-out
 * size. A non-positive expanded size is guarded so the division can never hand
 * NaN/Infinity to View.animate().
 */
fun dismissCollapsedScale(bubbleSizePx: Int, expandedSizePx: Int): Float =
    if (expandedSizePx <= 0) 1f else bubbleSizePx.toFloat() / expandedSizePx.toFloat()

/**
 * Pure "is the overlay window still really there" rule.
 *
 * The process-local latch raised in showBubble() can be stranded at true by any
 * teardown path that never reaches onDestroy() — the system reclaiming the overlay,
 * a swallowed removeView(), an exception thrown half-way through teardown. Reading
 * the live View is the only authority on whether a window still exists.
 *
 * This is the fix behind enable_floating_bubble.md's "The interface shouldn't become
 * 'Đang bảo vệ' if the app's bubble is not displayed on the screen": Home stops
 * trusting bookkeeping and starts asking the window.
 *
 * Only ever consulted once [canVerifyWindowAttachment] allows it — see there for why
 * reading the View too early is worse than not reading it at all.
 */
fun isOverlayWindowAttached(
    viewInitialized: Boolean,
    attachedToWindow: Boolean,
    hasWindowToken: Boolean
): Boolean = viewInitialized && attachedToWindow && hasWindowToken

/**
 * Pure rule for whether the live View may be trusted to correct the liveness latch.
 *
 * `WindowManager.addView()` returns *before* the first traversal, so
 * `View.isAttachedToWindow` is still false — and `windowToken` still null — for a
 * frame or two on a window that is perfectly healthy. Verifying inside that window
 * would tear down a bubble that was just shown, which is worse than the staleness it
 * is meant to cure, so verification only starts once the attach has had time to
 * settle. Until then the latch (raised only after a *successful* addView) is the
 * better authority.
 */
fun canVerifyWindowAttachment(attachSettled: Boolean, viewInitialized: Boolean): Boolean =
    attachSettled && viewInitialized

/**
 * Pure gate for the hidden "Chế độ nhận diện khuôn mặt" developer option.
 *
 * With the gate OFF the random popup timer is armed the instant a double-tap starts
 * the scan — the historical behaviour, and what enable_floating_bubble.md describes.
 * With it ON the scan must first see a real human face on screen, so the timer stays
 * unarmed and no warning can fire until ScreenCaptureService reports one.
 *
 * Deliberately a top-level Android-free function, like every other rule here, so the
 * gate is JVM-unit-testable.
 */
fun shouldArmScanTimer(faceGateEnabled: Boolean, faceDetected: Boolean): Boolean =
    !faceGateEnabled || faceDetected


/**
 * Pure rule for the Home status card: the app may only claim "Đang bảo vệ" while a
 * bubble really is on screen.
 *
 * enable_floating_bubble.md: "The interface shouldn't become 'Đang bảo vệ' if the
 * app's bubble is not displayed on the screen." The persisted switch alone can
 * outlive the bubble — a process kill, a reboot or a force-stop all leave it set
 * with nothing rendered — so it has to be AND-ed with live liveness.
 */
fun isProtectionEffective(persistedActive: Boolean, bubbleVisible: Boolean): Boolean =
    persistedActive && bubbleVisible

/**
 * Pure lock-screen rule — enable_floating_bubble.md Lifecycle Rules:
 * "When the device is locked, the bubble is hidden. After the device is unlocked,
 * the bubble becomes visible again if protection remains enabled."
 *
 * Derived from the *current* keyguard state rather than latched onto broadcast
 * edges. That distinction is the whole fix: ACTION_USER_PRESENT is not sent at
 * all when the device has no secure lock screen, and any screen broadcast can be
 * deferred indefinitely while the process sits in the Android 12+ cached-app
 * freezer. An edge-triggered hide therefore had no matching edge to un-hide it,
 * stranding the bubble invisible for the rest of the session.
 */
fun bubbleShouldBeVisible(protectionActive: Boolean, keyguardShowing: Boolean): Boolean =
    protectionActive && !keyguardShowing

/**
 * Pure honesty rule for the Home badge: an overlay window that is attached but
 * hidden for the lock screen has *not* been "successfully rendered", so it must
 * not count as visible.
 *
 * Without this, enable_floating_bubble.md's "The interface shouldn't become
 * 'Đang bảo vệ' if the app's bubble is not displayed on the screen" fails during
 * the entire locked period.
 */
fun isBubbleOnScreen(windowAttached: Boolean, hiddenByLock: Boolean): Boolean =
    windowAttached && !hiddenByLock

/**
 * Pure composite "the device is locked or asleep" rule behind the Lifecycle Rules.
 *
 * Any one signal is enough: a non-interactive display means nothing should be drawn
 * at all, while an up keyguard means the bubble must not float over the lock screen.
 * Combining them is also what makes the gate robust across devices — a handset with
 * no secure lock screen never reports a keyguard *and* never broadcasts
 * ACTION_USER_PRESENT, so the display state has to carry the "locked" half on its
 * own.
 */
fun isDeviceLockedForBubble(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
    deviceLocked: Boolean
): Boolean = !screenInteractive || keyguardLocked || deviceLocked

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleContent: FrameLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var ripple: View
    private lateinit var bubbleLayoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var current = DetectionResult(0, RiskLevel.NO_RESULT)
    private var monitoringStarted = false
    private var resultSequenceStarted = false
    private var participantFaceDetected = false
    /** Pending model updates belonging to the running scan; retracted by stopScan(). */
    private val resultRunnables = mutableListOf<Runnable>()
    private var isFloating = false

    /**
     * True once the overlay window has had time to finish its first traversal after
     * `WindowManager.addView()`.
     *
     * Until then `View.isAttachedToWindow` is still false on a perfectly healthy
     * window, so the liveness check in [refreshBubbleLiveness] must stay off — see
     * [canVerifyWindowAttachment]. Armed by a short postDelayed in showBubble().
     */
    private var windowAttachSettled = false

    /**
     * True while a scan is running. Drives the bubble visual state:
     * idle = neutral surface + hairline + secondary shield, no ripple;
     * scanning = mint bubble + white shield + looping ripple.
     */
    private var scanning = false
    private var rippleDuration = RIPPLE_DURATION_MS

    /**
     * True while a face-gated scan is parked waiting for the first real human face to
     * appear on screen. Set from the hidden "Chế độ nhận diện khuôn mặt" developer
     * option: while it holds, the random popup timer is *not* armed, so no warning can
     * fire. Cleared the moment ScreenCaptureService reports a face.
     */
    private var awaitingFace = false

    /** True once [armScanTimer] has scheduled the popup for the current scan. */
    private var scanTimerArmed = false

    /**
     * developer_setting_page.md §6 — the mode this detection session runs with.
     *
     * Copied from [ProtectionState.getMode] exactly once, when a double tap starts the scan,
     * and then *locked*: "The session mode remains unchanged until the bubble becomes
     * inactive." Every consumer of the mode inside a live session — the popup timer, the
     * popup itself, the warning notification and the model-result sequence — reads this
     * rather than the preference, so a mode selected mid-scan can only ever affect the *next*
     * session. Cleared by [stopScan] and by [onDestroy]; mirrored into the companion so
     * [SettingsActivity] can display it.
     */
    private var sessionMode: ScanMode? = null

    /**
     * UC-02 step 7 — true once the live session has shown its result (the pop-up, and in
     * Warning Mode the notification that rides the same timer).
     *
     * Reset by [startScan] and [stopScan], and *not* by closing the pop-up (A1), which is
     * what makes [detectionStatusOf] report "Result displayed" for the rest of the session.
     */
    private var resultDisplayed = false

    /**
     * UC-02 step 5 — the face the live session saved, in screen coordinates.
     *
     * Filled from the tracked rectangle [ScreenCaptureService] puts on its face-detection
     * broadcast, and only while a scan is running (an idle bubble saves nothing, A3). It
     * stays for as long as the session does and is dropped exactly when the session ends —
     * [stopScan] for a second double-click (step 10) and [onDestroy] for the teardown paths
     * that never reach [stopScan] (A2, A6, A7). Null means "no face in this session".
     */
    private var sessionFace: Rect? = null

    /**
     * The most recent face rectangle ScreenCaptureService reported, remembered even while the
     * bubble is idle — the exact companion of [participantFaceDetected], and for the same
     * reason: the capture service only broadcasts when that state *changes*, so a face already
     * in frame when the user double-taps would otherwise never reach the new session.
     *
     * Deliberately not session-scoped and not cleared by [stopScan]: it caches what the screen
     * currently shows, of which [sessionFace] is the session-scoped copy.
     */
    private var lastReportedFaceBounds: Rect? = null

    /** Guards the blink recursion so stopBlink() can't be raced by an end-action. */
    private var isBlinking = false
    private var blinkingIcon: ImageView? = null

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    /**
     * The edge-snap spring currently running, if any. Held so it can be cancelled when the
     * bubble is grabbed again or a newer snap supersedes it — see [cancelSnapAnimation].
     */
    private var snapAnimator: ValueAnimator? = null

    // ── Drag-to-dismiss (X bubble) ──────────────────────────────────────────
    /**
     * The gray X circle (the view that scales on overlap) and the transparent
     * window that hosts it. The host is deliberately *larger* than the circle —
     * see [dismissTargetWindowSize] — or the expanded circle would be clipped to a
     * square. Both are non-null only while a press-and-hold/drag is live.
     */
    private var dismissTarget: View? = null
    private var dismissTargetWindow: View? = null

    /** True while the bubble overlaps the X target — drives the shrink animation. */
    private var isOverDismiss = false

    /** Guards the haptic so it fires once per entry into the zone, not per frame. */
    private var hasVibratedThisOverlap = false

    // ── Phase 4: design-token colours loaded once ──
    private var cMint100 = 0
    private var cMint500 = 0
    private var cMint600 = 0
    private var cSurface = 0
    private var cOutline = 0
    private var cSurfaceAlt = 0
    private var cTextPrimary = 0
    private var cTextSecondary = 0
    private var cTextOnPrimary = 0
    private var cTerracotta = 0
    private var cTerracottaSoft = 0
    private var cTerracottaDeep = 0
    private var cRiskSafe = 0
    private var cRiskCaution = 0
    private var cRiskDanger = 0
    private var cScrim = 0
    private var cDismissTargetBg = 0

    // ─ Nunito font (system sans-serif-medium fallback) ──
    private lateinit var fontMedium: Typeface
    private lateinit var fontBold: Typeface

    /**
     * Double-click is the only bubble gesture that toggles the scan (spec §3),
     * and press-and-hold is what reveals the X dismiss target
     * (disable_floating_bubble.md). Every touch event is offered to this
     * detector first, so both coexist with the manual drag handling below.
     */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleScan()
                return true
            }

            /**
             * "A large X bubble with a gray color will appear at the bottom of
             * the screen, when the user presses and holds the floating bubble."
             * A double-tap settles well inside the long-press timeout, so this
             * never fires on the toggle gesture.
             */
            override fun onLongPress(e: MotionEvent) {
                showDismissTarget()
            }
        })
    }

    /**
     * Fires once per scan and shows the popup for the mode *this session* was started with.
     * Deliberately a named Runnable: stopScan() cancels just this callback,
     * whereas removeCallbacksAndMessages(null) would also drop pending model
     * updates and break the Phase-4 result sequence.
     *
     * developer_setting_page.md §6: the popup and the notification both come from
     * [sessionMode], never from the live preference, so a mode chosen mid-scan cannot change
     * what this session reports.
     */
    private val scanRunnable = Runnable {
        if (!scanning) return@Runnable
        // Defence in depth for the face gate: a scan still parked waiting for a real
        // face must never produce a result, even if something armed the timer early.
        if (awaitingFace || !scanTimerArmed) return@Runnable
        scanTimerArmed = false
        when (activeSessionMode()) {
            ScanMode.SAFE -> showSafePopup()
            ScanMode.WARNING -> {
                showWarningPopup()
                showHighRiskNotification()
            }
        }
        // UC-02 step 7: the session has now produced its result, so the Detection status
        // moves from Detecting to "Result displayed" and stays there until deactivation —
        // closing the pop-up (A1) must not walk it back.
        resultDisplayed = true
        publishDetectionStatus()
    }

    // NOTE: there is deliberately no ACTION_MODE_CHANGED receiver here any more. It used to
    // re-roll the pending popup window when Page 2 changed the mode mid-scan, which
    // developer_setting_page.md §6 forbids: "A new mode applies only to the next detection
    // session." A mode edit is now invisible to a running session by construction — the
    // session keeps the mode it copied at startScan(). ACTION_INTERVALS_CHANGED is a
    // different thing (the roll *window*, not the mode) and still re-rolls.

    /** Same, for a roll window edited on the hidden developer page. */
    private val intervalsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProtectionState.ACTION_INTERVALS_CHANGED) return
            if (scanning && !awaitingFace) armScanTimer()
        }
    }

    /**
     * The hidden page can flip "Chế độ nhận diện khuôn mặt" while a scan is live, so the
     * running scan follows the new setting instead of waiting for the next double-tap:
     * gate off (or a face already in frame) releases a parked scan immediately, gate on
     * with no face retracts a pending window.
     */
    private val faceGateChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProtectionState.ACTION_FACE_GATE_CHANGED) return
            if (!scanning) return
            val gateSatisfied = shouldArmScanTimer(
                ProtectionState.isFaceGateEnabled(this@FloatingBubbleService),
                participantFaceDetected
            )
            if (gateSatisfied) {
                releaseFaceGateIfSatisfied()
            } else if (!awaitingFace) {
                awaitingFace = true
                scanTimerArmed = false
                handler.removeCallbacks(scanRunnable)
                render()
            }
        }
    }

    private val faceDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED) return

            val detected = intent.getBooleanExtra(ScreenCaptureService.EXTRA_FACE_DETECTED, false)
            // The tracked rectangle rides the same broadcast (UC-02 step 5); read it once.
            val bounds = intent.faceBounds()
            // Always remember the latest state, even while idle: the user may
            // double-tap later and no new broadcast will arrive by then.
            participantFaceDetected = detected
            // Cache the rectangle beside the flag, under the same "remember it even while idle"
            // rule: a session started after this report has no new broadcast coming.
            lastReportedFaceBounds = if (detected) bounds ?: lastReportedFaceBounds else null
            // "Chế độ nhận diện khuôn mặt": this is the event a parked scan waits for.
            releaseFaceGateIfSatisfied()
            // Idle bubble -> ignored. Only a double-tap scan consumes results.
            if (shouldApplyFaceDetection(scanning, detected)) {
                // UC-02 step 5: "the system saves the detected face to the current session."
                // The same gate as the model-result sequence, so an idle bubble saves nothing
                // (A3) and a face reported before the double-tap is never carried into the
                // new session.
                if (bounds != null) {
                    sessionFace = bounds
                    // Republish so the companion's sessionFaceBounds tracks the face the
                    // session now holds, not just the one it had when the status last moved.
                    publishDetectionStatus()
                }
                beginResultSequence()
            }
        }
    }

    /**
     * The tracked face rectangle [ScreenCaptureService] attaches to its face-detection
     * broadcast, or null when the report carried none.
     *
     * API-guarded for the same reason [ScreenCaptureService] guards its own projection
     * payload: the typed `getParcelableExtra` overload only exists from API 33.
     */
    private fun Intent.faceBounds(): Rect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(ScreenCaptureService.EXTRA_FACE_BOUNDS, Rect::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(ScreenCaptureService.EXTRA_FACE_BOUNDS)
        }

    /**
     * Component 6 — Lifecycle Rules (enable_floating_bubble.md): "When the device is
     * locked, the bubble is hidden. After the device is unlocked, the bubble becomes
     * visible again if protection remains enabled."
     *
     * All three actions re-derive the gate from the *real* keyguard state instead of
     * toggling it, so a missed or deferred broadcast cannot strand the bubble.
     * ACTION_SCREEN_ON is included because ACTION_USER_PRESENT is never sent when the
     * device has no secure lock screen — relying on it alone is what left the bubble
     * permanently invisible after unlock.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> applyLockVisibility()
            }
        }
    }

    /**
     * Safety net for the lock gate. Broadcasts are the primary trigger but they can be
     * dropped (no secure keyguard => no ACTION_USER_PRESENT) or deferred while the
     * process is frozen, so the gate is also re-derived once a second. A tick only
     * reads KeyguardManager and asserts a visibility, both cheap and idempotent, and
     * it is the reason an unfrozen process recovers without waiting for any event.
     */
    private val lockWatchdog = object : Runnable {
        override fun run() {
            applyLockVisibility()
            handler.postDelayed(this, LOCK_WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Protection is flipped on by showBubble() *after* the bubble is really on
        // screen, so on a fresh start this reads false; it is only true when the
        // service is recreated while protection is already persisted.
        monitoringStarted = ProtectionState.isActive(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // ─ Phase 4: load design tokens once ──
        cMint100 = ContextCompat.getColor(this, R.color.mint_100)
        cMint500 = ContextCompat.getColor(this, R.color.mint_500)
        cMint600 = ContextCompat.getColor(this, R.color.mint_600)
        cSurface = ContextCompat.getColor(this, R.color.surface)
        cOutline = ContextCompat.getColor(this, R.color.outline)
        cSurfaceAlt = ContextCompat.getColor(this, R.color.surface_alt)
        cTextPrimary = ContextCompat.getColor(this, R.color.text_primary)
        cTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        cTextOnPrimary = ContextCompat.getColor(this, R.color.text_on_primary)
        cTerracotta = ContextCompat.getColor(this, R.color.terracotta)
        cTerracottaSoft = ContextCompat.getColor(this, R.color.terracotta_soft)
        cTerracottaDeep = ContextCompat.getColor(this, R.color.terracotta_deep)
        cRiskSafe = ContextCompat.getColor(this, R.color.risk_safe)
        cRiskCaution = ContextCompat.getColor(this, R.color.risk_caution)
        cRiskDanger = ContextCompat.getColor(this, R.color.risk_danger)
        cScrim = ContextCompat.getColor(this, R.color.scrim)
        cDismissTargetBg = ContextCompat.getColor(this, R.color.dismiss_target_bg)

        // Nunito font — system sans-serif-medium as flat-design fallback
        fontMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontBold = Typeface.create("sans-serif-medium", Typeface.BOLD)

        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            faceDetectionReceiver,
            IntentFilter(ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // The hidden developer page can change the roll windows while a scan is
        // pending; re-arm so the edit takes effect immediately. The *mode* is
        // deliberately not listened for — see the note by scanRunnable.
        ContextCompat.registerReceiver(
            this,
            intervalsChangedReceiver,
            IntentFilter(ProtectionState.ACTION_INTERVALS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Same page can toggle the "Chế độ nhận diện khuôn mặt" gate mid-scan.
        ContextCompat.registerReceiver(
            this,
            faceGateChangedReceiver,
            IntentFilter(ProtectionState.ACTION_FACE_GATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Component 6. All three of these are *protected* system broadcasts, so only
        // the OS can send them and RECEIVER_EXPORTED cannot be spoofed by another app.
        // EXPORTED (rather than NOT_EXPORTED) is deliberate: a NOT_EXPORTED
        // registration is documented to accept system broadcasts, but several OEM
        // builds drop them, which is one of the ways the unlock edge went missing.
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The scan mode is read straight from ProtectionState — no intent extras.
        // The "Đang bảo vệ" flip is deliberately NOT done here: onCreate ->
        // showBubble() owns it, so the app can never report protection before the
        // bubble has actually been rendered (enable_floating_bubble.md).
        monitoringStarted = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Liveness goes first: anything that re-syncs during teardown (including the
        // setActive(false) broadcast below) must already see "no bubble on screen".
        bubbleAttached = false
        bubbleHiddenByLock = false
        windowAttachSettled = false
        // The session dies with the service: nothing may keep claiming a locked mode, the
        // face it saved goes with it, and no pop-up window can still be up once this returns.
        sessionMode = null
        resultDisplayed = false
        sessionFace = null
        // No stopScan() runs on this path (A2 "Tắt bảo vệ", A6 drag-to-X, A7 lifecycle), so
        // the active flag is cleared here too. It is what the Detection status is derived
        // from, and a dead service must report Idle rather than a session that no longer runs.
        scanning = false
        publishSessionMode()
        publishDetectionStatus()
        popupAttached = false
        liveInstanceRef = null
        // Announced *before* the teardown work rather than after it. This is the signal
        // that makes the Home card stop claiming "Đang bảo vệ" the instant the bubble
        // goes — including when the X target dismissed it from inside another app while
        // this activity's ACTION_CHANGED receiver was unregistered and could not hear it.
        publishBubbleVisibility()

        // Every remaining step is isolated. A throwing teardown used to be able to skip
        // everything after it — in particular the setActive(false) below — and that is
        // exactly how the persisted switch came to outlive the bubble.
        runCatching {
            // Cancel the pending scan popup before the blanket handler flush.
            handler.removeCallbacks(scanRunnable)
            handler.removeCallbacks(lockWatchdog)
            stopBlink()
            handler.removeCallbacksAndMessages(null)
            cancelSnapAnimation()
            stopFloating()
            stopRipple()
        }
        runCatching { ProtectionState.setActive(this, false) }
        runCatching { unregisterReceiver(faceDetectionReceiver) }
        runCatching { unregisterReceiver(intervalsChangedReceiver) }
        runCatching { unregisterReceiver(faceGateChangedReceiver) }
        runCatching { unregisterReceiver(screenStateReceiver) }
        runCatching { removePanel() }
        // The X target is a second overlay window: it must never outlive the bubble.
        runCatching { hideDismissTarget() }
        runCatching { windowManager.removeView(bubbleContainer) }
        // Final restatement, in case a step above changed what is really on screen.
        publishBubbleVisibility()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val containerSize = dimen(R.dimen.bubble_container_size)
        val bubbleSize = dimen(R.dimen.bubble_size)
        val edgeMargin = dimen(R.dimen.bubble_edge_margin)
        val dragSlop = dimen(R.dimen.bubble_drag_slop)

        bubbleContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        bubbleContent = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        ripple = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
            background = circle(Color.TRANSPARENT, cMint500, 2)
            alpha = 0f
        }

        // Shield glyph replaces the old "◉" TextView; render() retints it per state.
        bubbleIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
            setImageResource(R.drawable.ic_shield)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dimen(R.dimen.bubble_icon_padding)
            setPadding(pad, pad, pad, pad)
            background = circle(cSurface, cOutline, 1)
            setColorFilter(cTextSecondary)
            contentDescription = getString(R.string.bubble_desc_idle)
        }

        bubbleContent.addView(ripple)
        bubbleContent.addView(bubbleIcon)

        bubbleContent.setOnTouchListener { _, event ->
            // Component 7 (disable_floating_bubble.md): "If an In-App Pop-up is
            // visible, the user cannot interact with the floating bubble. The user
            // must close the pop-up before disabling the bubble." The event is
            // consumed but ignored, so it cannot reach the drag handler, the double
            // tap detector or the press-and-hold that raises the X. The popup's own
            // buttons stay usable because they live in a separate overlay window.
            if (panel != null) return@setOnTouchListener true
            // Offer every event to the double-click detector first. A drag can
            // still begin afterwards, so the two gestures coexist.
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    // Grabbing the bubble mid-spring takes over from the animation: let it go,
                    // or it keeps writing x against the drag and (with §5 enabled) would end by
                    // selecting the mode of an edge the bubble was pulled away from.
                    cancelSnapAnimation()
                    initialX = bubbleLayoutParams.x
                    initialY = bubbleLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                        isDragging = true
                        // A drag is itself a "press and hold": raise the X target
                        // even if the long-press timeout has not elapsed yet.
                        showDismissTarget()
                    }
                    if (isDragging) {
                        // Gravity.START: x grows rightwards, so the delta is ADDED.
                        // (Under the old Gravity.END it had to be subtracted.)
                        bubbleLayoutParams.x = (initialX + dx).coerceIn(0, maxX())
                        bubbleLayoutParams.y = (initialY - dy).coerceIn(0, maxY())
                        windowManager.updateViewLayout(bubbleContainer, bubbleLayoutParams)
                        updateDismissTargetState()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Single tap stays a no-op: spec §3 defines only the double
                    // click. Dropping the bubble on the X target disables
                    // protection; dropping it anywhere else springs it to the
                    // nearer edge. ACTION_CANCEL is handled too, or a system
                    // gesture that steals the drag would strand the X on screen.
                    val dismissed = isDragging && isOverDismiss
                    hideDismissTarget()
                    when {
                        dismissed -> stopProtection()
                        isDragging -> snapToNearestEdge()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        bubbleContainer.addView(
            bubbleContent,
            FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
        )

        // Pinned to the LEFT edge on first render (main flow §3: "a floating
        // bubble on the bottom left corner as an initial position"); after that
        // the user may drag it anywhere and it snaps to the nearer edge.
        bubbleLayoutParams = overlayParams(
            containerSize,
            containerSize,
            Gravity.BOTTOM or Gravity.START,
            true
        ).apply {
            x = edgeMargin
            y = dimen(R.dimen.bubble_bottom_margin)
        }

        // enable_floating_bubble.md: "The app must not display 'Đang bảo vệ'
        // unless the bubble has been successfully rendered." So the persisted flag
        // — and the Home badge that listens for its broadcast — is only flipped
        // once addView() has genuinely put the bubble on screen.
        val attached = runCatching {
            windowManager.addView(bubbleContainer, bubbleLayoutParams)
        }.isSuccess
        if (!attached) {
            // Nothing was rendered: tear the whole protection session back down so
            // the interface stays "Chưa kích hoạt" and no projection is orphaned.
            stopProtection()
            return
        }

        monitoringStarted = true
        // Liveness flag behind the Home badge. Set only now — after addView() has
        // really put the bubble on screen — and cleared in onDestroy(). It is
        // process-local by design: a kill, a reboot or a force-stop resets it to
        // false, which is exactly what enable_floating_bubble.md's Lifecycle Rules
        // require ("Force-stopping the app disables protection and hides the
        // bubble" / "Restarting the device disables protection").
        bubbleAttached = true
        windowAttachSettled = false
        liveInstanceRef = WeakReference(this)
        ProtectionState.setActive(this, true)
        render()

        // Component 6: settle the lock gate immediately (a service started while the
        // keyguard is up must not draw), then keep re-deriving it so a missed or
        // freezer-deferred unlock broadcast can never strand the bubble invisible.
        applyLockVisibility()
        // Tell Home the bubble is up. setActive() above reported the *switch*; this is
        // the signal that carries "a window is really on screen now".
        publishBubbleVisibility()
        // addView() has returned but the first traversal has not run yet, so the View
        // still reports "not attached". Only start believing it once that settles —
        // see canVerifyWindowAttachment().
        handler.postDelayed({ windowAttachSettled = true }, ATTACH_SETTLE_MS)
        handler.removeCallbacks(lockWatchdog)
        handler.postDelayed(lockWatchdog, LOCK_WATCHDOG_INTERVAL_MS)
    }

    /**
     * Component 6 — re-derives the lock gate and asserts the bubble's visibility.
     *
     * Idempotent and state-driven rather than edge-driven, so it is safe to call from
     * every screen broadcast, from [lockWatchdog] and from [syncBubbleVisibility].
     * The overlay window itself is never removed: drag position, animations and the
     * liveness flag all survive a lock period, only the drawing stops.
     */
    private fun applyLockVisibility() {
        if (!::bubbleContainer.isInitialized) return

        // Ask the live window whether it still exists before anything else. A bubble
        // the system reclaimed without ever calling onDestroy() would otherwise keep
        // the liveness latch true forever — which is exactly how Home came to show
        // "Đang bảo vệ - Tắt ứng dụng" over an empty screen.
        if (refreshBubbleLiveness()) {
            stopProtection()
            return
        }

        if (isDeviceLockedNow()) {
            if (!bubbleHiddenByLock) {
                // Entering a lock period. A gesture can be mid-flight, so drop the X
                // target and the drag state — nothing may be stranded behind the
                // keyguard for the user to trip over on unlock.
                hideDismissTarget()
                isDragging = false
                bubbleHiddenByLock = true
            }
            bubbleContainer.visibility = View.GONE
            publishBubbleVisibility()
            return
        }

        val wasHidden = bubbleHiddenByLock
        bubbleHiddenByLock = false
        if (wasHidden && !ProtectionState.isActive(this)) {
            // "…becomes visible again *if protection remains enabled*." It no longer
            // is, so the window must not come back. Tearing down also clears the stale
            // switch, so Home cannot keep claiming "Đang bảo vệ".
            stopProtection()
            return
        }
        // Asserted on every evaluation, not only on a transition: setVisibility() is a
        // no-op when nothing changed, and re-asserting is exactly what unsticks a
        // window left GONE by a ACTION_USER_PRESENT that never arrived.
        bubbleContainer.visibility = View.VISIBLE
        publishBubbleVisibility()
    }

    /**
     * Re-reads the live overlay window and corrects the liveness latch against it.
     *
     * @return true when the latch had to be *cleared* — the window is gone even though
     * the service still believed a bubble existed. Callers must tear the session down.
     *
     * A no-op until [canVerifyWindowAttachment] allows it: `addView()` returns before
     * the first traversal, and trusting `isAttachedToWindow` during that gap would
     * destroy a bubble that was just shown.
     */
    private fun refreshBubbleLiveness(): Boolean {
        if (!canVerifyWindowAttachment(windowAttachSettled, ::bubbleContainer.isInitialized)) {
            return false
        }
        val attached = isOverlayWindowAttached(
            viewInitialized = true,
            attachedToWindow = bubbleContainer.isAttachedToWindow,
            hasWindowToken = bubbleContainer.windowToken != null
        )
        if (attached == bubbleAttached) return false
        bubbleAttached = attached
        publishBubbleVisibility()
        return !attached
    }

    /**
     * Announces the bubble's real on-screen state to whoever is listening (Home).
     *
     * De-duplicated against the last published value so the once-a-second lock
     * watchdog cannot spam identical broadcasts. Deliberately fire-and-forget inside a
     * runCatching: reporting state must never be able to break the teardown path that
     * called it, which is how the badge came to be stranded before.
     */
    private fun publishBubbleVisibility() {
        val visible = isBubbleVisible
        if (lastPublishedVisibility == visible) return
        lastPublishedVisibility = visible
        runCatching {
            sendBroadcast(
                Intent(ACTION_BUBBLE_VISIBILITY_CHANGED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_BUBBLE_VISIBLE, visible)
            )
        }
    }

    /** Reads the three lock signals behind [isDeviceLockedForBubble]. */
    private fun isDeviceLockedNow(): Boolean = runCatching {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        isDeviceLockedForBubble(
            screenInteractive = power.isInteractive,
            keyguardLocked = keyguard.isKeyguardLocked,
            deviceLocked = keyguard.isDeviceLocked
        )
    }.getOrDefault(false)

    /**
     * Idle     -> neutral surface bubble, hairline outline, secondary-tinted
     *             shield, no ripple, gentle float.
     * Scanning -> mint bubble (escalating to the risk colour once the model
     *             reports), white shield, looping ripple, no float.
     */
    private fun render() {
        if (!scanning) {
            bubbleIcon.background = circle(cSurface, cOutline, 1)
            bubbleIcon.setColorFilter(cTextSecondary)
            bubbleIcon.contentDescription = getString(R.string.bubble_desc_idle)
            stopRipple()
            startFloating()
            return
        }

        val level = current.riskLevel
        val escalated = level == RiskLevel.SUSPICIOUS || level == RiskLevel.HIGH_RISK
        val color = if (escalated) colorFor(level) else cMint500
        val duration = if (level == RiskLevel.HIGH_RISK) RIPPLE_DURATION_FAST_MS else RIPPLE_DURATION_MS

        bubbleIcon.background = circle(color)
        bubbleIcon.setColorFilter(cTextOnPrimary)
        // A face-gated scan that has not seen a face yet says so, which is the only
        // feedback the user gets for why nothing is happening.
        bubbleIcon.contentDescription = getString(
            if (awaitingFace) R.string.bubble_desc_waiting_face
            else R.string.bubble_desc_scanning
        )
        ripple.background = circle(Color.TRANSPARENT, color, 2)
        stopFloating()
        startRippleLoop(duration)
    }

    /** Looping ripple used while scanning; re-armed from its own end action. */
    private fun startRippleLoop(duration: Long) {
        rippleDuration = duration
        ripple.animate().cancel()
        ripple.alpha = 0.75f
        ripple.scaleX = 0.9f
        ripple.scaleY = 0.9f
        ripple.animate()
            .scaleX(1.35f)
            .scaleY(1.35f)
            .alpha(0f)
            .setDuration(duration)
            .withEndAction { if (scanning) startRippleLoop(rippleDuration) }
            .start()
    }

    private fun stopRipple() {
        ripple.animate().cancel()
        ripple.alpha = 0f
        ripple.scaleX = 1f
        ripple.scaleY = 1f
    }

    /**
     * Springs the bubble to whichever screen edge is nearer, keeping the dragged
     * vertical offset (enable_floating_bubble.md postconditions: "the bubble
     * snaps to the nearest screen edge" / "preserves its vertical position").
     *
     * developer_setting_page.md §5 hangs off the *end* of that spring, never off the
     * release: with "Đổi chế độ dựa trên vị trí bubble" selected, the edge the bubble
     * finally comes to rest on picks the scan mode, and "The mode changes only after the
     * snap is complete." A cancelled animation (a new gesture took over) applies nothing,
     * because the bubble never reached an edge.
     */
    private fun snapToNearestEdge() {
        val containerWidth = dimen(R.dimen.bubble_container_size)
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeMargin = dimen(R.dimen.bubble_edge_margin)
        val target = nearestEdgeX(bubbleLayoutParams.x, containerWidth, screenWidth, edgeMargin)
        // A new snap supersedes any still running one; cancelling it also stops it from
        // reporting an edge the bubble never came to rest on.
        cancelSnapAnimation()
        if (bubbleLayoutParams.x == target) {
            // Already resting on an edge: the snap is trivially complete, so §5 applies now.
            applyPositionBasedMode(bubbleEdgeOf(target, edgeMargin))
            return
        }
        var cancelled = false
        snapAnimator = ValueAnimator.ofInt(bubbleLayoutParams.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                bubbleLayoutParams.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubbleContainer, bubbleLayoutParams) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    snapAnimator = null
                    if (cancelled) return
                    // Read the edge back off the *actual* resting x rather than the target,
                    // so a layout that clamped differently cannot report the wrong edge.
                    applyPositionBasedMode(bubbleEdgeOf(bubbleLayoutParams.x, edgeMargin))
                }
            })
        }.also { it.start() }
    }

    /**
     * Stops a snap that is still springing, if any.
     *
     * Called when a new snap supersedes it and when the bubble is grabbed again mid-spring:
     * without it the 200 ms animation keeps writing [bubbleLayoutParams] against the drag, and
     * — once §5 is enabled — would finish by selecting the mode of an edge the bubble was
     * dragged away from before it ever arrived.
     */
    private fun cancelSnapAnimation() {
        snapAnimator?.let { runCatching { it.cancel() } }
        snapAnimator = null
    }

    /**
     * developer_setting_page.md §5 — "Đổi chế độ dựa trên vị trí bubble".
     *
     * Runs once the snap has finished, and only when every gate the spec puts on it is open:
     * the method is the selected automatic one, no In-App Pop-up is up (§8), and the bubble
     * is inactive (§5's last bullet, which is [canSelectModeNow]).
     *
     * Re-snapping onto the edge the bubble already sits on requests the mode that is already
     * selected, so [isModeChange] is false and nothing is written or announced — that is what
     * makes "Moving the bubble vertically along the same edge does not change the mode" hold,
     * and what stops a drag up and down one edge from firing a toast per release.
     */
    private fun applyPositionBasedMode(edge: BubbleEdge) {
        val current = ProtectionState.getMode(this)
        // The whole §5 gate is one pure function, so the rule the unit tests pin is exactly
        // the rule that runs here.
        if (!shouldApplyEdgeMode(
                autoSelection = ProtectionState.getAutoModeSelection(this),
                popupVisible = panel != null,
                bubbleActive = scanning,
                currentMode = current,
                edge = edge
            )
        ) {
            return
        }
        val requested = modeForEdge(edge)
        val feedback = feedbackForModeChange(current, requested, ProtectionState.getModeFeedback(this))
        ProtectionState.setMode(this, requested)
        announceModeChange(requested, feedback)
    }

    /**
     * §7 mode-change feedback, from the service side.
     *
     * A toast and an optional vibration pulse only — deliberately *not* a notification: the
     * spec is explicit that "No system notification is created for a mode change", and the
     * warning channel must stay reserved for actual deepfake alerts.
     */
    private fun announceModeChange(mode: ScanMode, feedback: ModeFeedback) {
        if (!feedbackUsesToast(feedback)) return
        Toast.makeText(
            this,
            getString(R.string.mode_changed_feedback, mode.label()),
            Toast.LENGTH_SHORT
        ).show()
        if (feedbackUsesVibration(feedback)) vibrateGently()
    }

    /** Human-readable name of a scan mode, for the §7 feedback toast. */
    private fun ScanMode.label(): String =
        getString(if (this == ScanMode.SAFE) R.string.mode_safe else R.string.mode_warning)

    // ── Drag-to-dismiss: the X bubble ────────────────────────────────────────

    /**
     * Raises the large gray X circle at the bottom-centre of the screen. Called on
     * press-and-hold and again when a drag actually begins; a no-op once it is up.
     *
     * The window is FLAG_NOT_TOUCHABLE so it can never steal the drag from the
     * bubble, and it only lives for the duration of the gesture.
     *
     * Shape: the host window is sized to the *fully expanded* circle
     * ([dismissTargetWindowSize]) and the ImageView fills it, so at rest the oval is
     * inscribed in the window at scale 1.0. That ordering is the whole fix — the
     * previous version laid the circle out at `dismiss_target_size` and scaled it up
     * 1.5x, which put the oval's four extremes exactly *on* the window rectangle.
     * WindowManager clips a window's drawing to that rectangle, so the north/south/
     * east/west points were cut and the shape read as a square. Nothing is drawn past
     * the window bounds now, so there is nothing left to clip; [clipToOutline] against
     * the oval background is kept as a guard for OEM builds that clip the root view
     * regardless.
     */
    private fun showDismissTarget() {
        if (dismissTarget != null) return

        val baseSize = dimen(R.dimen.dismiss_target_size)
        val windowSize = dismissTargetWindowSize(baseSize, DISMISS_SCALE_EXPANDED)
        // The view is now the expanded size, so the base padding grows with it or the
        // X glyph would swell instead of staying the same icon at a larger diameter.
        val glyphPadding = dismissTargetGlyphPadding(
            dimen(R.dimen.dismiss_target_glyph_padding),
            DISMISS_SCALE_EXPANDED
        )

        val target = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(cTextOnPrimary)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(glyphPadding, glyphPadding, glyphPadding, glyphPadding)
            background = circle(cDismissTargetBg)
            contentDescription = getString(R.string.bubble_dismiss_target_desc)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // Resting state IS the "large X bubble" (1.0 == the expanded window); it
            // shrinks to the bubble's own diameter once the bubble is dragged onto it.
            scaleX = DISMISS_SCALE_REST
            scaleY = DISMISS_SCALE_REST
        }

        // Transparent host, exactly the size of the resting circle. No clipChildren
        // escape hatch is needed any more: the child never exceeds the window, which
        // is precisely what keeps the oval from being squared off.
        val host = FrameLayout(this).apply {
            background = null
            addView(
                target,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dimen(R.dimen.dismiss_target_bottom_margin)
        }

        runCatching { windowManager.addView(host, params) }.onSuccess {
            dismissTargetWindow = host
            dismissTarget = target
            isOverDismiss = false
            hasVibratedThisOverlap = false
        }
    }

    /**
     * Re-evaluates the bubble↔X overlap after every drag move. Entering the zone
     * shrinks the X to the bubble's size and fires one gentle vibration; leaving
     * it grows the X back, so the user can still change their mind before release.
     */
    private fun updateDismissTargetState() {
        val target = dismissTarget ?: return

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val containerSize = dimen(R.dimen.bubble_container_size)

        // The bubble window is Gravity.START|BOTTOM (x grows rightwards, y grows
        // upwards) while the X target is bottom-centre. Both centres are converted
        // to top-left-origin screen coordinates so they are directly comparable.
        val bubbleCenterX = bubbleLayoutParams.x + containerSize / 2f
        val bubbleCenterY = screenHeight - (bubbleLayoutParams.y + containerSize / 2f)
        val targetCenterX = screenWidth / 2f
        // The circle is centred in its (larger) host window, so the window height —
        // not the circle's — decides where the centre sits on screen.
        val targetCenterY = dismissTargetCenterY(
            screenHeight,
            dimen(R.dimen.dismiss_target_bottom_margin),
            dismissTargetExpandedSize()
        )

        val overlapping = isOverDismissTarget(
            bubbleCenterX,
            bubbleCenterY,
            targetCenterX,
            targetCenterY,
            dimen(R.dimen.dismiss_overlap_threshold).toFloat()
        )
        if (overlapping == isOverDismiss) return
        isOverDismiss = overlapping

        animateDismissTarget(target, collapsed = overlapping)
        if (overlapping) {
            if (!hasVibratedThisOverlap) {
                hasVibratedThisOverlap = true
                vibrateGently()
            }
        } else {
            // Re-arm so dragging off the zone and back on vibrates again.
            hasVibratedThisOverlap = false
        }
    }

    /**
     * Grows/shrinks the X circle. The collapsed scale is the bubble's exact diameter
     * over the *expanded* target diameter — the circle is now laid out at its
     * expanded size — so the spec's "shrink to the same size as the floating bubble"
     * holds for any token values instead of a hard-coded 1.0x.
     */
    private fun animateDismissTarget(view: View, collapsed: Boolean) {
        val scale = if (collapsed) dismissCollapsedScale() else DISMISS_SCALE_REST
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(DISMISS_ANIM_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * The laid-out diameter of the X circle, which is also the size of the host
     * window that carries it. Kept in one place so the window, the hit-zone centre
     * and the collapsed scale can never disagree.
     */
    private fun dismissTargetExpandedSize() =
        dismissTargetWindowSize(dimen(R.dimen.dismiss_target_size), DISMISS_SCALE_EXPANDED)

    private fun dismissCollapsedScale() = dismissCollapsedScale(
        bubbleSizePx = dimen(R.dimen.bubble_size),
        expandedSizePx = dismissTargetExpandedSize()
    )

    /** Removes the X target window and clears the overlap/haptic bookkeeping. */
    private fun hideDismissTarget() {
        isOverDismiss = false
        hasVibratedThisOverlap = false
        dismissTarget?.animate()?.cancel()
        dismissTargetWindow?.let { window ->
            runCatching { windowManager.removeView(window) }
        }
        dismissTarget = null
        dismissTargetWindow = null
    }

    /**
     * The "system makes the phone gently vibrate" feedback: one short pulse at
     * default amplitude. [VibratorManager] is the API 31+ entry point; below that
     * the deprecated VIBRATOR_SERVICE is still the only option.
     */
    private fun vibrateGently() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(HAPTIC_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }


    private fun startFloating() {
        if (isFloating) return
        isFloating = true
        animateFloatingStep(-floatOffset())
    }

    private fun animateFloatingStep(targetY: Float) {
        if (!isFloating) return
        bubbleContent.animate()
            .translationY(targetY)
            .setDuration(1200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (isFloating) {
                    val offset = floatOffset()
                    animateFloatingStep(if (targetY < 0) offset else -offset)
                }
            }
            .start()
    }

    private fun stopFloating() {
        isFloating = false
        bubbleContent.animate().cancel()
        bubbleContent.animate().translationY(0f).setDuration(250).start()
    }

    private fun floatOffset() = dimen(R.dimen.bubble_float_offset).toFloat()

    // ── Phase D: double-click scan state machine ──────────────────────────────

    /** Double-click handler — the only bubble gesture defined by spec §3. */
    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    /**
     * Mint bubble + looping ripple, then arm the mode-specific popup timer — unless the
     * hidden "Chế độ nhận diện khuôn mặt" gate is on, in which case the scan parks until
     * a real human face shows up on screen.
     */
    private fun startScan() {
        scanning = true
        rippleDuration = RIPPLE_DURATION_MS
        // §6: "When the bubble becomes active, the current scan mode is copied into the
        // current detection session." One copy, here, and nothing re-reads the preference
        // again until stopScan() clears it — that is the whole lock.
        sessionMode = ProtectionState.getMode(this)
        // UC-02 steps 2-4: a brand-new session has produced no result and holds no face yet,
        // so the Detection status starts at Detecting and the session face starts empty.
        resultDisplayed = false
        sessionFace = null
        publishSessionMode()
        publishDetectionStatus()
        // Read the gate *before* render(): render() picks the content description from
        // awaitingFace, so the bubble has to know it is waiting before it repaints.
        val faceGateOn = ProtectionState.isFaceGateEnabled(this)
        awaitingFace = !shouldArmScanTimer(faceGateOn, participantFaceDetected)
        scanTimerArmed = false
        render()
        if (awaitingFace) {
            // No timer, therefore no popup and no notification, until a face arrives.
            // The bubble stays in its scanning look so the user can see it is working.
            handler.removeCallbacks(scanRunnable)
        } else {
            armScanTimer()
        }
        // A participant face may already be in frame: it was reported while the
        // bubble was idle (and deliberately ignored), and ScreenCaptureService
        // only re-broadcasts when that state *changes*.
        if (shouldApplyFaceDetection(scanning, participantFaceDetected)) {
            // UC-02 step 5 applies to it all the same, so the new session adopts the cached
            // rectangle rather than staying empty until the next change is reported.
            sessionFace = lastReportedFaceBounds
            publishDetectionStatus()
            beginResultSequence()
        }
    }

    /**
     * Returns the bubble to idle: cancels the pending popup *and* the model
     * result sequence, dismisses any displayed panel and drops the ripple, so
     * nothing scheduled by this scan can fire afterwards.
     */
    private fun stopScan() {
        scanning = false
        // A face-gated scan that never saw a face must not leave its "waiting" flag
        // behind, or the next double-tap would start out mislabelled.
        awaitingFace = false
        scanTimerArmed = false
        // §6: the bubble is inactive again, so the session's mode lock is released and the
        // next double tap copies whatever is selected at that point.
        sessionMode = null
        // UC-02 step 10: "the system removes the face from the current session and ends the
        // session." Ending it clears the face it saved and returns the Detection status to Idle.
        resultDisplayed = false
        sessionFace = null
        publishSessionMode()
        publishDetectionStatus()
        handler.removeCallbacks(scanRunnable)
        cancelResultSequence()
        removePanel()
        stopBlink()
        render()
    }

    /**
     * (Re)rolls the popup delay for the mode *this session* was started with, using the
     * roll windows from the hidden developer page (spec defaults when untouched).
     *
     * Only ever called once the face gate has been satisfied — see [shouldArmScanTimer].
     */
    private fun armScanTimer() {
        handler.removeCallbacks(scanRunnable)
        scanTimerArmed = true
        handler.postDelayed(
            scanRunnable,
            ProtectionState.scanDelayMsFor(this, activeSessionMode())
        )
    }

    /**
     * The mode a live session must be judged against (§6).
     *
     * [sessionMode] is authoritative while a scan runs; the preference is only consulted
     * outside one, where [armScanTimer] and [scanRunnable] are unreachable anyway. Falling
     * back rather than throwing keeps a torn-down or oddly-ordered service from crashing on
     * the timer it is about to cancel.
     */
    private fun activeSessionMode(): ScanMode = sessionMode ?: ProtectionState.getMode(this)

    /**
     * Mirrors the session state into the companion so Page 2 can display and lock against it,
     * and announces the change so a Page 2 that is *already open* follows at once.
     *
     * Derived from *both* flags rather than from `scanning` alone. That matters for teardown:
     * onDestroy() clears [sessionMode] before it stops anything, and `stopProtection()` reaches
     * onDestroy() without ever calling stopScan() — so a service dying mid-scan would otherwise
     * leave [FloatingBubbleService.isScanSessionActive] true and Page 2 permanently locked
     * against a session that no longer exists.
     *
     * De-duplicated against [lastPublishedSession] and fire-and-forget inside a runCatching,
     * exactly like [publishBubbleVisibility]: the teardown paths call this more than once, and
     * reporting state must never be able to break the path that called it.
     */
    private fun publishSessionMode() {
        val active = scanning && sessionMode != null
        sessionActiveRef = active
        sessionModeRef = sessionMode
        if (lastPublishedSession == (active to sessionMode)) return
        lastPublishedSession = active to sessionMode
        runCatching {
            sendBroadcast(
                Intent(ACTION_SCAN_SESSION_CHANGED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_SESSION_ACTIVE, active)
                    .putExtra(EXTRA_SESSION_MODE, sessionMode?.name)
            )
        }
    }

    /**
     * Mirrors the UC-02 Detection status into the companion, exactly as [publishSessionMode]
     * mirrors the locked mode.
     *
     * Derived from the live flags rather than stored, so the status a reader sees can never
     * disagree with what the bubble is actually doing. Called wherever a session starts, is
     * torn down, shows its result, or saves a face.
     */
    private fun publishDetectionStatus() {
        detectionStatusRef = detectionStatusOf(scanning, resultDisplayed)
        sessionFaceRef = sessionFace
    }

    /**
     * Releases a face-gated scan: arms the random window as soon as [shouldArmScanTimer]
     * is satisfied, and not before. A no-op for an ungated scan, which is already armed.
     *
     * Called both when a face is reported and when the hidden page turns the gate back
     * off mid-scan, so the gate is re-read from [ProtectionState] rather than assumed.
     */
    private fun releaseFaceGateIfSatisfied() {
        if (!scanning || !awaitingFace) return
        if (!shouldArmScanTimer(ProtectionState.isFaceGateEnabled(this), participantFaceDetected)) {
            return
        }
        awaitingFace = false
        armScanTimer()
        // Repaint so the content description stops saying "waiting for a face".
        render()
    }

    // ── Phase D: scan popups ──────────────────────────────────────────────────

    /**
     * SAFE-mode outcome: a green check on a mint well plus one secondary pill.
     * Built from the same flat-card helpers as the risk panel, so the two
     * overlays are visually identical apart from their content.
     */
    private fun showSafePopup() {
        removePanel()

        val icon = popupIconWell(
            R.drawable.ic_check,
            cRiskSafe,
            cMint100,
            getString(R.string.popup_safe_icon_desc)
        )

        val card = popupCard().apply {
            addView(icon, popupIconParams())
            addView(
                popupMessage(getString(R.string.popup_safe_message)),
                popupTextParams(dimen(R.dimen.space_lg))
            )
            addView(
                singleAction(getString(R.string.popup_btn_close)) { removePanel() },
                popupTextParams(dimen(R.dimen.space_xl))
            )
        }
        showOverlay(card)
    }

    /**
     * WARNING-mode outcome: a terracotta alert glyph pulsing on a loop, with a
     * destructive "Tắt bảo vệ" pill beside "Đóng".
     */
    private fun showWarningPopup() {
        removePanel()

        val icon = popupIconWell(
            R.drawable.ic_alert,
            cTerracotta,
            cTerracottaSoft,
            getString(R.string.popup_warning_icon_desc)
        )

        val card = popupCard().apply {
            addView(icon, popupIconParams())
            addView(
                popupMessage(getString(R.string.popup_warning_message)),
                popupTextParams(dimen(R.dimen.space_lg))
            )
            // actionRow already carries its own top padding.
            addView(
                actionRow(
                    getString(R.string.popup_btn_close),
                    getString(R.string.popup_btn_disable_protection),
                    ::removePanel,
                    ::stopProtection
                ),
                popupTextParams(0)
            )
        }
        showOverlay(card)

        blinkingIcon = icon
        startBlink(icon)
    }

    /** White flat card shell shared by both popups: radius_card + 1dp hairline. */
    private fun popupCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(22), dp(22), dp(22), dp(18))
        background = rounded(cSurface, dp(24), cOutline)
    }

    /** Circular well holding a tinted line glyph — same pattern as the Home status icon. */
    private fun popupIconWell(
        @DrawableRes glyph: Int,
        tint: Int,
        wellColor: Int,
        desc: String
    ) = ImageView(this).apply {
        setImageResource(glyph)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dimen(R.dimen.popup_icon_padding)
        setPadding(pad, pad, pad, pad)
        background = circle(wellColor)
        contentDescription = desc
    }

    private fun popupIconParams() = LinearLayout.LayoutParams(
        dimen(R.dimen.popup_icon_size),
        dimen(R.dimen.popup_icon_size)
    ).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun popupMessage(text: String) = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(cTextPrimary)
        textSize = 15f
        typeface = fontBold
        setLineSpacing(dp(3).toFloat(), 1f)
        this.text = text
    }

    private fun popupTextParams(topMargin: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = topMargin }

    /** Adds a flat overlay card centred on screen and records it as the live panel. */
    private fun showOverlay(card: View) {
        panel = card
        // §8 mirror: Page 1 has to be able to ask whether a pop-up is up without reaching
        // into this service's fields, because its own mode-selection gestures are blocked
        // for exactly as long as one is visible.
        popupAttached = true
        windowManager.addView(
            card,
            overlayParams(
                dp(PANEL_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
                false
            )
        )
    }

    /**
     * Pulses the warning glyph 1f -> [BLINK_MIN_ALPHA] -> 1f on a ~600 ms loop.
     * [isBlinking] guards the withEndAction recursion: a *cancelled*
     * ViewPropertyAnimator never runs its end action, but a completed one always
     * does, so the flag is what actually stops the loop.
     */
    private fun startBlink(view: ImageView) {
        isBlinking = true
        view.alpha = 1f
        blinkOut(view)
    }

    private fun blinkOut(view: ImageView) {
        if (!isBlinking) return
        view.animate()
            .alpha(BLINK_MIN_ALPHA)
            .setDuration(BLINK_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isBlinking) blinkIn(view) }
            .start()
    }

    private fun blinkIn(view: ImageView) {
        if (!isBlinking) return
        view.animate()
            .alpha(1f)
            .setDuration(BLINK_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isBlinking) blinkOut(view) }
            .start()
    }

    private fun stopBlink() {
        isBlinking = false
        blinkingIcon?.let {
            it.animate().cancel()
            it.alpha = 1f
        }
        blinkingIcon = null
    }

    private fun showPanel() {
        removePanel()
        // Flat white card: radius_card, 1dp hairline, no shadow.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(cSurface, dp(24), cOutline)
        }

        // Title: text_primary, Nunito Bold
        val title = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(cTextPrimary)
            textSize = 16f
            typeface = fontBold
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))

        when (current.riskLevel) {
            RiskLevel.NO_RESULT -> {
                title.text = getString(R.string.panel_title)
                val message = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTextColor(cTextSecondary)
                    textSize = 14f
                    typeface = fontMedium
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(10), 0, 0)
                    text = getString(R.string.panel_message_no_result)
                }
                card.addView(message, LinearLayout.LayoutParams(-1, -2))
                card.addView(
                    actionRow(getString(R.string.panel_btn_continue), getString(R.string.panel_btn_stop), ::removePanel, ::stopProtection),
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
            else -> {
                // HIGH_RISK: ic_alert line icon tinted terracotta (no text glyph)
                if (current.riskLevel == RiskLevel.HIGH_RISK) {
                    val alertIcon = ImageView(this).apply {
                        setImageResource(R.drawable.ic_alert)
                        setColorFilter(cTerracotta)
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                            topMargin = dp(4)
                        }
                        contentDescription = getString(R.string.panel_alert_content_desc)
                    }
                    card.addView(alertIcon, LinearLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.CENTER
                    })
                }

                // Score: Display size, Nunito Bold, risk colour
                val score = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTextColor(colorFor(current.riskLevel))
                    textSize = 28f
                    typeface = fontBold
                    setPadding(0, dp(12), 0, 0)
                    text = "${current.score}%"
                }
                card.addView(score, LinearLayout.LayoutParams(-1, -2))

                // Terracotta "badge đánh giá" pill for the risk label
                val badge = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTextColor(cTerracottaDeep)
                    textSize = 11f
                    typeface = fontBold
                    setPadding(dp(14), dp(5), dp(14), dp(5))
                    background = pill(cTerracottaSoft)
                    text = current.riskLevel.label()
                    letterSpacing = 0.06f
                }
                card.addView(badge, LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = dp(10)
                    gravity = Gravity.CENTER
                })

                // Message: text_secondary
                val message = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTextColor(cTextSecondary)
                    textSize = 14f
                    typeface = fontMedium
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(12), 0, 0)
                    text = current.riskLevel.message()
                }
                card.addView(message, LinearLayout.LayoutParams(-1, -2))

                // Action row: secondary + danger
                card.addView(
                    actionRow(getString(R.string.panel_btn_continue), getString(R.string.panel_btn_stop), ::removePanel, ::stopProtection),
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
        }

        showOverlay(card)
    }

    private fun stopProtection() {
        ProtectionState.setActive(this, false)
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopSelf()
    }

    /**
     * Runs the model-result sequence for the scan the user started with a double
     * tap. Only reachable while [scanning] is true: an idle bubble ignores face
     * detection completely (see [shouldApplyFaceDetection]).
     */
    private fun beginResultSequence() {
        if (!monitoringStarted || !scanning || resultSequenceStarted) return

        resultSequenceStarted = true
        // §6: the model-result branch — and the high-risk notification it raises — belongs to
        // the session's locked mode, not to whatever Page 2 happens to say right now.
        if (activeSessionMode() == ScanMode.WARNING) {
            postResult(900) { update(DetectionResult(58, RiskLevel.SUSPICIOUS)) }
            postResult(4_500) {
                update(DetectionResult(84, RiskLevel.HIGH_RISK))
                showHighRiskNotification()
            }
        } else {
            postResult(900) { update(DetectionResult(24, RiskLevel.NORMAL)) }
        }
    }

    /** Schedules a model update and records it so [cancelResultSequence] can retract it. */
    private fun postResult(delayMs: Long, block: () -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                resultRunnables.remove(this)
                block()
            }
        }
        resultRunnables += runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** Drops every pending model update and clears the risk shown on the bubble. */
    private fun cancelResultSequence() {
        resultRunnables.forEach { handler.removeCallbacks(it) }
        resultRunnables.clear()
        resultSequenceStarted = false
        current = DetectionResult(0, RiskLevel.NO_RESULT)
    }

    private fun update(result: DetectionResult) { current = result; render() }

    private fun actionRow(left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
            // Left = secondary (outline pill), Right = danger (terracotta-soft pill)
            addView(button(left, ButtonVariant.SECONDARY, onLeft),
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(button(right, ButtonVariant.DANGER, onRight),
                LinearLayout.LayoutParams(0, dp(46), 1f))
        }

    private fun singleAction(text: String, action: () -> Unit): View =
        button(text, ButtonVariant.SECONDARY, action)

    private enum class ButtonVariant { PRIMARY, SECONDARY, DANGER }

    private fun button(text: String, variant: ButtonVariant, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        typeface = fontMedium
        when (variant) {
            ButtonVariant.PRIMARY -> {
                // mint_500 bg, white text, pill shape — CTA, ≥14sp bold for AA-large
                setTextColor(cTextOnPrimary)
                background = pill(cMint500)
            }
            ButtonVariant.SECONDARY -> {
                // surface bg, text_secondary text, outline stroke, pill shape
                setTextColor(cTextSecondary)
                background = pillOutline(cSurface, cOutline)
            }
            ButtonVariant.DANGER -> {
                // terracotta_soft bg, terracotta_deep text, pill shape
                setTextColor(cTerracottaDeep)
                background = pill(cTerracottaSoft)
            }
        }
        setOnClickListener { action() }
    }

    private fun removePanel() {
        // Any blinking warning glyph lives inside the panel, so stop it first.
        stopBlink()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        popupAttached = false
    }

    private fun showHighRiskNotification() {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setColor(cMint500)
            .setContentTitle(getString(R.string.notif_warning_title))
            .setContentText(getString(R.string.notif_warning_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_warning_big_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WARNING_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(WARNING_CHANNEL, getString(R.string.notif_warning_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /** Overlay window type for this API level — shared by the bubble, panel and X target. */
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun overlayParams(width: Int, height: Int, gravity: Int, notFocusable: Boolean) = WindowManager.LayoutParams(
        width, height,
        overlayWindowType(),
        if (notFocusable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity }

    private fun circle(color: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }
    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke) }
    /** Pill shape: solid colour, radius_pill (999dp), no stroke. */
    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(999).toFloat()
    }
    /** Pill outline: surface bg, radius_pill, 1dp hairline stroke. */
    private fun pillOutline(bg: Int, strokeColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(bg)
        cornerRadius = dp(999).toFloat()
        setStroke(dp(1), strokeColor)
    }
    private fun colorFor(level: RiskLevel) = when (level) {
        RiskLevel.NO_RESULT -> cMint500
        RiskLevel.NORMAL -> cRiskSafe
        RiskLevel.SUSPICIOUS -> cRiskCaution
        RiskLevel.HIGH_RISK -> cRiskDanger
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dimen(@DimenRes id: Int) = resources.getDimensionPixelSize(id)

    /** Horizontal drag bound: keeps the whole bubble on screen. */
    private fun maxX() =
        (resources.displayMetrics.widthPixels - dimen(R.dimen.bubble_container_size)).coerceAtLeast(0)

    /** Vertical drag bound (Gravity.BOTTOM: y grows upwards). */
    private fun maxY() =
        (resources.displayMetrics.heightPixels - dimen(R.dimen.bubble_container_size)).coerceAtLeast(0)

    private fun RiskLevel.label() = when (this) {
        RiskLevel.NO_RESULT -> getString(R.string.risk_no_result)
        RiskLevel.NORMAL -> getString(R.string.risk_normal)
        RiskLevel.SUSPICIOUS -> getString(R.string.risk_suspicious)
        RiskLevel.HIGH_RISK -> getString(R.string.risk_high)
    }
    private fun RiskLevel.message() = when (this) {
        RiskLevel.NORMAL -> getString(R.string.risk_msg_normal)
        RiskLevel.SUSPICIOUS -> getString(R.string.risk_msg_suspicious)
        RiskLevel.HIGH_RISK -> getString(R.string.risk_msg_high)
        RiskLevel.NO_RESULT -> ""
    }

    companion object {
        /**
         * True only while a bubble overlay is genuinely attached to the
         * WindowManager. Deliberately process-local and in-memory: a process kill,
         * a reboot or a force-stop resets it to false, which is what lets the Home
         * screen spot a stale persisted "Đang bảo vệ" instead of repeating it.
         */
        @Volatile
        private var bubbleAttached = false

        /**
         * developer_setting_page.md §6 — the mode the *running* detection session was started
         * with, mirrored out of the instance so Page 2 can display it.
         *
         * Null whenever no session is live. Written only by publishSessionMode(), which is
         * called from startScan(), stopScan() and onDestroy() — all on the main thread, but
         * volatile because Page 2 reads it from its own.
         */
        @Volatile
        private var sessionModeRef: ScanMode? = null

        /**
         * Mirror of the instance's `scanning` flag: true while a detection session is running,
         * which is what §6 means by "the bubble is active".
         */
        @Volatile
        private var sessionActiveRef = false

        /**
         * UC-02 "Detection status" — the status of the running session, mirrored out of the
         * instance so any surface can report Idle / Detecting / Result displayed without
         * reaching into a live Service.
         *
         * Written only by publishDetectionStatus(), on the same main-thread transitions that
         * move the session; volatile because a reader may be on its own thread. Idle is the
         * correct initial value: no session can be running before the service exists.
         */
        @Volatile
        private var detectionStatusRef = DetectionStatus.IDLE

        /**
         * UC-02 step 5 — the face the running session saved, or null when the session never
         * saw one (A3) or has ended and dropped it (step 10). Mirrors `sessionFace`.
         */
        @Volatile
        private var sessionFaceRef: Rect? = null

        /**
         * Mirror of `panel != null` (§8): true while an In-App Pop-up overlay window is up.
         * Page 1 consults this before honouring any mode-selection gesture, because
         * disable_floating_bubble.md already forbids interacting with the bubble in that state.
         */
        @Volatile
        private var popupAttached = false

        /**
         * Mirror of the instance's Component-6 lock gate, held here so
         * [isBubbleVisible] can be read cross-thread. An attached-but-GONE window
         * has not been "successfully rendered", so it must not count as visible.
         */
        @Volatile
        private var bubbleHiddenByLock = false

        /**
         * The last value handed to [ACTION_BUBBLE_VISIBILITY_CHANGED], so the
         * once-a-second watchdog and the repeated applyLockVisibility() assertions do
         * not spam Home with broadcasts that change nothing. Null until the process has
         * published once, which makes the very first announcement unconditional.
         */
        @Volatile
        private var lastPublishedVisibility: Boolean? = null

        /**
         * The last value handed to [ACTION_SCAN_SESSION_CHANGED], for the same reason as
         * [lastPublishedVisibility]: teardown reaches publishSessionMode() more than once
         * (stopScan() and then onDestroy()), and Page 2 must not be woken again for a state it
         * has already painted.
         */
        @Volatile
        private var lastPublishedSession: Pair<Boolean, ScanMode?>? = null

        /**
         * The live service, held *weakly*. Lets the Home screen force the lock gate to
         * be re-derived before it reads [isBubbleVisible] — the definitive self-heal
         * for a process that was frozen with the screen off and therefore never saw
         * the unlock broadcast.
         *
         * Weak on purpose: a Service is a Context, so a strong static reference would
         * leak the whole view tree if onDestroy() were ever skipped. The framework
         * holds a strong reference to a running service anyway, so the weak one stays
         * reachable for exactly as long as the bubble exists and clears itself
         * afterwards. Set in showBubble(), cleared in onDestroy().
         */
        @Volatile
        private var liveInstanceRef: WeakReference<FloatingBubbleService>? = null

        private val liveInstance: FloatingBubbleService?
            get() = liveInstanceRef?.get()

        /** Read-only liveness view of the bubble for the Home screen. */
        val isBubbleVisible: Boolean get() = isBubbleOnScreen(bubbleAttached, bubbleHiddenByLock)

        /**
         * §6 — true while a detection session is running, i.e. while the bubble is "active".
         * Both mode-selection gestures are refused for exactly this long, and Page 2 locks.
         */
        val isScanSessionActive: Boolean get() = sessionActiveRef

        /**
         * §6 — the mode the running session copied at start-up, or null when no session is
         * live. Page 2 shows this instead of the selected mode while [isScanSessionActive].
         */
        val activeSessionMode: ScanMode? get() = sessionModeRef

        /**
         * UC-02 "Detection status" of protection right now: Idle before the first double-click
         * and once a session has ended, Detecting while a session is hunting for a result, and
         * Result displayed once it has shown one — and only a deactivation clears that again,
         * so pressing "Đóng" (A1) leaves it at Result displayed as the postcondition requires.
         */
        val detectionStatus: DetectionStatus get() = detectionStatusRef

        /**
         * UC-02 step 5 — the face the live session is holding, or null when it holds none.
         *
         * A defensive copy, so a caller reads a snapshot of the session's face instead of a
         * handle on the rectangle the service may replace on the next face report.
         */
        val sessionFaceBounds: Rect? get() = sessionFaceRef?.let { Rect(it) }

        /** §8 — true while an In-App Pop-up overlay is on screen. */
        val isPopupOnScreen: Boolean get() = popupAttached

        /**
         * True while the window is attached but deliberately not drawn because the
         * device is locked.
         *
         * This separates "the session is alive and parked behind the keyguard" from
         * "the persisted flag outlived the bubble". Home must only self-heal the
         * stale flag in the second case — wiping it in the first would break the
         * Lifecycle Rule "the bubble becomes visible again *if protection remains
         * enabled*".
         */
        val isHiddenByLockOnly: Boolean get() = bubbleAttached && bubbleHiddenByLock

        /**
         * Re-derives the lock gate from the real keyguard state *and* re-reads the live
         * overlay window, then reports whether the bubble is on screen.
         *
         * MainActivity calls this at the top of syncProtectionState() and on every
         * reconcile tick. Two different failures are corrected in the same pass:
         *  - a window left GONE by an ACTION_USER_PRESENT that never arrived, which would
         *    otherwise keep the badge at "Chưa kích hoạt" on an unlocked device in the
         *    user's hand; and
         *  - a liveness latch left true by a teardown that never reached onDestroy(),
         *    which is how the badge came to claim "Đang bảo vệ - Tắt ứng dụng" over an
         *    empty screen. See [isOverlayWindowAttached].
         */
        fun syncBubbleVisibility(): Boolean {
            liveInstance?.applyLockVisibility()
            return isBubbleVisible
        }

        /**
         * The state the Home card is allowed to display: the persisted switch *and*
         * a bubble really on screen (enable_floating_bubble.md: "The interface
         * shouldn't become 'Đang bảo vệ' if the app's bubble is not displayed").
         */
        fun isProtecting(context: Context): Boolean =
            isProtectionEffective(ProtectionState.isActive(context), isBubbleVisible)

        private const val WARNING_CHANNEL = "high_risk_warning"
        private const val WARNING_NOTIFICATION_ID = 42

        /**
         * Sent every time the bubble's *real* on-screen state changes: attached after
         * a successful addView(), removed on teardown, or hidden/restored for the lock
         * screen.
         *
         * ProtectionState.ACTION_CHANGED alone cannot carry this. It reports the
         * persisted switch, and the switch does not move when the bubble is hidden for
         * the keyguard, reclaimed by the system, or torn down by a path that never
         * reached setActive(). Home listens for this action too so the card follows the
         * bubble rather than waiting for the next onResume — which is exactly the gap
         * that left "Đang bảo vệ - Tắt ứng dụng" on screen over an empty display.
         */
        const val ACTION_BUBBLE_VISIBILITY_CHANGED =
            "com.voiceshield.ai.BUBBLE_VISIBILITY_CHANGED"

        /** Boolean extra on [ACTION_BUBBLE_VISIBILITY_CHANGED]. */
        const val EXTRA_BUBBLE_VISIBLE = "bubble_visible"

        /**
         * Sent every time a detection session starts or ends, i.e. every time the §6 mode lock
         * moves.
         *
         * The companion flags ([isScanSessionActive], [activeSessionMode]) already let Page 2
         * *read* the lock, but reading only happens in onStart()/onCreate — and the bubble is an
         * overlay that floats *over* Page 2, so a double tap there never reaches that page's
         * lifecycle. This is the missing signal: Page 2 stays open and still greys the controls
         * out the moment the session begins, and un-greys them the moment it ends.
         */
        const val ACTION_SCAN_SESSION_CHANGED =
            "com.voiceshield.ai.SCAN_SESSION_CHANGED"

        /** Boolean extra on [ACTION_SCAN_SESSION_CHANGED]: whether a session is running. */
        const val EXTRA_SESSION_ACTIVE = "session_active"

        /**
         * [ScanMode]-name extra on [ACTION_SCAN_SESSION_CHANGED], absent while no session is
         * running. Informational only — listeners re-derive from [activeSessionMode] rather than
         * trusting it.
         */
        const val EXTRA_SESSION_MODE = "session_mode"

        private const val RIPPLE_DURATION_MS = 1_300L
        private const val RIPPLE_DURATION_FAST_MS = 700L
        private const val SNAP_DURATION_MS = 200L
        private const val BLINK_DURATION_MS = 600L
        private const val BLINK_MIN_ALPHA = 0.25f
        private const val PANEL_WIDTH_DP = 310

        /** Component 6 watchdog cadence: one KeyguardManager read per second. */
        private const val LOCK_WATCHDOG_INTERVAL_MS = 1_000L

        /**
         * How long showBubble() waits after a successful addView() before the live View
         * is allowed to correct the liveness latch. A frame is ~16 ms, so this is a wide
         * margin; it only has to outlast the first traversal.
         */
        private const val ATTACH_SETTLE_MS = 500L

        // ── Drag-to-dismiss X target ──
        /**
         * How much larger than `dismiss_target_size` the X circle is at rest. This is a
         * *sizing* factor, not a View scale: the host window and the ImageView are both
         * laid out at this diameter, so the oval is inscribed at scale 1.0 and can never
         * have its extremes clipped off by the window rectangle.
         */
        private const val DISMISS_SCALE_EXPANDED = 1.5f
        /** View scale of the resting circle — it already *is* the expanded size. */
        private const val DISMISS_SCALE_REST = 1.0f
        private const val DISMISS_ANIM_DURATION_MS = 160L
        /** Length of the "gently vibrate" pulse fired when the bubble hits the X. */
        private const val HAPTIC_DURATION_MS = 50L
    }
}
