package com.voiceshield.ai.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

class AudioStreamController {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    // -------------------------------------------------------------------------
    // 1. Interface Callback gửi kết quả về UI / Activity
    // -------------------------------------------------------------------------
    interface OnAIResultListener {
        fun onAIResult(score: Float)
    }

    private var listener: OnAIResultListener? = null

    fun setOnAIResultListener(listener: OnAIResultListener) {
        this.listener = listener
    }

    // -------------------------------------------------------------------------
    // 2. Khai báo các hàm C++ Native
    // -------------------------------------------------------------------------
    external fun nativeInitBuffer(samples: Int)
    external fun nativeWritePCM(data: ShortArray, size: Int)
    external fun nativeProcessDSP()
    external fun nativeRAMFlush()

    companion object {
        private const val TAG = "AudioStreamController"
        init {
            System.loadLibrary("voiceshield_core")
        }
    }

    // -------------------------------------------------------------------------
    // 3. Hàm JNI Callback (Được C++ Native gọi trực tiếp)
    // -------------------------------------------------------------------------
    fun onNativeAIResult(score: Float) {
        Log.d(TAG, "--> Received AI Score from C++: ${(score * 100).toInt()}%")

        // Chuyển kết quả về Main Thread để cập nhật UI an toàn
        CoroutineScope(Dispatchers.Main).launch {
            listener?.onAIResult(score)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Thu âm & Đẩy dữ liệu xuống C++
    // -------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return

        nativeInitBuffer(32000) // Đệm RAM 2.0s âm thanh
        Log.d(TAG, "--> Initialized Circular RAM Buffer (2.0s)")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        isRecording = true
        audioRecord?.startRecording()
        Log.d(TAG, "--> Started Real-time Audio Streaming...")

        recordingJob = ioScope.launch {
            val pcmChunk = ShortArray(3200) // 200ms chunk
            var frameCounter = 0

            while (isRecording && isActive) {
                val readBytes = audioRecord?.read(pcmChunk, 0, pcmChunk.size) ?: 0
                if (readBytes > 0) {
                    nativeWritePCM(pcmChunk, readBytes)
                    frameCounter++

                    // Cứ sau ~400ms (2 chunks), kích hoạt C++ DSP & AI Inference
                    if (frameCounter >= 2) {
                        nativeProcessDSP()
                        frameCounter = 0
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Dừng thu âm & Dọn dẹp tài nguyên (Không làm lag / khựng UI)
    // -------------------------------------------------------------------------
    fun stopAndClear(onCleared: (() -> Unit)? = null) {
        if (!isRecording) return

        isRecording = false

        // Đưa việc dừng phần cứng & chờ hủy luồng vào Background (IO Thread)
        // để Luồng giao diện (Main Thread) không bị giật lag
        ioScope.launch {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }

            // Chờ Coroutine thu âm dừng hẳn ngầm bên dưới
            recordingJob?.cancelAndJoin()

            // Xoá RAM và giải phóng tài nguyên ở tầng C++
            nativeRAMFlush()
            Log.d(TAG, "--> Stopped Streaming & Flushed RAM successfully!")

            // Trả callback về Luồng chính nếu Activity/Fragment cần cập nhật trạng thái
            withContext(Dispatchers.Main) {
                onCleared?.invoke()
            }
        }
    }
}