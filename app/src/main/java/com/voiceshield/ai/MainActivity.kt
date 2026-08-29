package com.voiceshield.ai

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

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

        findViewById<Button>(R.id.view_debug_frames_button).setOnClickListener {
            showDebugFramesDialog()
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

    private fun showDebugFramesDialog() {
        val dir = File(filesDir, "debug_frames")
        val frames = dir.listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (frames.isEmpty()) {
            Toast.makeText(this, "No debug frames saved yet", Toast.LENGTH_SHORT).show()
            return
        }

        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val title = TextView(context).apply {
            text = "Saved debug frames"
            textSize = 18f
            setPadding(0, 0, 0, 12)
        }
        container.addView(title)

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        frames.take(10).forEach { frameFile ->
            val preview = ImageView(context).apply {
                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath)
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    240
                )
                setPadding(0, 8, 0, 8)
            }
            buttonContainer.addView(preview)

            val filename = TextView(context).apply {
                text = frameFile.name
                setPadding(0, 0, 0, 12)
            }
            buttonContainer.addView(filename)
        }

        scroll.addView(buttonContainer)
        container.addView(scroll)

        AlertDialog.Builder(context)
            .setTitle("Private debug frames")
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
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
