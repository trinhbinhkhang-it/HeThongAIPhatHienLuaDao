package com.voiceshield.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

/**
 * Pure gate for the system screen-share consent result: protection may only be
 * enabled when the MediaProjection dialog returned RESULT_OK *with* payload
 * data. Anything else means the user pressed "Cancel" / "Huỷ".
 *
 * Top-level and Android-free (Activity.RESULT_* are inlined compile-time
 * constants) so the rule is JVM-unit-testable.
 */
fun isScreenCaptureGranted(resultCode: Int, hasData: Boolean): Boolean =
    resultCode == Activity.RESULT_OK && hasData

/**
 * Page 1 (Home).
 *
 * A single status card holds the shield well, title, description, state badge
 * and the primary pill CTA that both activates and deactivates protection.
 * The underlined version footer is the entry point to Page 2 (Settings), where
 * the scan mode (Safe / Warning) is configured — reached by the gesture the hidden
 * developer page selects (developer_setting_page.md §1), and also the surface §4's
 * hold-then-swipe mode selection runs on.
 */
class MainActivity : AppCompatActivity() {

    private var isProtecting = false
    /** False until the card has been painted once, so the first pass always draws. */
    private var statePainted = false
    private var waitingForOverlayPermission = false

    /** True between launching the screen-share consent dialog and its result. */
    private var waitingForScreenCapture = false

    private lateinit var protectionButton: MaterialButton
    private lateinit var protectionTitle: TextView
    private lateinit var protectionDescription: TextView
    private lateinit var protectionStatus: TextView
    private lateinit var statusBadge: LinearLayout
    private lateinit var statusBadgeIcon: ImageView
    private lateinit var footerVersion: TextView

    // ── Version footer: developer_setting_page.md §1–§4 ───────────────────────
    /**
     * The footer carries every gesture this spec defines, plus the one that predates it.
     *
     *  - §1 decides how the "Chế độ quét" page is opened: a single click (the default), a
     *    press-and-hold, or a press-and-hold finished with a swipe up.
     *  - §2 decides how long that hold has to last: 0.5 s, 1 s (the default) or 2 s.
     *  - §4, when "Đổi chế độ bằng việc vuốt sang trái hoặc phải" is selected, reuses the same
     *    hold and then reads a left/right swipe as a scan-mode selection.
     *  - A triple tap still opens the hidden developer page, unchanged.
     *
     * Because the single click and the triple tap share one view, a lone tap *defers* its
     * navigation to the end of [TRIPLE_CLICK_WINDOW_MS]: navigating at once would put Page 2
     * underneath taps 2 and 3 and make the hidden page unreachable. That deferral is the price
     * of keeping both gestures, and it applies only to the §1 single-click method — the two
     * hold-based methods are unambiguous and navigate the moment the hold resolves.
     */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var versionTapCount = 0
    private var versionFirstTapAt = 0L
    private var versionDownRawX = 0f
    private var versionDownRawY = 0f
    private var versionDownAt = 0L
    /** True once the finger travelled past the touch slop *before* the hold completed. */
    private var versionHoldBroken = false
    /** True once the configured hold completed; §4's swipe is only honoured after this. */
    private var versionHoldSatisfied = false
    /** True once the hold already navigated, so ACTION_UP cannot navigate a second time. */
    private var versionHoldFired = false

    // The §1–§4 settings, re-read on every ACTION_DOWN so a change made on the hidden page
    // takes effect on the very next gesture without needing a broadcast.
    private var versionEntry = DEFAULT_SCAN_MODE_ENTRY
    private var versionAutoSelection = DEFAULT_AUTO_MODE_SELECTION
    private var versionHoldMs = DEFAULT_HOLD_DURATION.milliseconds
    private var versionSwipeMinPx = 0f

    /**
     * Fires exactly [versionHoldMs] into an unbroken hold on the footer (§2).
     *
     * It always confirms the hold with a haptic, but only navigates when
     * [holdCompletesImmediately] says it may: with a footer swipe armed (§4), or a swipe-up
     * still owed (§1), the decision has to wait for the finger to lift — otherwise the page
     * would open before the swipe that was meant to override it ever happened.
     */
    private val holdThresholdRunnable = Runnable {
        versionHoldSatisfied = true
        if (isFinishing || isDestroyed) return@Runnable
        footerVersion.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (holdCompletesImmediately(versionEntry, versionAutoSelection)) {
            versionHoldFired = true
            openScanModePage()
        }
    }

