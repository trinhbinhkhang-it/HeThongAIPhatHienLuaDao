package com.voiceshield.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var isProtecting = false
    private lateinit var protectionButton: MaterialButton
    private lateinit var protectionTitle: TextView
    private lateinit var protectionDescription: TextView

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
            if (status != null) {
                Log.d(TAG, "Status update: $status")
            }
        }
    }

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                Log.i(TAG, "Capture permission denied")
                updateProtectionState(false)
                return@registerForActivityResult
            }

            Log.i(TAG, "Capture permission granted")
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SAMPLING_RATE, 1)
                putExtra(ScreenCaptureService.EXTRA_WINDOW_SIZE_SECONDS, 5)
                putExtra(ScreenCaptureService.EXTRA_AGGREGATION, "average")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateProtectionState(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        protectionButton = findViewById(R.id.protection_button)
        protectionTitle = findViewById(R.id.protection_title)
        protectionDescription = findViewById(R.id.protection_description)

        protectionButton.setOnClickListener {
            if (!isProtecting) {
                startProtection()
            } else {
                stopProtection()
            }
        }
    }

    private fun startProtection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        capturePermissionLauncher.launch(captureIntent)
    }

    private fun stopProtection() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        updateProtectionState(false)
    }

    private fun updateProtectionState(active: Boolean) {
        isProtecting = active
        if (active) {
            protectionTitle.text = "Đang giám sát nền"
            protectionDescription.text = "Hệ thống đang chủ động bảo vệ và phân tích cuộc gọi video Zalo."
            protectionButton.text = "Dừng bảo vệ"
        } else {
            protectionTitle.text = "Bảo vệ đã sẵn sàng"
            protectionDescription.text = "Bật bảo vệ để DeepCheck sẵn sàng hỗ trợ bạn khi đang gọi điện."
            protectionButton.text = "Bắt đầu bảo vệ"
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
    }

    override fun onStop() {
        unregisterReceiver(captureStatusReceiver)
        super.onStop()
    }

    private companion object {
        const val TAG = "DeepCheckMainActivity"
    }
}

