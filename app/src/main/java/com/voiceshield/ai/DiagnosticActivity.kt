package com.voiceshield.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voiceshield.ai.audio.DiagnosticService

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var displayLayout: LinearLayout
    private val infoViews = mutableMapOf<String, TextView>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("DiagnosticActivity", "--> Received broadcast: ${intent?.action}")
            if (intent?.action == DiagnosticService.ACTION_UPDATE) {
                updateUI(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        displayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(displayLayout)
        setContentView(root)

        addInfoRow("CALL STATE", "UNKNOWN")
        addInfoRow("AUDIO MODE", "UNKNOWN")
        addInfoRow("SPEAKER", "UNKNOWN")
        addInfoRow("RECORD_AUDIO", "UNKNOWN")
        addInfoRow("AudioRecord", "UNKNOWN")
        addInfoRow("SOURCE", "VOICE_COMMUNICATION")
        addInfoRow("Capture", "UNKNOWN")
        addInfoRow("PCM", "UNKNOWN")
        addInfoRow("OTHER APPS", "0")
        addInfoRow("RMS", "0.0")
        addInfoRow("PEAK", "0.0")
        addInfoRow("API LEVEL", Build.VERSION.SDK_INT.toString())
        addInfoRow("DEVICE TYPE", "N/A")

        val btnStart = Button(this).apply {
            text = "START DIAGNOSTIC SERVICE"
            setOnClickListener { 
                if (checkPermissionsForStart()) {
                    startDiagnostic() 
                }
            }
        }
        displayLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "STOP DIAGNOSTIC SERVICE"
            setOnClickListener { stopService(Intent(this@DiagnosticActivity, DiagnosticService::class.java)) }
        }
        displayLayout.addView(btnStop)

        val btnRestart = Button(this).apply {
            text = "RESTART AUDIO (TRY RE-ACQUIRE)"
            setOnClickListener {
                stopService(Intent(this@DiagnosticActivity, DiagnosticService::class.java))
                mainHandler.postDelayed({ startDiagnostic() }, 500)
            }
        }
        displayLayout.addView(btnRestart)

        checkPermissions()
    }

    private fun addInfoRow(label: String, initialValue: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val labelView = TextView(this).apply {
            text = "$label: "
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        val valueView = TextView(this).apply {
            text = initialValue
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.BLUE)
            textSize = 16f
        }
        row.addView(labelView)
        row.addView(valueView)
        displayLayout.addView(row)
        infoViews[label] = valueView
    }

    private fun checkPermissionsForStart(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            false
        } else {
            infoViews["RECORD_AUDIO"]?.text = "GRANTED"
            true
        }
    }

    private fun checkPermissions() {
        checkPermissionsForStart()
    }

    private fun startDiagnostic() {
        val intent = Intent(this, DiagnosticService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun updateUI(intent: Intent) {
        val audioMode = intent.getIntExtra("AUDIO_MODE", -1)
        val modeText = when (audioMode) {
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            else -> "UNKNOWN ($audioMode)"
        }
        
        val isSilenced = intent.getBooleanExtra("IS_SILENCED", false)
        val rms = intent.getDoubleExtra("RMS", 0.0)
        val peak = intent.getDoubleExtra("PEAK", 0.0)
        val isInitialized = intent.getBooleanExtra("INITIALIZED", false)
        val speakerOn = intent.getBooleanExtra("SPEAKER_ON", false)
        val callState = intent.getStringExtra("CALL_STATE") ?: "UNKNOWN"
        val otherApps = intent.getIntExtra("OTHER_APPS_COUNT", 0)

        infoViews["CALL STATE"]?.text = callState
        infoViews["AUDIO MODE"]?.text = modeText
        infoViews["SPEAKER"]?.text = if (speakerOn) "ON" else "OFF"
        infoViews["AudioRecord"]?.text = if (isInitialized) "INITIALIZED" else "FAILED"
        infoViews["Capture"]?.text = if (isSilenced) "SILENCED (Muted by System)" else "ACTIVE"
        infoViews["PCM"]?.text = if (rms > 1.0) "RECEIVING" else "SILENCE"
        infoViews["OTHER APPS"]?.text = otherApps.toString()
        infoViews["RMS"]?.text = String.format("%.2f", rms)
        infoViews["PEAK"]?.text = String.format("%.2f", peak)
        
        if (isSilenced) {
            infoViews["Capture"]?.setTextColor(Color.RED)
        } else {
            infoViews["Capture"]?.setTextColor(Color.GREEN)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DiagnosticService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            infoViews["RECORD_AUDIO"]?.text = if (granted) "GRANTED" else "DENIED"
        }
    }
}
