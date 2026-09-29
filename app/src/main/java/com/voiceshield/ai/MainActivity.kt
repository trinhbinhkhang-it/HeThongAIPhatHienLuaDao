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
 * the scan mode (Safe / Warning) is configured — reached by pressing and holding
 * it for [VERSION_LONG_PRESS_MS].
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

    // ── Version footer: 2 s hold -> Page 2, triple tap -> hidden dev page ─────
    /**
     * The footer has two deliberate gestures and no accidental one.
     *
     * A press-and-hold of [VERSION_LONG_PRESS_MS] opens Page 2 (Settings); a plain tap
     * now does nothing at all, which is the point — the hold keeps the scan-mode page off
     * the accidental path. A triple tap still opens the hidden developer page.
     *
     * Because a tap no longer navigates, the old trick of *deferring* the single-tap
     * navigation until the triple-tap window closed is gone with it: taps 2 and 3 now land
     * on a footer that is still on screen, so nothing has to be postponed.
     */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var versionTapCount = 0
    private var versionFirstTapAt = 0L
    private var versionDownRawX = 0f
    private var versionDownRawY = 0f
    private var versionDownAt = 0L
    /** True once the finger travelled past the touch slop, which voids the hold. */
    private var versionHoldBroken = false
    /** True once the hold completed, so ACTION_UP cannot navigate a second time. */
    private var versionLongPressFired = false

    /** Fires exactly [VERSION_LONG_PRESS_MS] into an unbroken hold on the footer. */
    private val openSettingsRunnable = Runnable {
        versionLongPressFired = true
        // Never navigate from a finishing or backgrounded activity: the hold can
        // otherwise complete after the user has already left the screen.
        if (isFinishing || isDestroyed) return@Runnable
        footerVersion.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startActivity(Intent(this, SettingsActivity::class.java))
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

        protectionButton.setOnClickListener {
            if (isProtecting) stopProtection() else startProtection()
        }
        // A plain tap only counts towards the hidden-page triple tap; Page 2 (Settings)
        // needs a two-second hold, handled by the touch listener below.
        footerVersion.setOnClickListener { registerVersionTap() }
        footerVersion.setOnTouchListener { view, event -> onVersionFooterTouch(view, event) }
        footerVersion.contentDescription = getString(R.string.version_footer_desc)

        syncProtectionState()
    }

    /**
     * Counts taps on the version footer. Three inside [TRIPLE_CLICK_WINDOW_MS] open the
     * hidden developer page. A tap no longer navigates anywhere on its own — Page 2 is
     * reached by the two-second hold — so there is nothing left to defer and no way for
     * the footer to be covered between taps.
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
            uiHandler.removeCallbacks(openSettingsRunnable)
            startActivity(Intent(this, HiddenSettingsActivity::class.java))
        }
    }

    /**
     * The press-and-hold that opens Page 2 (Settings).
     *
     * The hold is armed on ACTION_DOWN for exactly [VERSION_LONG_PRESS_MS]. Travelling
     * past the touch slop voids it, so a scroll or a shaky finger cannot open Settings;
     * [isVersionLongPress] is the single source of truth for whether the hold counted and
     * is what the unit tests pin. ACTION_UP either completes the hold (when the posted
     * callback was somehow still pending) or hands the gesture to the click listener,
     * which only counts triple taps.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onVersionFooterTouch(view: View, event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                versionDownRawX = event.rawX
                versionDownRawY = event.rawY
                versionDownAt = SystemClock.uptimeMillis()
                versionHoldBroken = false
                versionLongPressFired = false
                view.isPressed = true
                uiHandler.removeCallbacks(openSettingsRunnable)
                uiHandler.postDelayed(openSettingsRunnable, VERSION_LONG_PRESS_MS)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!versionHoldBroken && movedPastTouchSlop(view, event)) {
                    versionHoldBroken = true
                    uiHandler.removeCallbacks(openSettingsRunnable)
                    view.isPressed = false
                }
                // Keep the gesture once we own it; release it if the hold was voided so
                // the parent can scroll.
                !versionHoldBroken
            }

            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(openSettingsRunnable)
                view.isPressed = false
                val heldMs = SystemClock.uptimeMillis() - versionDownAt
                when {
                    // The hold already completed while the finger was still down, and it
                    // has navigated — do not navigate again and do not count a tap.
                    versionLongPressFired -> Unit
                    isVersionLongPress(heldMs, versionHoldBroken) -> openSettingsRunnable.run()
                    else -> view.performClick()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(openSettingsRunnable)
                versionHoldBroken = true
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
        requestScreenCapture()
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
    }
}
