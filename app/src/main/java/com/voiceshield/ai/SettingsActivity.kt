package com.voiceshield.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
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
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var saveButton: MaterialButton
    private lateinit var lockedNotice: TextView

    /**
     * Re-reads the mode whenever anything changes it — including §4's footer swipe and §5's
     * edge snap, neither of which goes through this screen. Without it the page would keep
     * showing a stale selection until the user reopened it.
     */
    private val modeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ProtectionState.ACTION_MODE_CHANGED) return
            bindMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        modeGroup = findViewById(R.id.mode_group)
        saveButton = findViewById(R.id.btn_save_config)
        lockedNotice = findViewById(R.id.mode_locked_notice)

        bindMode()

        saveButton.setOnClickListener { saveConfig() }
        findViewById<MaterialButton>(R.id.btn_back_home).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            modeChangedReceiver,
            IntentFilter(ProtectionState.ACTION_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // A session may have started or ended while this page was backgrounded, and the lock
        // follows the session rather than the page.
        bindMode()
    }

    override fun onStop() {
        unregisterReceiver(modeChangedReceiver)
        super.onStop()
    }

    /**
     * The mode this page shows: the running session's locked mode while the bubble is active,
     * the persisted selection otherwise ([displayedScanMode]).
     */
    private fun displayedMode(): ScanMode = displayedScanMode(
        sessionActive = FloatingBubbleService.isScanSessionActive,
        sessionMode = FloatingBubbleService.activeSessionMode,
        selectedMode = ProtectionState.getMode(this)
    )

    /** Paints the radio group and the §6 lock state in one pass. */
    private fun bindMode() {
        val locked = isScanModeLocked(FloatingBubbleService.isScanSessionActive)
        modeGroup.check(
            if (displayedMode() == ScanMode.SAFE) R.id.mode_safe else R.id.mode_warning
        )
        // RadioGroup.setEnabled() does not reach its children, so each button is switched too.
        modeGroup.isEnabled = !locked
        for (index in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(index).isEnabled = !locked
        }
        saveButton.isEnabled = !locked
        lockedNotice.visibility = if (locked) View.VISIBLE else View.GONE
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
