package com.voiceshield.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/** How many taps on the version footer open this page. */
const val TRIPLE_CLICK_COUNT = 3

/**
 * Whole burst window, in milliseconds, measured from the *first* tap. All three taps
 * must land inside it.
 *
 * It also doubles as the deferral for the §1 single-click navigation to Page 2 (see
 * [DEFAULT_SCAN_MODE_ENTRY]): a lone tap has to wait until the burst closes before it
 * navigates, or taps 2 and 3 would land on Page 2 instead of the footer and the hidden
 * page would become unreachable.
 */
const val TRIPLE_CLICK_WINDOW_MS = 900L

/**
 * Fallback for the "Version 1.0.1" hold, used when no explicit duration is supplied.
 *
 * developer_setting_page.md §2 makes this a *setting*: the hidden page offers 0.5 s, 1 s and
 * 2 s, defaulting to 1 s, and the selected value drives every hold gesture in the spec. So
 * the real threshold is always [ProtectionState.holdDurationMs]; this constant is only the
 * default a pure call falls back to.
 *
 * A half-second floor is still deliberately above an ordinary tap (~80 ms), so the hold never
 * fires by accident — the reason the original fixed value was two seconds.
 */
val DEFAULT_VERSION_HOLD_MS: Long = DEFAULT_HOLD_DURATION.milliseconds

/**
 * Pure rule for the version-footer hold (developer_setting_page.md §2).
 *
 * The hold counts only when it ran the full [requiredMs] *and* was never broken by the
 * finger travelling past the touch slop — otherwise a scroll or a shaky grip would open
 * the scan-mode settings page. Top-level and Android-free, exactly like [isTripleClick],
 * so the gesture is unit-testable on the local JVM.
 *
 * [requiredMs] is the configured [HoldDuration], which is why it is a parameter and not a
 * constant here.
 */
fun isVersionLongPress(
    heldMs: Long,
    movedPastSlop: Boolean,
    requiredMs: Long = DEFAULT_VERSION_HOLD_MS
): Boolean = !movedPastSlop && heldMs >= requiredMs

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
 *
 * It also carries the "Chế độ nhận diện khuôn mặt" gate, which makes a double-tapped
 * scan wait for a real human face on screen before the random window — and therefore
 * before any warning — can start. See [shouldArmScanTimer].
 *
 * developer_setting_page.md adds four more single-choice groups here, all applied the
 * moment they are picked and all persisted:
 *  - §1 how the "Chế độ quét" page is opened from the version footer;
 *  - §2 how long every press-and-hold in the spec has to last;
 *  - §3 the one automatic scan-mode selection method, if any;
 *  - §7 what feedback, if any, follows a real mode change.
 *
 * Each is a [RadioGroup] holding a single enum value, so "exactly one opening method" and
 * "at most one automatic method" cannot be violated even in principle. None of them stores
 * or touches the selected [ScanMode]; see [ScanModeInteractionSettings].
 */
class HiddenSettingsActivity : AppCompatActivity() {

    private lateinit var safeMinInput: TextInputEditText
    private lateinit var safeMaxInput: TextInputEditText
    private lateinit var warningMinInput: TextInputEditText
    private lateinit var warningMaxInput: TextInputEditText
    private lateinit var preview: TextView
    private lateinit var faceGateSwitch: SwitchMaterial
    private lateinit var discordWebhookInput: TextInputEditText
    private lateinit var discordEnabledSwitch: SwitchMaterial
    private lateinit var discordTestButton: MaterialButton
    private lateinit var discordScreenshotSwitch: SwitchMaterial
    private lateinit var discordScreenshotRow: View

    // developer_setting_page.md §1–§3 and §7 — four single-choice groups. The RadioButtons are
    // direct children of their RadioGroup, so checking one unchecks the rest for free; that is
    // what makes "Exactly one opening method can be selected" and "At most one automatic
    // mode-selection method can be selected" true in the UI as well as in the stored enum.
    private lateinit var entryGroup: RadioGroup
    private lateinit var holdDurationGroup: RadioGroup
    private lateinit var autoSelectionGroup: RadioGroup
    private lateinit var feedbackGroup: RadioGroup