    /** The deferred §1 single-click navigation; see the note above about the triple tap. */
    private val openScanModeRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        openScanModePage()
    }

    /**
     * Re-derives the card from the bubble's real state twice a second while this screen
     * is visible.
     *
     * Broadcasts alone cannot carry the state. The X-target dismiss normally happens
     * inside another app — which is the whole point of a floating bubble — and by then
     * this activity is stopped, its receivers are unregistered and the broadcast is
     * simply dropped. A process reclaim is worse: nothing is left alive to send one. The
     * tick is what turns "Đang bảo vệ" into a claim the app continuously verifies instead
     * of an event it happened to hear, which is exactly the confusion the spec forbids:
     * "The interface shouldn't become 'Đang bảo vệ' if the app's bubble is not displayed
     * on the screen."
     */
    private val stateReconcileRunnable = object : Runnable {
        override fun run() {
            syncProtectionState()
            uiHandler.postDelayed(this, STATE_RECONCILE_INTERVAL_MS)
        }
    }

    /**
     * Screen-share consent gate — the only path into [enableProtection]. If the
     * user presses "Cancel" nothing has been started yet, so the app simply
     * explains it with a toast: no bubble, the state stays "Chưa kích hoạt" and
     * the CTA keeps reading "Kích hoạt ứng dụng".
     */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!waitingForScreenCapture) return@registerForActivityResult
        waitingForScreenCapture = false

        val data = result.data
        if (!isScreenCaptureGranted(result.resultCode, data != null)) {
            Toast.makeText(this, R.string.screen_share_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        enableProtection(result.resultCode, requireNotNull(data))
    }

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
            if (status != null) {
                Log.d(TAG, "Status update: $status")
            }
        }
    }

    private val protectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Registered for both ACTION_CHANGED (the persisted switch) and
            // ACTION_BUBBLE_VISIBILITY_CHANGED (whether a window is really on screen).
            // Neither extra is trusted on its own: the card may only claim "Đang bảo vệ"
            // while a bubble exists, so every signal re-derives from both.
            syncProtectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        protectionButton = findViewById(R.id.btn_start_protection)
        protectionTitle = findViewById(R.id.protection_title)
        protectionDescription = findViewById(R.id.protection_description)
        protectionStatus = findViewById(R.id.protection_status)
        statusBadge = findViewById(R.id.status_badge)
        statusBadgeIcon = findViewById(R.id.status_badge_icon)
        footerVersion = findViewById(R.id.footer_version)
        // Top-right profile action -> the personal-information page.
        findViewById<View>(R.id.btn_profile).setOnClickListener {
            startActivity(Intent(this, PersonalInfoActivity::class.java))
        }

        protectionButton.setOnClickListener {
            if (isProtecting) stopProtection() else startProtection()
        }
        // The click listener counts taps for the hidden page and owns the §1 single-click
        // navigation; the touch listener below owns every hold and swipe.
        footerVersion.setOnClickListener { registerVersionTap() }
        footerVersion.setOnTouchListener { view, event -> onVersionFooterTouch(view, event) }
        refreshFooterDescription()

        syncProtectionState()
    }

    /**
     * Keeps the footer's accessibility text in step with the §1 opening method, so a
     * screen-reader user is told the gesture that actually works rather than a fixed one.
     */
    private fun refreshFooterDescription() {
        val entry = ProtectionState.getScanModeEntry(this)
        footerVersion.contentDescription = getString(
            when (entry) {
                ScanModeEntry.SINGLE_CLICK -> R.string.version_footer_desc_single_click
                ScanModeEntry.PRESS_AND_HOLD -> R.string.version_footer_desc_press_hold
                ScanModeEntry.PRESS_HOLD_SWIPE_UP ->
                    R.string.version_footer_desc_press_hold_swipe_up
            }
        )
    }

    /**
     * Counts taps on the version footer. Three inside [TRIPLE_CLICK_WINDOW_MS] open the
     * hidden developer page.
     *
     * When §1 is set to single click — the default — a lone tap *also* opens Page 2, but not
     * until the burst window has closed. That deferral is what lets both gestures share one
     * view: navigating on the first tap would put Page 2 under taps 2 and 3 and make the
     * hidden page unreachable. Re-posting on every tap keeps the deadline anchored to the
     * *first* tap, so the burst can never be extended indefinitely.
     */
    private fun registerVersionTap() {
        val now = SystemClock.uptimeMillis()
        if (versionTapCount > 0 && isClickBurstOpen(now - versionFirstTapAt)) {
            versionTapCount++
        } else {
            versionTapCount = 1
            versionFirstTapAt = now
        }

        if (isTripleClick(versionTapCount, now - versionFirstTapAt)) {
            versionTapCount = 0
            // The third tap wins: drop any single-click navigation still pending.
            uiHandler.removeCallbacks(openScanModeRunnable)
            startActivity(Intent(this, HiddenSettingsActivity::class.java))
            return
        }

        if (ProtectionState.getScanModeEntry(this) == ScanModeEntry.SINGLE_CLICK) {
            uiHandler.removeCallbacks(openScanModeRunnable)
            val remaining = (versionFirstTapAt + TRIPLE_CLICK_WINDOW_MS - now).coerceAtLeast(0L)
            uiHandler.postDelayed(openScanModeRunnable, remaining)
        }
    }

    /**
     * The hold-and-swipe half of the footer (§1 options B/C, §2 and §4).
     *
     * ACTION_DOWN snapshots the configured settings and arms the hold for exactly
     * [HoldDuration.milliseconds]. Travelling past the touch slop *before* that threshold
     * voids the hold, so a scroll or a shaky finger cannot open the page or change the mode;
     * travelling *after* it is the swipe §4 asks for, and is measured rather than rejected.
     *
     * ACTION_UP hands everything to [resolveFooterRelease], the one pure function the unit
     * tests pin, and then performs whichever [FooterAction] it returned.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onVersionFooterTouch(view: View, event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Re-read per gesture: the hidden page can change any of these while this
                // screen was stopped, and the next touch must honour the new selection.
                versionEntry = ProtectionState.getScanModeEntry(this)
                versionAutoSelection = ProtectionState.getAutoModeSelection(this)
                versionHoldMs = ProtectionState.holdDurationMs(this)
                versionSwipeMinPx = swipeMinDistancePx(
                    MODE_SWIPE_MIN_DISTANCE_DP,
                    resources.displayMetrics.density
                )
                versionDownRawX = event.rawX
                versionDownRawY = event.rawY
                versionDownAt = SystemClock.uptimeMillis()
                versionHoldBroken = false
                versionHoldSatisfied = false
                versionHoldFired = false
                view.isPressed = true
                uiHandler.removeCallbacks(holdThresholdRunnable)
                uiHandler.postDelayed(holdThresholdRunnable, versionHoldMs)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                // Only movement *before* the threshold voids the hold. Once the hold has
                // completed the finger is expected to travel — that travel is the swipe.
                if (!versionHoldSatisfied && !versionHoldBroken && movedPastTouchSlop(view, event)) {
                    versionHoldBroken = true
                    uiHandler.removeCallbacks(holdThresholdRunnable)
                    view.isPressed = false
                }
                // Keep the gesture once we own it; release it if the hold was voided so
                // the parent can scroll.
                !versionHoldBroken
            }

            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(holdThresholdRunnable)
                view.isPressed = false
                handleFooterRelease(view, event)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(holdThresholdRunnable)
                versionHoldBroken = true
                versionHoldSatisfied = false
                view.isPressed = false
                true
            }

            else -> false
        }

    /** Whether the finger has travelled far enough that this is no longer a hold. */
    private fun movedPastTouchSlop(view: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        return abs(event.rawX - versionDownRawX) > slop ||
            abs(event.rawY - versionDownRawY) > slop
    }

    /**
     * Decides what the released finger meant and carries it out.
     *
     * The hold counts if the threshold callback already fired *or* the release itself was long
     * enough and unbroken — [isVersionLongPress] is the shared rule, fed the configured
     * duration, which is what makes "Releasing before the configured duration does not trigger
     * the action" true for all three §2 options.
     */
    private fun handleFooterRelease(view: View, event: MotionEvent) {
        val heldMs = SystemClock.uptimeMillis() - versionDownAt
        val holdSatisfied = versionHoldSatisfied ||
            isVersionLongPress(heldMs, versionHoldBroken, versionHoldMs)
        val swipe = swipeDirection(
            event.rawX - versionDownRawX,
            event.rawY - versionDownRawY,
            versionSwipeMinPx
        )
        val action = resolveFooterRelease(
            entry = versionEntry,
            autoSelection = versionAutoSelection,
            holdSatisfied = holdSatisfied,
            swipe = swipe,
            // §6's "the bubble is active" == a detection session is running.
            bubbleActive = FloatingBubbleService.isScanSessionActive,
            // §8: nothing is honoured while an In-App Pop-up is up.
            popupVisible = FloatingBubbleService.isPopupOnScreen
        )
        when (action) {
            FooterAction.NONE -> Unit
            // The hold may already have navigated while the finger was down; never do it twice.
            FooterAction.COUNT_TAP -> if (!versionHoldFired) view.performClick()
            FooterAction.OPEN_SCAN_MODE_PAGE -> if (!versionHoldFired) openScanModePage()
            FooterAction.SELECT_MODE_SAFE -> applySelectedMode(ScanMode.SAFE)
            FooterAction.SELECT_MODE_WARNING -> applySelectedMode(ScanMode.WARNING)
        }
    }

    /** Opens the "Chế độ quét" page, dropping any single-click navigation still pending. */
    private fun openScanModePage() {
        uiHandler.removeCallbacks(openScanModeRunnable)
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * §4 — writes the swipe-selected mode and gives the §7 feedback.
     *
     * [resolveFooterRelease] has already established that the gesture was valid and the bubble
     * inactive, so all that is left is the "did it actually change" question
     * [feedbackForModeChange] answers: re-selecting the mode already in force stays silent.
     */
    private fun applySelectedMode(mode: ScanMode) {
        val current = ProtectionState.getMode(this)
        val feedback = feedbackForModeChange(
            current,
            mode,
            ProtectionState.getModeFeedback(this)
        )
        if (isModeChange(current, mode)) ProtectionState.setMode(this, mode)
        announceModeChange(mode, feedback)
    }

    /**
     * §7 mode-change feedback. A toast naming the mode, plus a vibration pulse when that option
     * is selected. Deliberately not a notification — the spec forbids one here, and the warning
     * channel stays reserved for real deepfake alerts.
     */
    private fun announceModeChange(mode: ScanMode, feedback: ModeFeedback) {
        if (!feedbackUsesToast(feedback)) return
        val label = getString(
            if (mode == ScanMode.SAFE) R.string.mode_safe else R.string.mode_warning
        )
        Toast.makeText(this, getString(R.string.mode_changed_feedback, label), Toast.LENGTH_SHORT)
            .show()
        if (feedbackUsesVibration(feedback)) vibrateForModeChange()
    }

    /** The vibration half of [ModeFeedback.TOAST_AND_VIBRATION]; silent when unavailable. */
    private fun vibrateForModeChange() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(MODE_CHANGE_HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    /**
     * Repaints the card from the *effective* protection state — the persisted
     * switch AND-ed with the bubble service's live liveness flag.
     *
     * The persisted flag on its own can outlive the bubble: a process kill, a
     * reboot or a force-stop all leave it set with nothing rendered, which is how
     * the Home screen came to claim "Đang bảo vệ" over an empty screen. A stale
     * flag is also written back to false, so the Lifecycle Rules hold
     * ("Force-stopping the app disables protection and hides the bubble",
     * "Restarting the device disables protection and hides the bubble").
     */
    private fun syncProtectionState() {
        val persisted = ProtectionState.isActive(this)
        // Re-derive the Component-6 lock gate *before* reading the flag. If the
        // process was frozen while the screen was off, the unlock broadcast may never
        // have been delivered and the window would still be GONE — this is what turns
        // the bubble back on in the same pass the badge is painted.
        val bubbleVisible = FloatingBubbleService.syncBubbleVisibility()
        if (persisted && !bubbleVisible && !FloatingBubbleService.isHiddenByLockOnly) {
            // Self-heal: nothing is on screen and nothing is parked behind the
            // keyguard, so protection cannot be in effect. setActive also
            // re-broadcasts, keeping any other listener in step.
            ProtectionState.setActive(this, false)
        }
        updateProtectionState(isProtectionEffective(persisted, bubbleVisible))
    }

    /**
     * CTA + badge state machine.
     * Inactive -> mint pill "Kich hoat ung dung" and a terracotta alert badge.
     * Active   -> terracotta pill "Tat ung dung" and a mint check badge.
     */
    private fun updateProtectionState(active: Boolean) {
        // The reconcile tick calls this every STATE_RECONCILE_INTERVAL_MS and so does
        // every focus change, so an unchanged card must not be repainted: re-assigning
        // backgroundTintList / rippleColor makes MaterialButton rebuild its background and
        // ripple drawables, which would churn twice a second for no visible reason (and
        // could cut a ripple short under the user's finger).
        if (statePainted && isProtecting == active) return
        statePainted = true
        isProtecting = active
        protectionTitle.text = getString(R.string.protection_title)

        if (active) {
            protectionDescription.text = getString(R.string.protection_active_description)
            protectionButton.text = getString(R.string.btn_deactivate_app)
            protectionButton.backgroundTintList = colorStateList(R.color.terracotta_soft)
            protectionButton.setTextColor(color(R.color.terracotta_deep))
            protectionButton.rippleColor = colorStateList(R.color.terracotta)

            protectionStatus.text = getString(R.string.status_active)
            statusBadge.setBackgroundResource(R.drawable.bg_badge_mint)
            statusBadgeIcon.setImageResource(R.drawable.ic_check)
            statusBadgeIcon.imageTintList = colorStateList(R.color.mint_600)
            protectionStatus.setTextColor(color(R.color.ink_on_mint))
        } else {
            protectionDescription.text = getString(R.string.protection_ready_description)
            protectionButton.text = getString(R.string.btn_activate_app)
            protectionButton.backgroundTintList = colorStateList(R.color.mint_500)
            protectionButton.setTextColor(color(R.color.text_on_primary))
            protectionButton.rippleColor = colorStateList(R.color.mint_600)

            protectionStatus.text = getString(R.string.status_inactive)
            statusBadge.setBackgroundResource(R.drawable.bg_badge_terracotta)
            statusBadgeIcon.setImageResource(R.drawable.ic_alert)
            statusBadgeIcon.imageTintList = colorStateList(R.color.terracotta_deep)
            protectionStatus.setTextColor(color(R.color.terracotta_deep))
        }
    }

    /**
     * Activation is consent-first: the overlay permission and then the system
     * screen-share dialog are both settled *before* anything is switched on, so
     * pressing "Cancel" leaves the app exactly as it was.
     * pressing Canclen  leaves the app excatly as it was
     *
     */
    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        when (ProtectionState.getCaptureBackend(this)) {
            CaptureBackend.ACCESSIBILITY -> startAccessibilityProtection()
            CaptureBackend.MEDIA_PROJECTION -> requestScreenCapture()
        }
    }

    /**
     * Accessibility-backend activation: no projection consent is involved. It gates on the app's
     * accessibility service being enabled, and when it is not, sends the user straight to the
     * Accessibility settings screen rather than failing silently.
     */
    private fun startAccessibilityProtection() {
        if (!isScreenshotAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, R.string.accessibility_not_enabled, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        startFloatingBubble()
        startAccessibilityScreenCapture()
    }

    /** Starts the capture service with no projection token; it pulls accessibility screenshots. */
    private fun startAccessibilityScreenCapture() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenCaptureService::class.java)
        )
    }

    /**
     * Runs only once the user has granted screen sharing; see [screenCaptureLauncher].
     *
     * The status flip to "Đang bảo vệ" is deliberately NOT done here. Per
     * enable_floating_bubble.md the app "must not display 'Đang bảo vệ' unless the
     * bubble has been successfully rendered", so [FloatingBubbleService] flips
     * [ProtectionState] only after its overlay is really on screen and
     * [protectionStateReceiver] repaints the card from that broadcast.
     */
    private fun enableProtection(resultCode: Int, resultData: Intent) {
        requestNotificationPermission()
        startFloatingBubble()
        startScreenCapture(resultCode, resultData)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private fun stopProtection() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, FloatingBubbleService::class.java))
        ProtectionState.setActive(this, false)
        updateProtectionState(false)
    }

    /** The scan mode travels through [ProtectionState], not through intent extras. */
    private fun startFloatingBubble() {
        startService(Intent(this, FloatingBubbleService::class.java))
    }

    /** Hands the granted projection token to the capture service. */
    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
        ContextCompat.startForegroundService(this, captureIntent)
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        waitingForScreenCapture = true
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            captureStatusReceiver,
            IntentFilter(ScreenCaptureService.ACTION_CAPTURE_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Both actions, because either one alone can be wrong: the persisted switch
        // moves without the bubble appearing or disappearing, and the bubble appears or
        // disappears (lock screen, X target, a reclaimed overlay) without the switch
        // moving.
        ContextCompat.registerReceiver(
            this,
            protectionStateReceiver,
            IntentFilter().apply {
                addAction(ProtectionState.ACTION_CHANGED)
                addAction(FloatingBubbleService.ACTION_BUBBLE_VISIBILITY_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Start reconciling immediately rather than after the first interval, so a state
        // change that happened while this screen was stopped is painted on the very next
        // frame instead of up to STATE_RECONCILE_INTERVAL_MS later.
        uiHandler.removeCallbacks(stateReconcileRunnable)
        stateReconcileRunnable.run()
    }

    override fun onStop() {
        unregisterReceiver(captureStatusReceiver)
        unregisterReceiver(protectionStateReceiver)
        // Stop reconciling and drop a still-pending hold navigation: the user has left
        // the screen, so Page 2 must not open behind whatever they went to.
        uiHandler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from Recents, the notification shade or the screen-share dialog is
        // exactly when a bubble dismissed elsewhere has to show up on the card straight
        // away — no lifecycle callback fires for those, so focus is the signal.
        if (hasFocus) syncProtectionState()
    }

    override fun onResume() {
        super.onResume()
        // Component 6: the user is looking at this screen, so the device is unlocked.
        // Force the lock gate to be re-derived first — after a screen-off the process
        // may have been frozen and never received ACTION_USER_PRESENT, leaving the
        // bubble GONE while protection is still enabled.
        FloatingBubbleService.syncBubbleVisibility()
        // The hidden page may have changed the §1 opening method while this screen was
        // stopped; the accessibility hint has to describe the gesture that works *now*.
        refreshFooterDescription()
        // granting_permissions.md: "If a previously granted permission is revoked,
        // the system disables the affected feature and asks the user to grant the
        // permission again." Without the overlay permission no bubble can exist, so
        // an ongoing protection session is no longer real.
        if (FloatingBubbleService.isProtecting(this) && !Settings.canDrawOverlays(this)) {
            stopProtection()
            Toast.makeText(this, R.string.overlay_permission_revoked, Toast.LENGTH_LONG).show()
        }
        syncProtectionState()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            // Overlay granted -> continue with the screen-share consent dialog.
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
        }
    }

    override fun onDestroy() {
        // Never leave a pending hold navigation or reconcile tick queued against a dead
        // activity.
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun colorStateList(@ColorRes id: Int) = ColorStateList.valueOf(color(id))

    private companion object {
        const val TAG = "SafeCallsCTMainActivity"
        const val NOTIFICATION_PERMISSION_REQUEST = 10

        /**
         * How often the Home card is re-derived from the bubble's real state while this
         * screen is visible. Half a second is well under any delay a user would notice
         * and cheap enough to leave running: one SharedPreferences read plus one
         * KeyguardManager read per tick.
         */
        const val STATE_RECONCILE_INTERVAL_MS = 500L

        /**
         * Length of the §7 confirmation pulse for [ModeFeedback.TOAST_AND_VIBRATION]. Matches
         * the bubble's own haptic so a mode change feels the same wherever it was triggered.
         */
        const val MODE_CHANGE_HAPTIC_MS = 50L
    }
}
