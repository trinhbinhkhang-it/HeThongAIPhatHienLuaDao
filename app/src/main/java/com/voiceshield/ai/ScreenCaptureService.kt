package com.voiceshield.ai

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastFrameLogTimeMs = 0L
    private var lastFrameSaveTimeMs = 0L
    private var lastSampleTimeMs = 0L
    private var windowStartedAtMs = 0L
    private var samplesInWindow = 0
    private var samplingRate = DEFAULT_SAMPLING_RATE
    private var windowSizeMs = DEFAULT_WINDOW_SIZE_SECONDS * 1_000L
    private var aggregation = DEFAULT_AGGREGATION
    @Volatile private var isFaceDetectionRunning = false
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var faceDetector: FaceDetector

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.projectionData() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        publishStatus("Capture starting")
        samplingRate = intent.getIntExtra(EXTRA_SAMPLING_RATE, DEFAULT_SAMPLING_RATE).coerceIn(1, 10)
        windowSizeMs = intent.getIntExtra(EXTRA_WINDOW_SIZE_SECONDS, DEFAULT_WINDOW_SIZE_SECONDS)
            .coerceIn(1, 60) * 1_000L
        aggregation = intent.getStringExtra(EXTRA_AGGREGATION) ?: DEFAULT_AGGREGATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, null)
        }
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ availableReader ->
                availableReader.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSampleTimeMs < 1_000L / samplingRate) return@use
                    lastSampleTimeMs = now
                    if (windowStartedAtMs == 0L) windowStartedAtMs = now
                    samplesInWindow++
                    detectFaces(image.toBitmap())
                    if (now - lastFrameLogTimeMs >= FRAME_LOG_INTERVAL_MS) {
                        Log.i(TAG, "Frame received | Width: ${image.width} | Height: ${image.height}")
                        lastFrameLogTimeMs = now
                    }
                    if (now - lastFrameSaveTimeMs >= windowSizeMs) {
                        saveDebugFrame(image)
                        lastFrameSaveTimeMs = now
                    }
                    if (now - windowStartedAtMs >= windowSizeMs) {
                        Log.i(TAG, "Window complete | aggregation=$aggregation | samples=$samplesInWindow")
                        windowStartedAtMs = now
                        samplesInWindow = 0
                    }
                }
            }, captureHandler)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapturePOC", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null
        )
        Log.i(TAG, "Screen capture started")
        publishStatus("Capture active — waiting for sampled frames")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        publishStatus("Capture stopped")
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        faceDetector.close()
        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("ScreenCaptureFrames").apply { start() }
        captureHandler = Handler(captureThread.looper)
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
        Log.i(TAG, "Face detector created")
        publishStatus("Face detector ready")
        val channel = NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Screen capture active")
        .setContentText("MediaProjection proof of concept is running.")
        .setOngoing(true)
        .build()

    private fun detectFaces(bitmap: Bitmap?) {
        if (bitmap == null || isFaceDetectionRunning) {
            bitmap?.recycle()
            return
        }
        isFaceDetectionRunning = true
        faceDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                Log.i(TAG, "Faces detected: ${faces.size}")
                publishStatus("Faces detected: ${faces.size}")
                faces.forEachIndexed { index, face ->
                    val box = face.boundingBox
                    Log.i(TAG, "Face ${index + 1} | x=${box.left}, y=${box.top}, width=${box.width()}, height=${box.height()}")
                }
                faces.maxByOrNull { face ->
                    face.boundingBox.width().toLong() * face.boundingBox.height()
                }?.let { largestFace ->
                    val box = largestFace.boundingBox
                    Log.i(TAG, "Selected remote face (assumption: largest) | x=${box.left}, y=${box.top}, width=${box.width()}, height=${box.height()}")
                } ?: Log.i(TAG, "Invalid frame: no faces detected")
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Face detection failed", error)
                val status = if (error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE) {
                    "Face model unavailable — keep this device online while ML Kit downloads it"
                } else {
                    "Face detection failed: ${error.message ?: error.javaClass.simpleName}"
                }
                publishStatus(status)
            }
            .addOnCompleteListener {
                isFaceDetectionRunning = false
                bitmap.recycle()
            }
    }

    private fun saveDebugFrame(image: android.media.Image) {
        val bitmap = image.toBitmap() ?: run {
            Log.w(TAG, "Unable to convert captured frame to bitmap")
            return
        }
        val timestamp = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "zalo_capture_$timestamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/VoiceShieldAIFrames"
            )
        }

        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore did not create an output URI")
            contentResolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write PNG"
                }
            } ?: error("Could not open output stream")
            Log.i(TAG, "Debug frame saved: $uri")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to save debug frame", error)
        } finally {
            bitmap.recycle()
        }
    }

    private fun android.media.Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val imageWidth = this.width
        val imageHeight = this.height
        val paddedWidth = imageWidth +
            (plane.rowStride - plane.pixelStride * imageWidth) / plane.pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, imageWidth, imageHeight)
        paddedBitmap.recycle()
        return croppedBitmap
    }

    private fun Intent.projectionData(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(EXTRA_RESULT_DATA)
    }

    private fun publishStatus(status: String) {
        Log.i(TAG, status)
        sendBroadcast(
            Intent(ACTION_CAPTURE_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
        )
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SAMPLING_RATE = "sampling_rate"
        const val EXTRA_WINDOW_SIZE_SECONDS = "window_size_seconds"
        const val EXTRA_AGGREGATION = "aggregation"
        const val ACTION_CAPTURE_STATUS = "com.voiceshield.ai.CAPTURE_STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCapturePOC"
        private const val FRAME_LOG_INTERVAL_MS = 1_000L
        private const val DEFAULT_SAMPLING_RATE = 1
        private const val DEFAULT_WINDOW_SIZE_SECONDS = 5
        private const val DEFAULT_AGGREGATION = "average"
        private const val MIN_FACE_SIZE = 0.1f
    }
}