    /**
     * The interaction settings currently on screen, rebuilt from the checked radio buttons.
     *
     * Read as one value so a change to any single group is persisted as a whole and the four
     * settings can never be left half-written.
     */
    private fun enteredInteraction() = ScanModeInteractionSettings(
        entry = scanModeEntryForId(entryGroup.checkedRadioButtonId),
        holdDuration = holdDurationForId(holdDurationGroup.checkedRadioButtonId),
        autoSelection = autoModeSelectionForId(autoSelectionGroup.checkedRadioButtonId),
        feedback = modeFeedbackForId(feedbackGroup.checkedRadioButtonId)
    )

    /**
     * Applies the interaction settings the moment any group changes, and says so.
     *
     * Held in a field rather than inlined so [resetInteractionGroups] can detach it while
     * forcing the radios back to their defaults — otherwise the programmatic check would fire
     * this listener four times over and its toast would overwrite the reset confirmation.
     */
    private val interactionListener = RadioGroup.OnCheckedChangeListener { _, _ ->
        ProtectionState.setScanModeInteraction(this, enteredInteraction())
        Toast.makeText(this, R.string.hidden_interaction_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies the gate the moment it is flipped and confirms it with a toast.
     *
     * Held in a field rather than inlined so [reset] can detach it while forcing the
     * switch back to off — otherwise the programmatic change would fire this listener
     * and its "đã tắt" toast would overwrite the reset confirmation.
     */
    private val faceGateListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setFaceGateEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) R.string.hidden_face_gate_on else R.string.hidden_face_gate_off,
                Toast.LENGTH_SHORT
            ).show()
        }

    /**
     * Arms the Discord mirror the moment the switch flips, exactly like [faceGateListener] —
     * the alert reads the persisted flag at send time, so no Save is required and no
     * broadcast is sent. A field (not inlined) so [reset] can detach it while forcing the
     * switch back to off without its "đã tắt" toast overwriting the reset confirmation.
     */
    private val discordEnabledListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setDiscordEnabled(this, checked)
            // The screenshot option exists only as a child of the mirror, so it appears and
            // disappears with the switch that arms it.
            renderDiscordOptions()
            Toast.makeText(
                this,
                if (checked) R.string.hidden_discord_on else R.string.hidden_discord_off,
                Toast.LENGTH_SHORT
            ).show()
        }

    /**
     * Persists the screenshot option the moment it flips. Unlike the mirror switch it sends
     * no toast: it is a sub-option of a switch that already confirmed itself, and the capture
     * path re-reads the flag per frame with no listener.
     */
    private val discordScreenshotListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setDiscordScreenshotEnabled(this, checked)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_settings)

        safeMinInput = findViewById(R.id.input_safe_min)
        safeMaxInput = findViewById(R.id.input_safe_max)
        warningMinInput = findViewById(R.id.input_warning_min)
        warningMaxInput = findViewById(R.id.input_warning_max)
        preview = findViewById(R.id.hidden_preview)
        faceGateSwitch = findViewById(R.id.switch_face_gate)
        discordWebhookInput = findViewById(R.id.input_discord_webhook)
        discordEnabledSwitch = findViewById(R.id.switch_discord_enabled)
        discordTestButton = findViewById(R.id.btn_discord_test)
        discordScreenshotSwitch = findViewById(R.id.switch_discord_screenshot)
        discordScreenshotRow = findViewById(R.id.discord_screenshot_row)
        entryGroup = findViewById(R.id.group_scan_mode_entry)
        holdDurationGroup = findViewById(R.id.group_hold_duration)
        autoSelectionGroup = findViewById(R.id.group_auto_mode_selection)
        feedbackGroup = findViewById(R.id.group_mode_feedback)

        bind(ProtectionState.getSafeInterval(this), ProtectionState.getWarningInterval(this))

        // The gate is applied the instant it is flipped, not on "Save": a running scan
        // listens for ACTION_FACE_GATE_CHANGED and follows the new setting immediately.
        faceGateSwitch.isChecked = ProtectionState.isFaceGateEnabled(this)
        faceGateSwitch.setOnCheckedChangeListener(faceGateListener)

        // The Discord mirror, bound the same way: switch first (listener detached), URL
        // echoed, then the listeners attached so restoring state is never mistaken for a
        // user action.
        discordEnabledSwitch.isChecked = ProtectionState.isDiscordEnabled(this)
        discordWebhookInput.setText(ProtectionState.getDiscordWebhook(this))
        discordEnabledSwitch.setOnCheckedChangeListener(discordEnabledListener)
        discordWebhookInput.addTextChangedListener(
            afterTextChangedWatcher { renderDiscordState() }
        )
        // The screenshot sub-option: echoed before its listener is attached, and shown only
        // when the mirror is armed.
        discordScreenshotSwitch.isChecked = ProtectionState.isDiscordScreenshotEnabled(this)
        discordScreenshotSwitch.setOnCheckedChangeListener(discordScreenshotListener)
        renderDiscordState()
        renderDiscordOptions()

        // §1–§3/§7 are applied on change for the same reason, and bound *before* the
        // listener is attached so echoing the persisted selection cannot re-save it.
        bindInteraction(ProtectionState.getScanModeInteraction(this))
        listOf(entryGroup, holdDurationGroup, autoSelectionGroup, feedbackGroup)
            .forEach { it.setOnCheckedChangeListener(interactionListener) }

        // Live preview of what will actually be saved, sanitisation included, so a
        // clamped or swapped pair is visible before the user commits to it.
        val watcher = afterTextChangedWatcher { renderPreview() }
        listOf(safeMinInput, safeMaxInput, warningMinInput, warningMaxInput)
            .forEach { it.addTextChangedListener(watcher) }
        renderPreview()

        findViewById<MaterialButton>(R.id.btn_hidden_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_hidden_reset).setOnClickListener { reset() }
        findViewById<MaterialButton>(R.id.btn_hidden_back).setOnClickListener { finish() }
        discordTestButton.setOnClickListener { testDiscord() }
    }

    private fun save() {
        val safe = enteredSafeInterval()
        val warning = enteredWarningInterval()
        ProtectionState.setIntervals(this, safe, warning)
        // The webhook URL is saved alongside the roll windows. Its switch is applied on
        // change, not here, so the "Lưu" toast never implies the mirror was (dis)armed.
        ProtectionState.setDiscordWebhook(this, discordWebhookInput.text?.toString().orEmpty())
        // Echo the normalised pair back: what is on screen is then exactly what is
        // persisted, even when the input needed clamping or swapping.
        bind(safe, warning)
        Toast.makeText(this, R.string.hidden_saved, Toast.LENGTH_SHORT).show()
    }

    private fun reset() {
        ProtectionState.resetIntervals(this)
        // "Khôi phục mặc định" restores *every* hidden option, and the face gate's
        // default is off: enable_floating_bubble.md arms the random window on the
        // double-tap itself, with no face precondition.
        ProtectionState.setFaceGateEnabled(this, false)
        faceGateSwitch.setOnCheckedChangeListener(null)
        faceGateSwitch.isChecked = false
        faceGateSwitch.setOnCheckedChangeListener(faceGateListener)
        // …and the Discord mirror (off, no webhook), detached the same way so its "đã tắt"
        // toast does not overwrite the reset confirmation.
        ProtectionState.resetDiscord(this)
        discordEnabledSwitch.setOnCheckedChangeListener(null)
        discordEnabledSwitch.isChecked = false
        discordEnabledSwitch.setOnCheckedChangeListener(discordEnabledListener)
        discordScreenshotSwitch.setOnCheckedChangeListener(null)
        discordScreenshotSwitch.isChecked = false
        discordScreenshotSwitch.setOnCheckedChangeListener(discordScreenshotListener)
        discordWebhookInput.setText("")
        renderDiscordOptions()
        // …and the four developer_setting_page.md interaction settings, back to single
        // click / 1 s / disabled / disabled. The selected *scan mode* is deliberately left
        // alone: it is not one of these settings, and §8 requires it to survive.
        ProtectionState.resetScanModeInteraction(this)
        resetInteractionGroups()
        bind(DEFAULT_SAFE_INTERVAL, DEFAULT_WARNING_INTERVAL)
        Toast.makeText(this, R.string.hidden_reset, Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends a sample embed to whatever URL is currently in the field, so the webhook can be
     * verified before the switch is armed. The button is disabled unless the field holds a
     * valid Discord webhook URL, so an invalid send is only reachable through a stale UI.
     */
    private fun testDiscord() {
        val url = discordWebhookInput.text?.toString()?.trim().orEmpty()
        if (!isValidDiscordWebhook(url)) {
            Toast.makeText(this, R.string.discord_test_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        // Remember the field as the configured webhook too: testing is the moment the
        // developer asserts this is the URL, so it doubles as Save for the URL.
        ProtectionState.setDiscordWebhook(this, url)
        // If a face-bearing frame is cached and the screenshot option is on, include it so the
        // test also exercises the multipart path.
        val screenshot =
            if (ProtectionState.isDiscordScreenshotEnabled(this)) FaceFrameCache.latestJpeg else null
        val payload = discordEmbedPayload(
            sampleDiscordAlert(),
            isTest = true,
            hasImage = screenshot != null
        )
        DiscordNotifier.send(url, payload, screenshot) { ok, code ->
            val message = if (ok) getString(R.string.discord_test_sent)
            else getString(R.string.discord_test_failed, code)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Keeps "Gửi thử" honest: it only fires for a URL that would really be accepted. */
    private fun renderDiscordState() {
        discordTestButton.isEnabled =
            isValidDiscordWebhook(discordWebhookInput.text?.toString().orEmpty())
    }

    /**
     * Shows the screenshot sub-option only while the mirror is armed, per the product rule
     * that it is a child of "Gửi cảnh báo tới Discord". Called on create and on every flip of
     * that switch.
     */
    private fun renderDiscordOptions() {
        discordScreenshotRow.visibility = if (discordEnabledSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * The metadata a "Test" send carries — fixed sample values, since a test is about the
     * webhook, not a live session. The name is the real one from the personal-information
     * page (blank shows as "—"), and device data is real so the embed shows where it came from.
     */
    private fun sampleDiscordAlert() = DiscordAlert(
        userName = ProtectionState.getUserName(this),
        timestampMs = System.currentTimeMillis(),
        device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(Android ${android.os.Build.VERSION.RELEASE})"
    )

    /**
     * Echoes [settings] back into the four radio groups with the listener detached, so
     * restoring a selection is never mistaken for the user making one.
     */
    private fun resetInteractionGroups() {
        detachInteractionListener()
        bindInteraction(DEFAULT_SCAN_MODE_INTERACTION)
        attachInteractionListener()
    }

    private fun detachInteractionListener() {
        listOf(entryGroup, holdDurationGroup, autoSelectionGroup, feedbackGroup)
            .forEach { it.setOnCheckedChangeListener(null) }
    }

    private fun attachInteractionListener() {
        listOf(entryGroup, holdDurationGroup, autoSelectionGroup, feedbackGroup)
            .forEach { it.setOnCheckedChangeListener(interactionListener) }
    }

    private fun bindInteraction(settings: ScanModeInteractionSettings) {
        entryGroup.check(idForScanModeEntry(settings.entry))
        holdDurationGroup.check(idForHoldDuration(settings.holdDuration))
        autoSelectionGroup.check(idForAutoModeSelection(settings.autoSelection))
        feedbackGroup.check(idForModeFeedback(settings.feedback))
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

// ── Radio id <-> setting mapping (developer_setting_page.md §1–§3, §7) ────────
// Kept as top-level functions in the same convention as [sanitizeInterval] and
// [isTripleClick]: an unknown or missing id falls back to the spec default instead of
// throwing, so a layout edit that drops a button degrades to the documented behaviour
// rather than crashing the hidden page.

/** §1 radio id -> opening method. */
fun scanModeEntryForId(id: Int): ScanModeEntry = when (id) {
    R.id.radio_entry_single_click -> ScanModeEntry.SINGLE_CLICK
    R.id.radio_entry_press_hold -> ScanModeEntry.PRESS_AND_HOLD
    R.id.radio_entry_press_hold_swipe_up -> ScanModeEntry.PRESS_HOLD_SWIPE_UP
    else -> DEFAULT_SCAN_MODE_ENTRY
}

/** §1 opening method -> radio id. */
fun idForScanModeEntry(entry: ScanModeEntry): Int = when (entry) {
    ScanModeEntry.SINGLE_CLICK -> R.id.radio_entry_single_click
    ScanModeEntry.PRESS_AND_HOLD -> R.id.radio_entry_press_hold
    ScanModeEntry.PRESS_HOLD_SWIPE_UP -> R.id.radio_entry_press_hold_swipe_up
}

/** §2 radio id -> hold duration. */
fun holdDurationForId(id: Int): HoldDuration = when (id) {
    R.id.radio_hold_half_second -> HoldDuration.HALF_SECOND
    R.id.radio_hold_one_second -> HoldDuration.ONE_SECOND
    R.id.radio_hold_two_seconds -> HoldDuration.TWO_SECONDS
    else -> DEFAULT_HOLD_DURATION
}

/** §2 hold duration -> radio id. */
fun idForHoldDuration(duration: HoldDuration): Int = when (duration) {
    HoldDuration.HALF_SECOND -> R.id.radio_hold_half_second
    HoldDuration.ONE_SECOND -> R.id.radio_hold_one_second
    HoldDuration.TWO_SECONDS -> R.id.radio_hold_two_seconds
}

/** §3 radio id -> automatic mode-selection method. */
fun autoModeSelectionForId(id: Int): AutoModeSelection = when (id) {
    R.id.radio_auto_disabled -> AutoModeSelection.DISABLED
    R.id.radio_auto_swipe -> AutoModeSelection.SWIPE_LEFT_RIGHT
    R.id.radio_auto_bubble_position -> AutoModeSelection.BUBBLE_POSITION
    else -> DEFAULT_AUTO_MODE_SELECTION
}

/** §3 automatic mode-selection method -> radio id. */
fun idForAutoModeSelection(selection: AutoModeSelection): Int = when (selection) {
    AutoModeSelection.DISABLED -> R.id.radio_auto_disabled
    AutoModeSelection.SWIPE_LEFT_RIGHT -> R.id.radio_auto_swipe
    AutoModeSelection.BUBBLE_POSITION -> R.id.radio_auto_bubble_position
}

/** §7 radio id -> mode-change feedback. */
fun modeFeedbackForId(id: Int): ModeFeedback = when (id) {
    R.id.radio_feedback_disabled -> ModeFeedback.DISABLED
    R.id.radio_feedback_toast -> ModeFeedback.TOAST
    R.id.radio_feedback_toast_vibration -> ModeFeedback.TOAST_AND_VIBRATION
    else -> DEFAULT_MODE_FEEDBACK
}

/** §7 mode-change feedback -> radio id. */
fun idForModeFeedback(feedback: ModeFeedback): Int = when (feedback) {
    ModeFeedback.DISABLED -> R.id.radio_feedback_disabled
    ModeFeedback.TOAST -> R.id.radio_feedback_toast
    ModeFeedback.TOAST_AND_VIBRATION -> R.id.radio_feedback_toast_vibration
}
