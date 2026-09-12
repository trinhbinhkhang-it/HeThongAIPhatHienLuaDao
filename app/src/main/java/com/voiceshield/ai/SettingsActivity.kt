package com.voiceshield.ai

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Page 2 (Settings): pick the scan mode the floating bubble uses once a scan
 * is toggled on with a double tap.
 *
 * SAFE    -> reassuring "call is safe" popup after a random 8-25 s.
 * WARNING -> deepfake alert popup after a random 7-20 s.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        modeGroup = findViewById(R.id.mode_group)
        modeGroup.check(
            if (ProtectionState.getMode(this) == ScanMode.SAFE) R.id.mode_safe else R.id.mode_warning
        )

        findViewById<MaterialButton>(R.id.btn_save_config).setOnClickListener { saveConfig() }
        findViewById<MaterialButton>(R.id.btn_back_home).setOnClickListener { finish() }
    }

    private fun saveConfig() {
        val mode = if (modeGroup.checkedRadioButtonId == R.id.mode_warning) {
            ScanMode.WARNING
        } else {
            ScanMode.SAFE
        }
        ProtectionState.setMode(this, mode)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        // Home is the task root, so finishing returns straight to Page 1.
        finish()
    }
}
