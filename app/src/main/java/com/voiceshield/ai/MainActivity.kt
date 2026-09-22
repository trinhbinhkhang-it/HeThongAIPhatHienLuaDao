package com.voiceshield.ai

import android.Manifest
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

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
 * the scan mode (Safe / Warning) is configured and persisted.
 */
class MainActivity : AppCompatActivity() {

    private var isProtecting = false
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

    // ── Hidden developer page: triple-tap the version footer ──────────────────
    /**
     * A single tap on the version footer opens Page 2 (Settings), so the ordinary
     * navigation has to be *deferred* until the triple-tap window closes: otherwise
     * tap 1 covers the footer with another activity and taps 2 and 3 can never land
     * on it. The deadline is absolute rather than pushed out by every tap, so a slow
     * burst cannot postpone Settings indefinitely.
     */
    private val versionTapHandler = Handler(Looper.getMainLooper())
    private var versionTapCount = 0
    private var versionFirstTapAt = 0L
    private val openSettingsRunnable = Runnable {
        versionTapCount = 0
        // Never navigate from a finishing or backgrounded activity: the deferred
        // single-tap can otherwise land after the user has already left.
        if (isFinishing || isDestroyed) return@Runnable
        startActivity(Intent(this, SettingsActivity::class.java))
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
            // The broadcast reports the persisted switch; the card may only claim
            // "Đang bảo vệ" while a bubble is really on screen, so re-derive from
            // both instead of trusting the extra alone.
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
        // The footer version is the link into Page 2 (Settings) on a single tap, and
        // into the hidden developer page on a triple tap.
        footerVersion.setOnClickListener { registerVersionTap() }

        syncProtectionState()
    }

    /**
     * Counts taps on the version footer. Three inside [TRIPLE_CLICK_WINDOW_MS] open
     * the hidden developer page; anything fewer falls through to Page 2 (Settings)
     * once the window closes.
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
            versionTapHandler.removeCallbacks(openSettingsRunnable)
            versionTapCount = 0
            startActivity(Intent(this, HiddenSettingsActivity::class.java))
            return
        }
        versionTapHandler.removeCallbacks(openSettingsRunnable)
        versionTapHandler.postAtTime(
            openSettingsRunnable,
            versionFirstTapAt + TRIPLE_CLICK_WINDOW_MS
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
        ContextCompat.registerReceiver(
            this,
            protectionStateReceiver,
            IntentFilter(ProtectionState.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(captureStatusReceiver)
        unregisterReceiver(protectionStateReceiver)
        // Drop a still-pending single-tap navigation: the user has left the screen,
        // so Page 2 must not open behind whatever they went to.
        versionTapHandler.removeCallbacks(openSettingsRunnable)
        super.onStop()
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
        // Never leave a deferred Settings navigation queued against a dead activity.
        versionTapHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun colorStateList(@ColorRes id: Int) = ColorStateList.valueOf(color(id))

    private companion object {
        const val TAG = "SafeCallsCTMainActivity"
        const val NOTIFICATION_PERMISSION_REQUEST = 10
    }
}
