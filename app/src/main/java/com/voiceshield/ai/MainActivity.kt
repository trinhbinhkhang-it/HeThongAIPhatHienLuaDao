package com.voiceshield.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var isProtecting = false
    private lateinit var protectionButton: MaterialButton
    private lateinit var protectionTitle: TextView
    private lateinit var protectionDescription: TextView
    private lateinit var mediumLevelButton: MaterialButton
    private lateinit var highLevelButton: MaterialButton
    private var useHighProtection = false
    private var waitingForOverlayPermission = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isProtecting || result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult

        val captureIntent = Intent(this, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
        ContextCompat.startForegroundService(this, captureIntent)
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

        protectionButton = findViewById(R.id.protection_button)
        protectionTitle = findViewById(R.id.protection_title)
        protectionDescription = findViewById(R.id.protection_description)
        mediumLevelButton = findViewById(R.id.protection_level_medium)
        highLevelButton = findViewById(R.id.protection_level_high)

        protectionButton.setOnClickListener {
            if (!isProtecting) {
                startProtection()
            } else {
                stopProtection()
            }
        }
        mediumLevelButton.setOnClickListener { selectProtectionLevel(false) }
        highLevelButton.setOnClickListener { selectProtectionLevel(true) }
        selectProtectionLevel(false)
        updateProtectionState(ProtectionState.isActive(this))
    }

    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        enableProtection()
    }

    private fun enableProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        ProtectionState.setActive(this, true)
        updateProtectionState(true)
        startFloatingBubble()
        requestScreenCapture()
    }

    private fun stopProtection() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, FloatingBubbleService::class.java))
        ProtectionState.setActive(this, false)
        updateProtectionState(false)
    }

    private fun startFloatingBubble() {
        val intent = Intent(this, FloatingBubbleService::class.java)
            .putExtra(FloatingBubbleService.EXTRA_HIGH_PROTECTION, useHighProtection)
        startService(intent)
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun selectProtectionLevel(high: Boolean) {
        useHighProtection = high
        val selected = "#1D4ED8"
        val unselected = "#1A2438"
        mediumLevelButton.setBackgroundColor(android.graphics.Color.parseColor(if (high) unselected else selected))
        highLevelButton.setBackgroundColor(android.graphics.Color.parseColor(if (high) selected else unselected))
    }

    private fun updateProtectionState(active: Boolean) {
        isProtecting = active
        if (active) {
            protectionTitle.text = "Bảo vệ cuộc gọi"
            protectionDescription.text = "Bubble bảo vệ đang hoạt động trên các ứng dụng khác."
            protectionButton.text = "Tắt bảo vệ"
        } else {
            protectionTitle.text = "Bảo vệ cuộc gọi"
            protectionDescription.text = "Bật bảo vệ để DeepCheck sẵn sàng hỗ trợ bạn khi đang gọi điện."
            protectionButton.text = "Bật bảo vệ"
        }
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
            if (Settings.canDrawOverlays(this)) enableProtection()
        }
    }

    private companion object {
        const val TAG = "DeepCheckMainActivity"
        const val NOTIFICATION_PERMISSION_REQUEST = 10
    }
}
