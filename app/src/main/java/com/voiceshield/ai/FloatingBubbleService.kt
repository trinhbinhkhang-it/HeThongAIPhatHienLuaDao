package com.voiceshield.ai

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** A UI-only boundary: replace FakeDetectionResult with the model result later. */
data class DetectionResult(val score: Int, val riskLevel: RiskLevel)

enum class RiskLevel { NO_RESULT, NORMAL, SUSPICIOUS, HIGH_RISK }

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleContent: FrameLayout
    private lateinit var bubbleText: TextView
    private lateinit var ripple: View
    private lateinit var bubbleLayoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var current = DetectionResult(0, RiskLevel.NO_RESULT)
    private var highProtection = false
    private var monitoringStarted = false
    private var resultSequenceStarted = false
    private var participantFaceDetected = false
    private var isFloating = false

    private var isDragging = false
    private var lastActionDownTime = 0L
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val faceDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED) return

            participantFaceDetected = intent.getBooleanExtra(ScreenCaptureService.EXTRA_FACE_DETECTED, false)
            if (participantFaceDetected) beginResultSequenceAfterFaceDetected()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The main screen has already enabled protection before it starts this service.
        monitoringStarted = ProtectionState.isActive(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            faceDetectionReceiver,
            IntentFilter(ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_HIGH_PROTECTION) == true) {
            highProtection = intent.getBooleanExtra(EXTRA_HIGH_PROTECTION, false)
        }
        monitoringStarted = true
        ProtectionState.setActive(this, true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopFloating()
        ProtectionState.setActive(this, false)
        unregisterReceiver(faceDetectionReceiver)
        removePanel()
        runCatching { windowManager.removeView(bubbleContainer) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        bubbleContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        bubbleContent = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        ripple = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            background = circle(Color.TRANSPARENT, COLOR_BLUE, 2)
        }

        bubbleText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(0f, .9f)
            background = circle(COLOR_BLUE)
        }

        bubbleContent.addView(ripple)
        bubbleContent.addView(bubbleText)

        bubbleContent.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    lastActionDownTime = System.currentTimeMillis()
                    initialX = bubbleLayoutParams.x
                    initialY = bubbleLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6))) {
                        isDragging = true
                    }
                    if (isDragging) {
                        bubbleLayoutParams.x = initialX - dx
                        bubbleLayoutParams.y = initialY - dy
                        windowManager.updateViewLayout(bubbleContainer, bubbleLayoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && System.currentTimeMillis() - lastActionDownTime < 400) {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleContainer.addView(bubbleContent, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        bubbleLayoutParams = overlayParams(dp(100), dp(100), Gravity.BOTTOM or Gravity.END, true).apply {
            x = dp(14)
            y = dp(90)
        }

        windowManager.addView(bubbleContainer, bubbleLayoutParams)
        render()
    }

    private fun render() {
        val (color, label) = when (current.riskLevel) {
            RiskLevel.NO_RESULT -> COLOR_BLUE to ""
            RiskLevel.NORMAL -> COLOR_GREEN to "${current.score}%"
            RiskLevel.SUSPICIOUS -> COLOR_YELLOW to "${current.score}%"
            RiskLevel.HIGH_RISK -> COLOR_RED to "${current.score}%"
        }
        bubbleText.text = if (label.isEmpty()) "◉" else "◉\n$label"
        bubbleText.background = circle(color)
        ripple.background = circle(Color.TRANSPARENT, color, 2)
        bubbleContainer.contentDescription = if (label.isEmpty()) {
            "DeepCheck: đang bảo vệ"
        } else {
            "DeepCheck: ${current.riskLevel.description()}, $label"
        }
        animateBubble(current.riskLevel)
    }

    private fun animateBubble(level: RiskLevel) {
        ripple.animate().cancel()
        when (level) {
            RiskLevel.NO_RESULT -> {
                startFloating()
            }
            RiskLevel.NORMAL -> {
                stopFloating()
            }
            RiskLevel.SUSPICIOUS, RiskLevel.HIGH_RISK -> {
                stopFloating()
                val duration = if (level == RiskLevel.HIGH_RISK) 700L else 1300L
                ripple.alpha = 0.75f
                ripple.scaleX = 0.9f
                ripple.scaleY = 0.9f
                ripple.animate()
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .alpha(0f)
                    .setDuration(duration)
                    .withEndAction {
                        if (current.riskLevel == level) {
                            animateBubble(level)
                        }
                    }
                    .start()
            }
        }
    }

    private fun startFloating() {
        if (isFloating) return
        isFloating = true
        animateFloatingStep(-dp(5).toFloat())
    }

    private fun animateFloatingStep(targetY: Float) {
        if (!isFloating) return
        bubbleContent.animate()
            .translationY(targetY)
            .setDuration(1200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (isFloating) {
                    animateFloatingStep(if (targetY < 0) dp(5).toFloat() else -dp(5).toFloat())
                }
            }
            .start()
    }

    private fun stopFloating() {
        isFloating = false
        bubbleContent.animate().cancel()
        bubbleContent.animate().translationY(0f).setDuration(250).start()
    }

    private fun togglePanel() {
        if (panel != null) {
            removePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        removePanel()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(COLOR_SURFACE, dp(20), COLOR_LINE)
        }
        val title = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, 1)
        }
        val score = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(colorFor(current.riskLevel))
            textSize = 28f
            setPadding(0, dp(18), 0, 0)
        }
        val message = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        when (current.riskLevel) {
            RiskLevel.NO_RESULT -> {
                title.text = "BẢO VỆ CUỘC GỌI"
                message.text = "Đang chờ phát hiện khuôn mặt trong cuộc gọi video."
                card.addView(message, LinearLayout.LayoutParams(-1, -2))
                card.addView(
                    actionRow("Tiếp tục theo dõi", "Tắt bảo vệ", ::removePanel, ::stopProtection),
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
            else -> {
                title.text = if (current.riskLevel == RiskLevel.HIGH_RISK) "⚠ RỦI RO CAO" else "ĐANG PHÂN TÍCH"
                score.text = "${current.score}%\n${current.riskLevel.label()}"
                message.text = current.riskLevel.message()
                card.addView(score, LinearLayout.LayoutParams(-1, -2))
                card.addView(message, LinearLayout.LayoutParams(-1, -2))
                card.addView(
                    actionRow("Tiếp tục theo dõi", "Tắt bảo vệ", ::removePanel, ::stopProtection),
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
        }
        panel = card
        windowManager.addView(card, overlayParams(dp(310), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER, false))
    }

    private fun stopProtection() {
        ProtectionState.setActive(this, false)
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopSelf()
    }

    /** Starts demo/model results only after ScreenCaptureService identifies a participant face. */
    private fun beginResultSequenceAfterFaceDetected() {
        if (!monitoringStarted || resultSequenceStarted) return

        resultSequenceStarted = true
        if (highProtection) {
            handler.postDelayed({ update(DetectionResult(58, RiskLevel.SUSPICIOUS)) }, 900)
            handler.postDelayed({
                update(DetectionResult(84, RiskLevel.HIGH_RISK))
                showHighRiskNotification()
            }, 4_500)
        } else {
            handler.postDelayed({ update(DetectionResult(24, RiskLevel.NORMAL)) }, 900)
        }
    }

    private fun update(result: DetectionResult) { current = result; render() }

    private fun actionRow(left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
            addView(button(left, false, onLeft), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(button(right, true, onRight), LinearLayout.LayoutParams(0, dp(46), 1f))
        }

    private fun singleAction(text: String, action: () -> Unit): View = button(text, false, action)

    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        background = rounded(if (primary) COLOR_BLUE else COLOR_SURFACE_2, dp(12), if (primary) COLOR_BLUE else COLOR_LINE)
        setOnClickListener { action() }
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun showHighRiskNotification() {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CẢNH BÁO CUỘC GỌI")
            .setContentText("Có dấu hiệu nghi ngờ thao túng khuôn mặt. Không chia sẻ OTP hoặc thông tin nhạy cảm.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Có dấu hiệu nghi ngờ thao túng khuôn mặt trong cuộc gọi. Không chia sẻ OTP hoặc thông tin nhạy cảm."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WARNING_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(WARNING_CHANNEL, "Cảnh báo DeepCheck", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int, notFocusable: Boolean) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        if (notFocusable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity }

    private fun circle(color: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }
    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke) }
    private fun colorFor(level: RiskLevel) = when (level) { RiskLevel.NO_RESULT -> COLOR_BLUE; RiskLevel.NORMAL -> COLOR_GREEN; RiskLevel.SUSPICIOUS -> COLOR_YELLOW; RiskLevel.HIGH_RISK -> COLOR_RED }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun RiskLevel.label() = when (this) { RiskLevel.NO_RESULT -> "CHƯA CÓ KẾT QUẢ"; RiskLevel.NORMAL -> "BÌNH THƯỜNG"; RiskLevel.SUSPICIOUS -> "NGHI NGỜ"; RiskLevel.HIGH_RISK -> "RỦI RO CAO" }
    private fun RiskLevel.description() = label().lowercase()
    private fun RiskLevel.message() = when (this) {
        RiskLevel.NORMAL -> "Chưa phát hiện dấu hiệu bất thường đáng kể."
        RiskLevel.SUSPICIOUS -> "Có một số dấu hiệu bất thường trong hình ảnh."
        RiskLevel.HIGH_RISK -> "Phát hiện dấu hiệu có thể liên quan đến face-swapping.\n\nKhông chia sẻ OTP hoặc thông tin nhạy cảm."
        RiskLevel.NO_RESULT -> ""
    }

    companion object {
        const val EXTRA_HIGH_PROTECTION = "high_protection"
        private const val WARNING_CHANNEL = "high_risk_warning"
        private const val WARNING_NOTIFICATION_ID = 42
        private val COLOR_BLUE = Color.rgb(22, 119, 255)
        private val COLOR_GREEN = Color.rgb(52, 211, 153)
        private val COLOR_YELLOW = Color.rgb(251, 191, 36)
        private val COLOR_RED = Color.rgb(255, 71, 87)
        private val COLOR_SURFACE = Color.rgb(18, 26, 43)
        private val COLOR_SURFACE_2 = Color.rgb(26, 36, 56)
        private val COLOR_LINE = Color.rgb(38, 49, 74)
        private val COLOR_MUTED = Color.rgb(139, 150, 170)
    }
}
