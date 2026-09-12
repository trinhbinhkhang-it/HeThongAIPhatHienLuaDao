package com.voiceshield.ai

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
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

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
     * True while a scan is running. Drives the bubble visual state:
     * idle = neutral surface + hairline + secondary shield, no ripple;
     * scanning = mint bubble + white shield + looping ripple.
     */
    private var scanning = false
    private var rippleDuration = RIPPLE_DURATION_MS

    /** Guards the blink recursion so stopBlink() can't be raced by an end-action. */
    private var isBlinking = false
    private var blinkingIcon: ImageView? = null

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

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

    // ─ Nunito font (system sans-serif-medium fallback) ──
    private lateinit var fontMedium: Typeface
    private lateinit var fontBold: Typeface

    /**
     * Double-click is the only bubble gesture (spec §3): it toggles the scan.
     * Every touch event is offered to this detector first, so it coexists with
     * the manual vertical-drag handling below.
     */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleScan()
                return true
            }
        })
    }

    /**
     * Fires once per scan and shows the popup for the mode selected on Page 2.
     * Deliberately a named Runnable: stopScan() cancels just this callback,
     * whereas removeCallbacksAndMessages(null) would also drop pending model
     * updates and break the Phase-4 result sequence.
     */
    private val scanRunnable = Runnable {
        if (!scanning) return@Runnable
        when (ProtectionState.getMode(this)) {
            ScanMode.SAFE -> showSafePopup()
            ScanMode.WARNING -> {
                showWarningPopup()
                showHighRiskNotification()
            }
        }
    }

    /** Page 2 can change the mode mid-scan: re-roll the pending window. */
    private val modeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProtectionState.ACTION_MODE_CHANGED) return
            if (scanning) armScanTimer()
        }
    }

    private val faceDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED) return

            val detected = intent.getBooleanExtra(ScreenCaptureService.EXTRA_FACE_DETECTED, false)
            // Always remember the latest state, even while idle: the user may
            // double-tap later and no new broadcast will arrive by then.
            participantFaceDetected = detected
            // Idle bubble -> ignored. Only a double-tap scan consumes results.
            if (shouldApplyFaceDetection(scanning, detected)) beginResultSequence()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The main screen has already enabled protection before it starts this service.
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
        ContextCompat.registerReceiver(
            this,
            modeChangedReceiver,
            IntentFilter(ProtectionState.ACTION_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The scan mode is read straight from ProtectionState — no intent extras.
        monitoringStarted = true
        ProtectionState.setActive(this, true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Cancel the pending scan popup before the blanket handler flush.
        handler.removeCallbacks(scanRunnable)
        stopBlink()
        handler.removeCallbacksAndMessages(null)
        stopFloating()
        stopRipple()
        ProtectionState.setActive(this, false)
        unregisterReceiver(faceDetectionReceiver)
        unregisterReceiver(modeChangedReceiver)
        removePanel()
        runCatching { windowManager.removeView(bubbleContainer) }
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
            // Offer every event to the double-click detector first. A drag can
            // still begin afterwards, so the two gestures coexist.
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
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
                    }
                    if (isDragging) {
                        // Gravity.START: x grows rightwards, so the delta is ADDED.
                        // (Under the old Gravity.END it had to be subtracted.)
                        bubbleLayoutParams.x = (initialX + dx).coerceIn(0, maxX())
                        bubbleLayoutParams.y = (initialY - dy).coerceIn(0, maxY())
                        windowManager.updateViewLayout(bubbleContainer, bubbleLayoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Single tap is intentionally a no-op: spec §3 defines only
                    // the double-click. A finished drag springs back to the edge.
                    if (isDragging) snapToLeftEdge()
                    true
                }
                else -> false
            }
        }

        bubbleContainer.addView(
            bubbleContent,
            FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
        )

        // Pinned to the LEFT edge (AssistiveTouch style); vertically draggable.
        bubbleLayoutParams = overlayParams(
            containerSize,
            containerSize,
            Gravity.BOTTOM or Gravity.START,
            true
        ).apply {
            x = edgeMargin
            y = dimen(R.dimen.bubble_bottom_margin)
        }

        windowManager.addView(bubbleContainer, bubbleLayoutParams)
        render()
    }

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
        bubbleIcon.contentDescription = getString(R.string.bubble_desc_scanning)
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

    /** Springs the bubble back to the left edge, keeping the dragged vertical offset. */
    private fun snapToLeftEdge() {
        val target = dimen(R.dimen.bubble_edge_margin)
        if (bubbleLayoutParams.x == target) return
        ValueAnimator.ofInt(bubbleLayoutParams.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                bubbleLayoutParams.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubbleContainer, bubbleLayoutParams) }
            }
        }.start()
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

    /** Mint bubble + looping ripple, then arm the mode-specific popup timer. */
    private fun startScan() {
        scanning = true
        rippleDuration = RIPPLE_DURATION_MS
        render()
        armScanTimer()
        // A participant face may already be in frame: it was reported while the
        // bubble was idle (and deliberately ignored), and ScreenCaptureService
        // only re-broadcasts when that state *changes*.
        if (shouldApplyFaceDetection(scanning, participantFaceDetected)) beginResultSequence()
    }

    /**
     * Returns the bubble to idle: cancels the pending popup *and* the model
     * result sequence, dismisses any displayed panel and drops the ripple, so
     * nothing scheduled by this scan can fire afterwards.
     */
    private fun stopScan() {
        scanning = false
        handler.removeCallbacks(scanRunnable)
        cancelResultSequence()
        removePanel()
        stopBlink()
        render()
    }

    /** (Re)rolls the popup delay for the mode currently selected on Page 2. */
    private fun armScanTimer() {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, scanDelayMs(ProtectionState.getMode(this)))
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
        if (ProtectionState.getMode(this) == ScanMode.WARNING) {
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

    private fun overlayParams(width: Int, height: Int, gravity: Int, notFocusable: Boolean) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
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
        private const val WARNING_CHANNEL = "high_risk_warning"
        private const val WARNING_NOTIFICATION_ID = 42
        private const val RIPPLE_DURATION_MS = 1_300L
        private const val RIPPLE_DURATION_FAST_MS = 700L
        private const val SNAP_DURATION_MS = 200L
        private const val BLINK_DURATION_MS = 600L
        private const val BLINK_MIN_ALPHA = 0.25f
        private const val PANEL_WIDTH_DP = 310
    }
}
