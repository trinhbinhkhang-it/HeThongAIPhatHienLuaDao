package com.voiceshield.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var bubble: FrameLayout
    private lateinit var bubbleText: TextView
    private lateinit var ripple: View
    private val handler = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var current = DetectionResult(0, RiskLevel.NO_RESULT)
    private var highProtection = false
    private var monitoringStarted = false
    private var resultSequenceStarted = false
    private var participantFaceDetected = false

    private val faceDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_FACE_DETECTION_CHANGED) return

            participantFaceDetected = intent.getBooleanExtra(ScreenCaptureService.EXTRA_FACE_DETECTED, false)
            if (participantFaceDetected) beginResultSequenceAfterFaceDetected()
        }
    }

    override fun onCreate() {
        super.onCreate()
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
        highProtection = intent?.getBooleanExtra(EXTRA_HIGH_PROTECTION, false) ?: false
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(faceDetectionReceiver)
        removePanel()
        runCatching { windowManager.removeView(bubble) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64))
            setOnClickListener { showPanel() }
        }
        ripple = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64))
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
        bubble.addView(ripple)
        bubble.addView(bubbleText)
        windowManager.addView(bubble, overlayParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END, true).apply {
            x = dp(18)
            y = dp(96)
        })
        render()
    }

    private fun render() {
        val (color, label) = when (current.riskLevel) {
            RiskLevel.NO_RESULT -> COLOR_BLUE to "Đang\nphân tích"
            RiskLevel.NORMAL -> COLOR_GREEN to "${current.score}%"
            RiskLevel.SUSPICIOUS -> COLOR_YELLOW to "${current.score}%"
            RiskLevel.HIGH_RISK -> COLOR_RED to "${current.score}%"
        }
        bubbleText.text = "◉\n$label"
        bubbleText.background = circle(color)
        ripple.background = circle(Color.TRANSPARENT, color, 2)
        bubble.contentDescription = "DeepCheck: ${current.riskLevel.description()}, $label"
        animateBubble(current.riskLevel)
    }

    private fun animateBubble(level: RiskLevel) {
        ripple.animate().cancel()
        bubble.animate().cancel()
        when (level) {
            RiskLevel.NO_RESULT -> bubble.animate().translationYBy(-dp(3).toFloat())
                .setDuration(900).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                    bubble.animate().translationY(0f).setDuration(900).start()
                }.start()
            RiskLevel.NORMAL -> Unit
            RiskLevel.SUSPICIOUS, RiskLevel.HIGH_RISK -> {
                val duration = if (level == RiskLevel.HIGH_RISK) 650L else 1_300L
                ripple.alpha = .7f
                ripple.scaleX = .88f; ripple.scaleY = .88f
                ripple.animate().scaleX(if (level == RiskLevel.HIGH_RISK) 1.42f else 1.24f)
                    .scaleY(if (level == RiskLevel.HIGH_RISK) 1.42f else 1.24f).alpha(0f)
                    .setDuration(duration).withEndAction { animateBubble(level) }.start()
            }
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
                title.text = if (monitoringStarted) "ĐANG PHÂN TÍCH" else "BẢO VỆ CUỘC GỌI"
                message.text = if (monitoringStarted) {
                    "Đang chờ phát hiện khuôn mặt trong cuộc gọi video."
                } else {
                    "Sẵn sàng phân tích cuộc gọi video này."
                }
                card.addView(message, LinearLayout.LayoutParams(-1, -2))
                if (monitoringStarted) {
                    card.addView(singleAction("Tiếp tục phân tích", ::removePanel), LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(20) })
                } else {
                    card.addView(actionRow("Hủy", "Bắt đầu", ::removePanel, ::startMonitoring))
                }
            }
            else -> {
                title.text = if (current.riskLevel == RiskLevel.HIGH_RISK) "⚠ RỦI RO CAO" else "ĐANG PHÂN TÍCH"
                score.text = "${current.score}%\n${current.riskLevel.label()}"
                message.text = current.riskLevel.message()
                card.addView(score, LinearLayout.LayoutParams(-1, -2))
                card.addView(message, LinearLayout.LayoutParams(-1, -2))
                card.addView(
                    actionRow("Tiếp tục phân tích", "Dừng phân tích", ::removePanel) { stopSelf() },
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
        }
        panel = card
        windowManager.addView(card, overlayParams(dp(310), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER, false))
    }

    private fun startMonitoring() {
        removePanel()
        handler.removeCallbacksAndMessages(null)
        monitoringStarted = true
        resultSequenceStarted = false
        update(DetectionResult(0, RiskLevel.NO_RESULT))
        if (participantFaceDetected) beginResultSequenceAfterFaceDetected()
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
