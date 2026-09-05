package com.voiceshield.ai.audio

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.sqrt

class DiagnosticService : Service() {

    companion object {
        const val CHANNEL_ID = "DiagnosticServiceChannel"
        const val ACTION_UPDATE = "com.voiceshield.ai.DIAGNOSTIC_UPDATE"
        private const val NOTIF_ID = 1
        private const val TAG = "DiagnosticService"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var isSilenced = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "--> DiagnosticService onStartCommand")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepCheck Diagnostic")
            .setContentText("Monitoring Audio Capability...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(NOTIF_ID, notification)
        startDiagnostic()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startDiagnostic() {
        Log.d(TAG, "--> startDiagnostic starting...")
        if (isRecording) return

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        Log.d(TAG, "--> AudioRecord (VOICE_COMMUNICATION) state: ${audioRecord?.state}")

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "--> AudioRecord FAILED TO INITIALIZE")
            broadcastUpdate("AudioRecord: FAILED_INIT")
            return
        }

        // Android 10+ Recording Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioRecord?.registerAudioRecordingCallback(
                { runnable -> mainHandler.post(runnable) },
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        val currentConfig = configs.find { it.clientAudioSessionId == audioRecord?.audioSessionId }
                        isSilenced = currentConfig?.isClientSilenced ?: false
                        
                        // Check for other apps
                        val otherAppsCount = configs.size - 1
                        val intent = Intent(ACTION_UPDATE).apply {
                            setPackage(packageName)
                            putExtra("OTHER_APPS_COUNT", otherAppsCount)
                        }
                        sendBroadcast(intent)
                    }
                }
            )
        }

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = serviceScope.launch {
            val buffer = ShortArray(1600) // 100ms
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    processAudio(buffer, read)
                } else if (read < 0) {
                    Log.e(TAG, "--> AudioRecord read error: $read")
                }
                delay(100)
                sendDiagnosticBroadcast()
            }
        }
    }

    private var currentRms = 0.0
    private var currentPeak = 0.0

    private fun processAudio(buffer: ShortArray, size: Int) {
        var sum = 0.0
        var maxAbs = 0
        for (i in 0 until size) {
            val sample = buffer[i].toInt()
            sum += (sample * sample).toDouble()
            maxAbs = maxOf(maxAbs, abs(sample))
        }
        currentRms = sqrt(sum / size)
        currentPeak = maxAbs.toDouble()
    }

    private fun sendDiagnosticBroadcast() {
        Log.d(TAG, "--> Sending diagnostic broadcast: RMS=$currentRms")
        val intent = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra("AUDIO_MODE", audioManager.mode)
            putExtra("SPEAKER_ON", audioManager.isSpeakerphoneOn)
            putExtra("IS_SILENCED", isSilenced)
            putExtra("RMS", currentRms)
            putExtra("PEAK", currentPeak)
            putExtra("INITIALIZED", audioRecord?.state == AudioRecord.STATE_INITIALIZED)
            
            // Check other recording configs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val configs = audioManager.activeRecordingConfigurations
                putExtra("OTHER_APPS_COUNT", configs.size - 1)
            }

            val callState = if (audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) "ACTIVE" else "IDLE"
            putExtra("CALL_STATE", callState)
        }
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(msg: String) {
        val intent = Intent(ACTION_UPDATE).apply {
            putExtra("ERROR", msg)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Diagnostic Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
