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
            updateProtectionState(intent.getBooleanExtra(ProtectionState.EXTRA_ACTIVE, false))
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
        // The footer version doubles as the link into Page 2 (Settings).
        footerVersion.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateProtectionState(ProtectionState.isActive(this))
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

    /** Runs only once the user has granted screen sharing; see [screenCaptureLauncher]. */
    private fun enableProtection(resultCode: Int, resultData: Intent) {
        requestNotificationPermission()
        ProtectionState.setActive(this, true)
        updateProtectionState(true)
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
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateProtectionState(ProtectionState.isActive(this))
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            // Overlay granted -> continue with the screen-share consent dialog.
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
        }
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun colorStateList(@ColorRes id: Int) = ColorStateList.valueOf(color(id))

    private companion object {
        const val TAG = "ShieldCallMainActivity"
        const val NOTIFICATION_PERMISSION_REQUEST = 10
    }
}
