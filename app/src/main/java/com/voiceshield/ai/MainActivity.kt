package com.voiceshield.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voiceshield.ai.audio.AudioStreamController

class MainActivity : AppCompatActivity() {

    private val audioController = AudioStreamController()
    private var isMonitoring = false

    private lateinit var txtStatus: TextView
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Dựng Layout giao diện test bằng Code
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val btnDiagnostic = Button(this).apply {
            text = "CHẨN ĐOÁN HỆ THỐNG (DIAGNOSTIC)"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DiagnosticActivity::class.java))
            }
        }
        rootLayout.addView(btnDiagnostic)

        txtStatus = TextView(this).apply {
            text = "Trạng thái: Chưa bật bảo vệ"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
            setTextColor(Color.GRAY)
        }

        btnToggle = Button(this).apply {
            text = "BẮT ĐẦU BẢO VỆ (START RAM BUFFER)"
            textSize = 18f
        }

        rootLayout.addView(txtStatus)
        rootLayout.addView(btnToggle)
        setContentView(rootLayout)

        checkPermission()

        // 2. Đăng ký nhận kết quả AI gửi từ C++ Native
        audioController.setOnAIResultListener(object : AudioStreamController.OnAIResultListener {
            override fun onAIResult(score: Float) {
                val percentage = (score * 100).toInt()

                // Phân loại mức độ rủi ro Deepfake để đổi màu & cảnh báo UI
                if (score >= 0.80f) {
                    txtStatus.text = "⚠️ CẢNH BÁO DEEPFAKE!\nNguy cơ giả mạo: $percentage%"
                    txtStatus.setTextColor(Color.RED)
                } else if (score >= 0.50f) {
                    txtStatus.text = "⚡ MỨC ĐỘ NGHI VẤN\nTỷ lệ Deepfake: $percentage%"
                    txtStatus.setTextColor(Color.rgb(255, 140, 0)) // Màu cam
                } else {
                    txtStatus.text = "✅ Giọng nói an toàn\nTỷ lệ Deepfake: $percentage%"
                    txtStatus.setTextColor(Color.rgb(0, 150, 0)) // Màu xanh lá
                }
            }
        })

        // 3. Xử lý sự kiện bấm nút Bắt đầu / Dừng
        btnToggle.setOnClickListener {
            if (!isMonitoring) {
                audioController.startCapture()
                btnToggle.text = "DỪNG BẢO VỆ (FLUSH RAM)"
                txtStatus.text = "⏳ Đang lắng nghe & phân tích âm thanh..."
                txtStatus.setTextColor(Color.BLUE)
                Toast.makeText(this, "Đã bật ghi âm đệm RAM vòng tròn!", Toast.LENGTH_SHORT).show()
            } else {
                audioController.stopAndClear()
                btnToggle.text = "BẮT ĐẦU BẢO VỆ (START RAM BUFFER)"
                txtStatus.text = "Trạng thái: Đã dừng bảo vệ"
                txtStatus.setTextColor(Color.GRAY)
                Toast.makeText(this, "Đã hủy toàn bộ dữ liệu trên RAM!", Toast.LENGTH_SHORT).show()
            }
            isMonitoring = !isMonitoring
        }
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioController.stopAndClear()
    }
}