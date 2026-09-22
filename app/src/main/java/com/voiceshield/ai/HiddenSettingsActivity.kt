package com.voiceshield.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/** How many taps on the version footer open this page. */
const val TRIPLE_CLICK_COUNT = 3

/**
 * Whole burst window, in milliseconds, measured from the *first* tap. All three
 * taps must land inside it, and it is also how long the ordinary single-tap
 * navigation to Page 2 is deferred so that taps 2 and 3 can still reach the footer.
 */
const val TRIPLE_CLICK_WINDOW_MS = 900L

/**
 * Pure multi-tap rule behind the hidden developer page.
 *
 * Top-level and Android-free, exactly like [isScreenCaptureGranted] and
 * [scanDelayMs], so the gesture can be unit-tested on the local JVM.
 */
fun isTripleClick(
    clickCount: Int,
    elapsedSinceFirstClickMs: Long,
    requiredClicks: Int = TRIPLE_CLICK_COUNT,
    windowMs: Long = TRIPLE_CLICK_WINDOW_MS
): Boolean = clickCount >= requiredClicks && elapsedSinceFirstClickMs <= windowMs

/**
 * True while a further tap still counts towards the same burst. Once this goes
 * false the counter restarts at 1, so three slow taps never add up to a triple
 * click and never lock the user out of Page 2.
 */
fun isClickBurstOpen(
    elapsedSinceFirstClickMs: Long,
    windowMs: Long = TRIPLE_CLICK_WINDOW_MS
): Boolean = elapsedSinceFirstClickMs <= windowMs

/**
 * The hidden developer page, reachable only by triple-tapping the "Version 1.0.1"
 * footer on Page 1. It is not linked from Page 2 and not exported, so it stays out
 * of the ordinary user's way.
 *
 * It edits the random roll windows the bubble waits before showing a scan popup:
 * Safe Mode and Warning Mode each get a minimum and a maximum, in whole seconds.
 * Every value passes through [sanitizeInterval], so free-form input can never
 * produce a window that makes the scan timer throw.
 */
class HiddenSettingsActivity : AppCompatActivity() {

    private lateinit var safeMinInput: TextInputEditText
    private lateinit var safeMaxInput: TextInputEditText
    private lateinit var warningMinInput: TextInputEditText
    private lateinit var warningMaxInput: TextInputEditText
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_settings)

        safeMinInput = findViewById(R.id.input_safe_min)
        safeMaxInput = findViewById(R.id.input_safe_max)
        warningMinInput = findViewById(R.id.input_warning_min)
        warningMaxInput = findViewById(R.id.input_warning_max)
        preview = findViewById(R.id.hidden_preview)

        bind(ProtectionState.getSafeInterval(this), ProtectionState.getWarningInterval(this))

        // Live preview of what will actually be saved, sanitisation included, so a
        // clamped or swapped pair is visible before the user commits to it.
        val watcher = afterTextChangedWatcher { renderPreview() }
        listOf(safeMinInput, safeMaxInput, warningMinInput, warningMaxInput)
            .forEach { it.addTextChangedListener(watcher) }
        renderPreview()

        findViewById<MaterialButton>(R.id.btn_hidden_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_hidden_reset).setOnClickListener { reset() }
        findViewById<MaterialButton>(R.id.btn_hidden_back).setOnClickListener { finish() }
    }

    private fun save() {
        val safe = enteredSafeInterval()
        val warning = enteredWarningInterval()
        ProtectionState.setIntervals(this, safe, warning)
        // Echo the normalised pair back: what is on screen is then exactly what is
        // persisted, even when the input needed clamping or swapping.
        bind(safe, warning)
        Toast.makeText(this, R.string.hidden_saved, Toast.LENGTH_SHORT).show()
    }

    private fun reset() {
        ProtectionState.resetIntervals(this)
        bind(DEFAULT_SAFE_INTERVAL, DEFAULT_WARNING_INTERVAL)
        Toast.makeText(this, R.string.hidden_reset, Toast.LENGTH_SHORT).show()
    }

    private fun enteredSafeInterval() = sanitizeInterval(
        safeMinInput.seconds(DEFAULT_SAFE_INTERVAL.minSeconds),
        safeMaxInput.seconds(DEFAULT_SAFE_INTERVAL.maxSeconds)
    )

    private fun enteredWarningInterval() = sanitizeInterval(
        warningMinInput.seconds(DEFAULT_WARNING_INTERVAL.minSeconds),
        warningMaxInput.seconds(DEFAULT_WARNING_INTERVAL.maxSeconds)
    )

    /**
     * Echoes a persisted pair back into the four fields.
     *
     * [SuppressLint] is deliberate, not lazy: lint wants locale-aware number
     * formatting here, but these are *machine* values that are parsed straight back
     * with [toIntOrNull]. A locale that inserts grouping separators would render
     * "1,000" and then fail to read it, silently falling back to the default.
     */
    @SuppressLint("SetTextI18n")
    private fun bind(safe: ScanInterval, warning: ScanInterval) {
        safeMinInput.setText(safe.minSeconds.toString())
        safeMaxInput.setText(safe.maxSeconds.toString())
        warningMinInput.setText(warning.minSeconds.toString())
        warningMaxInput.setText(warning.maxSeconds.toString())
        renderPreview(safe, warning)
    }

    private fun renderPreview(
        safe: ScanInterval = enteredSafeInterval(),
        warning: ScanInterval = enteredWarningInterval()
    ) {
        preview.text = getString(
            R.string.hidden_preview,
            safe.minSeconds,
            safe.maxSeconds,
            warning.minSeconds,
            warning.maxSeconds
        )
    }

    /** Blank or unparseable input falls back to [fallback] rather than blocking. */
    private fun TextInputEditText.seconds(fallback: Int): Int =
        text?.toString()?.trim()?.toIntOrNull() ?: fallback

    private fun afterTextChangedWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }
}
