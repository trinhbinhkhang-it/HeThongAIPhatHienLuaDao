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
import android.graphics.Rect
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
    @Volatile private var lastTrackedFaceRect: Rect? = null
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
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
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

        val cropRect = getVideoCallCropRect(bitmap)
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        if (cropWidth <= 0 || cropHeight <= 0) {
            bitmap.recycle()
            return
        }

        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropWidth,
            cropHeight
        )

        isFaceDetectionRunning = true
        faceDetector.process(InputImage.fromBitmap(croppedBitmap, 0))
            .addOnSuccessListener { faces ->
                val rawFaceCount = faces.size
                Log.i(TAG, "Raw ML Kit faces detected: $rawFaceCount")

                val frameArea = croppedBitmap.width.toLong() * croppedBitmap.height.toLong()
                val minFaceArea = maxOf(1_000L, (frameArea * 0.0025f).toLong())
                val maxFaceArea = maxOf(minFaceArea + 1L, (frameArea * 0.75f).toLong())

                val validFaces = faces.filter { face ->
                    val box = face.boundingBox
                    val area = box.width().toLong() * box.height().toLong()
                    val isReasonableSize = area in minFaceArea..maxFaceArea
                    val isReasonableShape = box.width() > 0 && box.height() > 0 &&
                        box.height().toFloat() / box.width().toFloat() in 0.5f..1.8f
                    isReasonableSize && isReasonableShape
                }

                Log.i(TAG, "Faces detected after size filter: ${validFaces.size}")
                publishStatus("Faces detected: ${validFaces.size}")
                validFaces.forEachIndexed { index, face ->
                    val box = face.boundingBox
                    val adjustedBox = Rect(
                        box.left + cropRect.left,
                        box.top + cropRect.top,
                        box.right + cropRect.left,
                        box.bottom + cropRect.top
                    )
                    Log.i(TAG, "Face ${index + 1} | x=${adjustedBox.left}, y=${adjustedBox.top}, width=${adjustedBox.width()}, height=${adjustedBox.height()}")
                }

                val candidateRects = validFaces.map { face ->
                    Rect(
                        face.boundingBox.left + cropRect.left,
                        face.boundingBox.top + cropRect.top,
                        face.boundingBox.right + cropRect.left,
                        face.boundingBox.bottom + cropRect.top
                    )
                }

                val previousFace = lastTrackedFaceRect
                val candidateWithoutPip = candidateRects
                    .filterNot { box -> isLikelyPipPreview(box, bitmap.width, bitmap.height, previousFace) }
                    .filterNot { box -> isLikelyPrivacyMask(box, bitmap.width, bitmap.height) }

                if (previousFace != null && candidateWithoutPip.isEmpty()) {
                    Log.i(TAG, "No candidate survived PIP/privacy filtering; resetting tracked face and waiting for a stronger match")
                    lastTrackedFaceRect = null
                }

                val selectedFace = if (previousFace != null) {
                    val sameIdentity = candidateWithoutPip.filter { box -> isLikelySameIdentity(box, previousFace) }
                    sameIdentity.maxByOrNull { box -> scoreFaceCandidate(box, bitmap.width, bitmap.height, previousFace) }
                        ?: candidateWithoutPip.maxByOrNull { box -> scoreFaceCandidate(box, bitmap.width, bitmap.height, previousFace) }
                } else {
                    candidateWithoutPip.maxByOrNull { box -> scoreFaceCandidate(box, bitmap.width, bitmap.height, previousFace) }
                }

                selectedFace?.let { box ->
                    if (previousFace == null || !isLikelySameIdentity(box, previousFace)) {
                        Log.i(TAG, "Tracking reset to a new face | x=${box.left}, y=${box.top}, width=${box.width()}, height=${box.height()}")
                    }
                    lastTrackedFaceRect = box
                    Log.i(TAG, "Selected tracked face | x=${box.left}, y=${box.top}, width=${box.width()}, height=${box.height()}")
                } ?: run {
                    Log.i(TAG, "Invalid frame: no valid participant face detected in the central call area")
                    lastTrackedFaceRect = null
                }
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
                croppedBitmap.recycle()
                bitmap.recycle()
            }
    }

    private fun getVideoCallCropRect(bitmap: Bitmap): Rect {
        val left = (bitmap.width * 0.10f).toInt()
        val top = (bitmap.height * 0.12f).toInt()
        val right = (bitmap.width * 0.90f).toInt()
        val bottom = (bitmap.height * 0.88f).toInt()
        return Rect(left, top, right, bottom)
    }

    private fun isLikelyPipPreview(box: Rect, screenWidth: Int, screenHeight: Int, previousFace: Rect?): Boolean {
        val areaRatio = box.width().toFloat() * box.height().toFloat() / (screenWidth * screenHeight).toFloat()
        val isSmall = areaRatio < 0.012f
        val isRounded = box.width() > 0 && box.height() > 0 &&
            kotlin.math.abs(box.width().toFloat() / box.height().toFloat() - 1.0f) < 0.45f

        val nearEdge = box.right > screenWidth * 0.78f && box.bottom < screenHeight * 0.45f
        val nearPrevious = previousFace != null && isLikelySameIdentity(box, previousFace)

        return isSmall && isRounded && nearEdge && !nearPrevious
    }

    private fun isLikelyPrivacyMask(box: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        val areaRatio = box.width().toFloat() * box.height().toFloat() / (screenWidth * screenHeight).toFloat()
        val isTiny = areaRatio < 0.005f
        val isTopLeftish = box.centerX() < screenWidth * 0.30f && box.centerY() < screenHeight * 0.25f
        return isTiny && isTopLeftish
    }

    private fun isLikelySameIdentity(candidate: Rect, previousFace: Rect): Boolean {
        val dx = kotlin.math.abs(candidate.centerX() - previousFace.centerX())
        val dy = kotlin.math.abs(candidate.centerY() - previousFace.centerY())
        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val similarSize = candidate.width() > previousFace.width() * 0.5f &&
            candidate.height() > previousFace.height() * 0.5f &&
            candidate.width() < previousFace.width() * 2.2f &&
            candidate.height() < previousFace.height() * 2.2f
        return distance < 180f && similarSize
    }

    private fun scoreFaceCandidate(
        box: Rect,
        screenWidth: Int,
        screenHeight: Int,
        previousFace: Rect?
    ): Float {
        val area = box.width().toFloat() * box.height().toFloat()
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()

        val sizeBias = area * 3f
        val centerBias = 1_000_000f / (1f + kotlin.math.abs(cx - screenWidth * 0.58f) + kotlin.math.abs(cy - screenHeight * 0.26f))
        val participantZone = if (cx in (screenWidth * 0.20f)..(screenWidth * 0.85f) &&
            cy in (screenHeight * 0.04f)..(screenHeight * 0.60f)) 45_000f else 0f

        val previousBoost = if (previousFace != null) {
            val dx = kotlin.math.abs(cx - previousFace.centerX())
            val dy = kotlin.math.abs(cy - previousFace.centerY())
            val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val proximity = if (distance < 180f) 120_000f / (1f + distance / 80f) else 0f
            proximity
        } else {
            0f
        }

        return sizeBias + centerBias + participantZone + previousBoost
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
