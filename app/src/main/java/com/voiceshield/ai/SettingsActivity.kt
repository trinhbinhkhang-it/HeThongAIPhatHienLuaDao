package com.voiceshield.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Page 2 (Settings) — the "Chế độ quét" page: pick the scan mode the floating bubble uses
 * once a scan is toggled on with a double tap.
 *
 * SAFE    -> reassuring "call is safe" popup after a random 4-7 s.
 * WARNING -> deepfake alert popup after a random 5-8 s.
 *
 * Both windows are defaults only: the hidden developer page (triple-tap the
 * version footer on Page 1) can override them, and the bubble re-reads the
 * persisted values every time it arms the scan timer.
 *
 * developer_setting_page.md §6 puts one limit on this page. While a detection session is
 * running the mode it copied at start-up is *locked*, so the page displays that session mode
 * and refuses edits until the bubble goes inactive again; a selection made here then applies
 * to the next session only.
 *
 * The lock is carried by the controls themselves rather than by a notice next to them: the two
 * radios and "Lưu cấu hình" grey out and stop responding, and every one of those colours comes
 * from a selector in res/color (color_mode_label, color_mode_button, color_pill_primary,
 * color_pill_primary_label) so locked and unlocked cannot look alike. "Về trang chủ" stays live
 * on purpose — the lock may not trap the user on the page.
 *
 * The page also follows the session *live*. The bubble is an overlay that floats over this
 * activity, so a double tap here never reaches onStart(); FloatingBubbleService broadcasts
 * [FloatingBubbleService.ACTION_SCAN_SESSION_CHANGED] for exactly that case, and it lands in
 * [bindMode] alongside [ProtectionState.ACTION_MODE_CHANGED].
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var saveButton: MaterialButton

    /**
     * Re-reads the page whenever anything changes it — including §4's footer swipe and §5's
     * edge snap, neither of which goes through this screen — and whenever the live session
     * starts or ends, which is what greys the controls out and brings them back. Without it the
     * page would keep showing a stale selection and a stale lock until the user reopened it.
     *
     * Both actions re-derive from the companion instead of trusting the broadcast extras, so a
     * signal that never arrived is healed by the next onStart() just the same.
     */
    private val pageStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ProtectionState.ACTION_MODE_CHANGED,
                FloatingBubbleService.ACTION_SCAN_SESSION_CHANGED -> bindMode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        modeGroup = findViewById(R.id.mode_group)
        saveButton = findViewById(R.id.btn_save_config)

        bindMode()

        saveButton.setOnClickListener { saveConfig() }
        findViewById<MaterialButton>(R.id.btn_back_home).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            pageStateReceiver,
            IntentFilter().apply {
                addAction(ProtectionState.ACTION_MODE_CHANGED)
                addAction(FloatingBubbleService.ACTION_SCAN_SESSION_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // A session may have started or ended while this page was backgrounded, and the lock
        // follows the session rather than the page.
        bindMode()
    }

    override fun onStop() {
        unregisterReceiver(pageStateReceiver)
        super.onStop()
    }

    /**
     * Paints the radio group and the §6 lock state in one pass, from the single pure rule that
     * owns both ([scanModePageState]).
     */
    private fun bindMode() {
        val state = scanModePageState(
            sessionActive = FloatingBubbleService.isScanSessionActive,
            sessionMode = FloatingBubbleService.activeSessionMode,
            selectedMode = ProtectionState.getMode(this)
        )
        modeGroup.check(
            if (state.displayedMode == ScanMode.SAFE) R.id.mode_safe else R.id.mode_warning
        )
        // RadioGroup.setEnabled() does not reach its children, so each button is switched too.
        modeGroup.isEnabled = state.controlsEnabled
        for (index in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(index).isEnabled = state.controlsEnabled
        }
        saveButton.isEnabled = state.controlsEnabled
    }

    private fun saveConfig() {
        // Belt and braces behind the disabled button: a locked session must never be edited,
        // because §6 says the session mode "remains unchanged until the bubble becomes
        // inactive".
        if (isScanModeLocked(FloatingBubbleService.isScanSessionActive)) return
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
