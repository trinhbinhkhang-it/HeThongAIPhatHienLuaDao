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
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var pendingConfig = CaptureConfig(1, DEFAULT_WINDOW_SIZE_SECONDS, "average")
    private lateinit var captureStatus: TextView
    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            captureStatus.text = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
                ?: "Capture status unavailable"
        }
    }

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                Log.i(TAG, "Capture permission denied")
                return@registerForActivityResult
            }

            Log.i(TAG, "Capture permission granted")
            startForegroundService(Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SAMPLING_RATE, pendingConfig.samplingRate)
                putExtra(ScreenCaptureService.EXTRA_WINDOW_SIZE_SECONDS, pendingConfig.windowSizeSeconds)
                putExtra(ScreenCaptureService.EXTRA_AGGREGATION, pendingConfig.aggregation)
            })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        captureStatus = findViewById(R.id.capture_status)

        findViewById<Button>(R.id.start_capture_button).setOnClickListener {
            val samplingRate = when (findViewById<Spinner>(R.id.sampling_rate_spinner).selectedItemPosition) {
                1 -> 2
                2 -> 5
                else -> 1
            }
            val windowSizeSeconds = findViewById<EditText>(R.id.window_size_input).text.toString()
                .toIntOrNull()?.coerceIn(1, 60) ?: DEFAULT_WINDOW_SIZE_SECONDS
            val aggregation = findViewById<Spinner>(R.id.aggregation_spinner).selectedItem.toString()
            pendingConfig = CaptureConfig(samplingRate, windowSizeSeconds, aggregation)
            Log.i(TAG, "Capture config | sampling_rate=$samplingRate fps | window_size=${windowSizeSeconds}s | aggregation=$aggregation")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForUserChoice()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            capturePermissionLauncher.launch(captureIntent)
        }
        findViewById<Button>(R.id.stop_capture_button).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            captureStatus.text = "Capture stopped"
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
        const val TAG = "ScreenCapturePOC"
        const val DEFAULT_WINDOW_SIZE_SECONDS = 5
    }

    private data class CaptureConfig(
        val samplingRate: Int,
        val windowSizeSeconds: Int,
        val aggregation: String
    )
}
