package com.voiceshield.ai

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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
    private var hasParticipantFace = false
    private var consecutiveMisses = 0
    private var consecutiveMismatches = 0
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
                val image = availableReader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                if (now - lastSampleTimeMs < 1_000L / samplingRate) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastSampleTimeMs = now
                if (windowStartedAtMs == 0L) windowStartedAtMs = now
                samplesInWindow++
                if (now - lastFrameLogTimeMs >= FRAME_LOG_INTERVAL_MS) {
                    Log.i(TAG, "Frame received | Width: ${image.width} | Height: ${image.height}")
                    lastFrameLogTimeMs = now
                }
                if (now - lastFrameSaveTimeMs >= windowSizeMs) {
                    saveDebugFrame(image)
                    lastFrameSaveTimeMs = now
                }
                detectFaces(image)
                if (now - windowStartedAtMs >= windowSizeMs) {
                    Log.i(TAG, "Window complete | aggregation=$aggregation | samples=$samplesInWindow")
                    windowStartedAtMs = now
                    samplesInWindow = 0
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
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_capture_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setColor(ContextCompat.getColor(this, R.color.mint_500))
        .setContentTitle(getString(R.string.notif_capture_title))
        .setContentText(getString(R.string.notif_capture_text))
        .setOngoing(true)
        .build()

    private fun detectFaces(image: Image?) {
        if (image == null) return
        if (isFaceDetectionRunning) {
            image.close()
            return
        }

        isFaceDetectionRunning = true
        val inputImage = when (image.format) {
            ImageFormat.YUV_420_888 -> InputImage.fromMediaImage(image, 0)
            PixelFormat.RGBA_8888 -> {
                val bitmap = image.toBitmap() ?: run {
                    isFaceDetectionRunning = false
                    image.close()
                    return
                }
                InputImage.fromBitmap(bitmap, 0)
            }
            else -> {
                isFaceDetectionRunning = false
                image.close()
                return
            }
        }

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val rawFaceCount = faces.size
                Log.i(TAG, "Raw ML Kit faces detected: $rawFaceCount")

                val frameArea = image.width.toLong() * image.height.toLong()
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
                    Log.i(TAG, "Face ${index + 1} | x=${box.left}, y=${box.top}, width=${box.width()}, height=${box.height()}")
                }

                updateTracking(validFaces.map { it.boundingBox }, image.width, image.height)
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
                image.close()
            }
    }

    private fun updateTracking(candidateRects: List<Rect>, screenWidth: Int, screenHeight: Int) {
        val previousFace = lastTrackedFaceRect
        val candidates = candidateRects
            .filterNot { box -> isLikelyPipPreview(box, screenWidth, screenHeight, previousFace) }
            .filterNot { box -> isLikelyPrivacyMask(box, screenWidth, screenHeight) }

        if (previousFace == null) {
            val best = candidates.maxByOrNull { box -> scoreFaceCandidate(box, screenWidth, screenHeight, null) }
            if (best != null) {
                lastTrackedFaceRect = best
                consecutiveMisses = 0
                consecutiveMismatches = 0
                setParticipantFaceDetected(true)
                Log.i(TAG, "Tracking started | x=${best.left}, y=${best.top}, width=${best.width()}, height=${best.height()}")
            } else {
                consecutiveMisses++
                setParticipantFaceDetected(false)
                Log.i(TAG, "No participant face detected yet (miss $consecutiveMisses)")
            }
            return
        }

        if (candidates.isEmpty()) {
            consecutiveMisses++
            consecutiveMismatches = 0
            if (consecutiveMisses >= MAX_MISS_TOLERANCE) {
                Log.i(TAG, "Participant face missing for $consecutiveMisses frames; clearing tracked face")
                lastTrackedFaceRect = null
                consecutiveMisses = 0
                setParticipantFaceDetected(false)
            } else {
                Log.i(TAG, "Participant face missing ($consecutiveMisses/$MAX_MISS_TOLERANCE); holding tracked face")
            }
            return
        }

        val sameIdentity = candidates.filter { box -> isLikelySameIdentity(box, previousFace) }
        if (sameIdentity.isNotEmpty()) {
            val best = sameIdentity.maxByOrNull { box -> scoreFaceCandidate(box, screenWidth, screenHeight, previousFace) }!!
            val smoothed = smoothFaceRect(previousFace, best, screenWidth, screenHeight)
            lastTrackedFaceRect = smoothed
            consecutiveMisses = 0
            consecutiveMismatches = 0
            setParticipantFaceDetected(true)
            Log.i(TAG, "Tracked face updated | x=${smoothed.left}, y=${smoothed.top}, width=${smoothed.width()}, height=${smoothed.height()}")
            return
        }

        consecutiveMismatches++
        consecutiveMisses = 0
        if (consecutiveMismatches >= RESET_HYSTERESIS) {
            val best = candidates.maxByOrNull { box -> scoreFaceCandidate(box, screenWidth, screenHeight, previousFace) }!!
            Log.i(TAG, "Tracking reset to a new face after $consecutiveMismatches mismatches | x=${best.left}, y=${best.top}, width=${best.width()}, height=${best.height()}")
            lastTrackedFaceRect = best
            consecutiveMismatches = 0
            setParticipantFaceDetected(true)
        } else {
            Log.i(TAG, "Identity mismatch ($consecutiveMismatches/$RESET_HYSTERESIS); holding previous face")
        }
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
        val distance = centerDistance(candidate, previousFace)
        val similarSize = candidate.width() > previousFace.width() * 0.5f &&
            candidate.height() > previousFace.height() * 0.5f &&
            candidate.width() < previousFace.width() * 2.2f &&
            candidate.height() < previousFace.height() * 2.2f
        return distance < identityDistanceThreshold(previousFace) && similarSize
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
            val distance = centerDistance(box, previousFace)
            val threshold = identityDistanceThreshold(previousFace)
            if (distance < threshold) 120_000f / (1f + distance / threshold.coerceAtLeast(1f)) else 0f
        } else {
            0f
        }

        return sizeBias + centerBias + participantZone + previousBoost
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val dx = (a.centerX() - b.centerX()).toFloat()
        val dy = (a.centerY() - b.centerY()).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun identityDistanceThreshold(previousFace: Rect): Float =
        previousFace.width() * IDENTITY_MOVEMENT_FRACTION

    private fun smoothFaceRect(previous: Rect, current: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val alpha = SMOOTHING_ALPHA
        fun blend(prev: Int, curr: Int): Int = (prev + (curr - prev) * alpha).toInt()
        return Rect(
            blend(previous.left, current.left).coerceIn(0, screenWidth),
            blend(previous.top, current.top).coerceIn(0, screenHeight),
            blend(previous.right, current.right).coerceIn(0, screenWidth),
            blend(previous.bottom, current.bottom).coerceIn(0, screenHeight)
        )
    }

    private fun saveDebugFrame(image: Image) {
        val bitmap = image.toBitmap() ?: run {
            Log.w(TAG, "Unable to convert captured frame to bitmap")
            return
        }
        try {
            val dir = File(filesDir, DEBUG_FRAME_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Unable to create debug frame directory: ${dir.absolutePath}")
                return
            }
            val file = File(dir, "frame_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write PNG"
                }
            }
            Log.i(TAG, "Debug frame saved: ${file.absolutePath}")
            enforceDebugFrameRetention(dir)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to save debug frame", error)
        } finally {
            bitmap.recycle()
        }
    }

    private fun enforceDebugFrameRetention(dir: File) {
        val frames = dir.listFiles { file -> file.isFile && file.extension == "png" } ?: return
        if (frames.size <= DEBUG_FRAME_MAX_COUNT) return
        frames.sortedBy { it.lastModified() }
            .take(frames.size - DEBUG_FRAME_MAX_COUNT)
            .forEach { it.delete() }
    }

    private fun Image.toBitmap(): Bitmap? {
        val width = this.width
        val height = this.height
        if (width <= 0 || height <= 0) return null

        return when (format) {
            PixelFormat.RGBA_8888 -> {
                val plane = planes.firstOrNull() ?: return null
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val expectedStride = width * 4
                Log.i(TAG, "RGBA stride check | rowStride=$rowStride | expected=$expectedStride | width=$width | height=$height")

                val output = ByteArray(width * height * 4)
                val source = plane.buffer.duplicate()
                val rowBytes = ByteArray(width * 4)

                if (rowStride == expectedStride && pixelStride == 4) {
                    source.rewind()
                    if (source.remaining() < output.size) return null
                    source.get(output)
                } else {
                    val stride = maxOf(rowStride, expectedStride)
                    val rowLength = width * 4
                    for (row in 0 until height) {
                        source.position(row * stride)
                        source.get(rowBytes, 0, rowLength)
                        System.arraycopy(rowBytes, 0, output, row * rowLength, rowLength)
                    }
                }

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val buffer = java.nio.ByteBuffer.wrap(output)
                bitmap.copyPixelsFromBuffer(buffer)
                bitmap
            }
            ImageFormat.YUV_420_888 -> {
                if (planes.size < 3) return null

                val yPlane = planes[0]
                val uPlane = planes[1]
                val vPlane = planes[2]
                val yBuffer = yPlane.buffer
                val uBuffer = uPlane.buffer
                val vBuffer = vPlane.buffer

                val nv21 = ByteArray(width * height * 3 / 2)
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        nv21[row * width + col] = yBuffer.get(row * yPlane.rowStride + col * yPlane.pixelStride)
                    }
                }

                var pos = width * height
                val uvWidth = width / 2
                val uvHeight = height / 2
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        nv21[pos] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                        nv21[pos + 1] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                        pos += 2
                    }
                }

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)) {
                    return null
                }
                val jpeg = out.toByteArray()
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }
            else -> null
        }
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

    private fun setParticipantFaceDetected(detected: Boolean) {
        if (hasParticipantFace == detected) return
        hasParticipantFace = detected
        sendBroadcast(
            Intent(ACTION_FACE_DETECTION_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_FACE_DETECTED, detected)
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
        const val ACTION_FACE_DETECTION_CHANGED = "com.voiceshield.ai.FACE_DETECTION_CHANGED"
        const val EXTRA_FACE_DETECTED = "face_detected"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCapturePOC"
        private const val FRAME_LOG_INTERVAL_MS = 1_000L
        private const val DEFAULT_SAMPLING_RATE = 1
        private const val DEFAULT_WINDOW_SIZE_SECONDS = 5
        private const val DEFAULT_AGGREGATION = "average"
        private const val MIN_FACE_SIZE = 0.1f
        private const val IDENTITY_MOVEMENT_FRACTION = 0.35f
        private const val MAX_MISS_TOLERANCE = 3
        private const val RESET_HYSTERESIS = 3
        private const val SMOOTHING_ALPHA = 0.3f
        private const val DEBUG_FRAME_DIR = "debug_frames"
        private const val DEBUG_FRAME_MAX_COUNT = 10
    }
}
