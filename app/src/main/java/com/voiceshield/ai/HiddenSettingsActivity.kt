package com.voiceshield.ai

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
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
    private lateinit var samplingFpsInput: TextInputEditText
    private lateinit var samplingPreview: TextView
    private lateinit var faceGateSwitch: SwitchMaterial
    private lateinit var discordWebhookInput: TextInputEditText
    private lateinit var discordEnabledSwitch: SwitchMaterial
    private lateinit var discordTestButton: MaterialButton
    private lateinit var discordScreenshotSwitch: SwitchMaterial
    private lateinit var discordScreenshotRow: View
    private lateinit var zaloTokenInput: TextInputEditText
    private lateinit var zaloAddIdInput: TextInputEditText
    private lateinit var zaloRecipientList: LinearLayout
    private lateinit var zaloRecipientsEmpty: TextView
    private lateinit var zaloEnabledSwitch: SwitchMaterial
    private lateinit var zaloTestButton: MaterialButton
    private lateinit var zaloFetchIdButton: MaterialButton
    private lateinit var debugFramesSwitch: SwitchMaterial
    private lateinit var captureAccessibilitySwitch: SwitchMaterial
    private lateinit var captureBackendStatus: TextView

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

    /**
     * Arms the Zalo channel the moment the switch flips, exactly like the Discord one.
     */
    private val zaloEnabledListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setZaloEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) R.string.hidden_zalo_on else R.string.hidden_zalo_off,
                Toast.LENGTH_SHORT
            ).show()
        }

    /**
     * Turns the debug PNG writer on or off the moment it flips. The capture loop re-reads the
     * flag per window, so no Save is needed and no broadcast is sent.
     */
    private val debugFramesListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setDebugFramesEnabled(this, checked)
        }

    /**
     * Switches the frame source between MediaProjection and Accessibility screenshots the moment
     * it flips.
     *
     * The capture service reads the backend only when it starts, so a change made while
     * protection is already running is reported and takes effect on the next activation rather
     * than being applied silently.
     */
    private val captureBackendListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setCaptureBackend(
                this,
                if (checked) CaptureBackend.ACCESSIBILITY else CaptureBackend.MEDIA_PROJECTION
            )
            renderCaptureBackendStatus()
            if (FloatingBubbleService.isProtecting(this)) {
                Toast.makeText(this, R.string.capture_backend_restart_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_settings)

        safeMinInput = findViewById(R.id.input_safe_min)
        safeMaxInput = findViewById(R.id.input_safe_max)
        warningMinInput = findViewById(R.id.input_warning_min)
        warningMaxInput = findViewById(R.id.input_warning_max)
        preview = findViewById(R.id.hidden_preview)
        samplingFpsInput = findViewById(R.id.input_sampling_fps)
        samplingPreview = findViewById(R.id.sampling_preview)
        faceGateSwitch = findViewById(R.id.switch_face_gate)
        discordWebhookInput = findViewById(R.id.input_discord_webhook)
        discordEnabledSwitch = findViewById(R.id.switch_discord_enabled)
        discordTestButton = findViewById(R.id.btn_discord_test)
        discordScreenshotSwitch = findViewById(R.id.switch_discord_screenshot)
        discordScreenshotRow = findViewById(R.id.discord_screenshot_row)
        zaloTokenInput = findViewById(R.id.input_zalo_token)
        zaloAddIdInput = findViewById(R.id.input_zalo_add_id)
        zaloRecipientList = findViewById(R.id.zalo_recipient_list)
        zaloRecipientsEmpty = findViewById(R.id.zalo_recipients_empty)
        zaloEnabledSwitch = findViewById(R.id.switch_zalo_enabled)
        zaloTestButton = findViewById(R.id.btn_zalo_test)
        zaloFetchIdButton = findViewById(R.id.btn_zalo_fetch_id)
        debugFramesSwitch = findViewById(R.id.switch_debug_frames)
        captureAccessibilitySwitch = findViewById(R.id.switch_capture_accessibility)
        captureBackendStatus = findViewById(R.id.capture_backend_status)
        entryGroup = findViewById(R.id.group_scan_mode_entry)
        holdDurationGroup = findViewById(R.id.group_hold_duration)
        autoSelectionGroup = findViewById(R.id.group_auto_mode_selection)
        feedbackGroup = findViewById(R.id.group_mode_feedback)

        bind(ProtectionState.getSafeInterval(this), ProtectionState.getWarningInterval(this))

        // The frame-analysis rate: echoed, watched for a live preview, and saved with the roll
        // windows. The capture service re-reads it every frame, so Save applies immediately.
        samplingFpsInput.setText(formatFps(ProtectionState.getSamplingFps(this)))
        samplingFpsInput.addTextChangedListener(
            afterTextChangedWatcher { renderSamplingPreview() }
        )
        renderSamplingPreview()

        // The debug PNG writer: off by default and applied the instant it flips.
        debugFramesSwitch.isChecked = ProtectionState.isDebugFramesEnabled(this)
        debugFramesSwitch.setOnCheckedChangeListener(debugFramesListener)

        // The frame source: echoed, then the listener attached, with a status line + a shortcut
        // to the Accessibility settings so the developer can see whether it is really enabled.
        captureAccessibilitySwitch.isChecked =
            ProtectionState.getCaptureBackend(this) == CaptureBackend.ACCESSIBILITY
        captureAccessibilitySwitch.setOnCheckedChangeListener(captureBackendListener)
        renderCaptureBackendStatus()

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

        // The Zalo channel, bound the same way: switch and fields echoed first, listeners
        // attached after, and the Test button gated on a usable token + chat id.
        zaloEnabledSwitch.isChecked = ProtectionState.isZaloEnabled(this)
        zaloTokenInput.setText(ProtectionState.getZaloToken(this))
        zaloEnabledSwitch.setOnCheckedChangeListener(zaloEnabledListener)
        zaloTokenInput.addTextChangedListener(afterTextChangedWatcher { renderZaloState() })
        renderZaloRecipients()
        renderZaloState()

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
        zaloTestButton.setOnClickListener { testZalo() }
        zaloFetchIdButton.setOnClickListener { fetchZaloChat() }
        findViewById<MaterialButton>(R.id.btn_zalo_add_id).setOnClickListener { addZaloManualRecipient() }
        findViewById<MaterialButton>(R.id.btn_zalo_clear_recipients).setOnClickListener {
            ProtectionState.setZaloRecipients(this, emptyList())
            renderZaloRecipients()
            renderZaloState()
        }
        findViewById<MaterialButton>(R.id.btn_open_accessibility_settings)
            .setOnClickListener { openAccessibilitySettings() }
    }

    private fun save() {
        val safe = enteredSafeInterval()
        val warning = enteredWarningInterval()
        ProtectionState.setIntervals(this, safe, warning)
        // The frame-analysis rate, saved with the roll windows; the capture loop picks it up on
        // the next frame.
        ProtectionState.setSamplingFps(this, enteredSamplingFps())
        samplingFpsInput.setText(formatFps(ProtectionState.getSamplingFps(this)))
        // The webhook URL is saved alongside the roll windows. Its switch is applied on
        // change, not here, so the "Lưu" toast never implies the mirror was (dis)armed.
        ProtectionState.setDiscordWebhook(this, discordWebhookInput.text?.toString().orEmpty())
        // The Zalo token and chat id are saved the same way; the Zalo switch is applied on
        // change, like Discord's.
        ProtectionState.setZaloToken(this, zaloTokenInput.text?.toString().orEmpty())
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
        // …and the Zalo channel (off, no token, no chat id), detached the same way.
        ProtectionState.resetZalo(this)
        zaloEnabledSwitch.setOnCheckedChangeListener(null)
        zaloEnabledSwitch.isChecked = false
        zaloEnabledSwitch.setOnCheckedChangeListener(zaloEnabledListener)
        zaloTokenInput.setText("")
        ProtectionState.setZaloRecipients(this, emptyList())
        renderZaloRecipients()
        renderZaloState()
        // …and the debug PNG writer back off.
        ProtectionState.setDebugFramesEnabled(this, false)
        debugFramesSwitch.setOnCheckedChangeListener(null)
        debugFramesSwitch.isChecked = false
        debugFramesSwitch.setOnCheckedChangeListener(debugFramesListener)
        // …and the frame source back to MediaProjection.
        ProtectionState.setCaptureBackend(this, CaptureBackend.MEDIA_PROJECTION)
        captureAccessibilitySwitch.setOnCheckedChangeListener(null)
        captureAccessibilitySwitch.isChecked = false
        captureAccessibilitySwitch.setOnCheckedChangeListener(captureBackendListener)
        renderCaptureBackendStatus()
        // …and the four developer_setting_page.md interaction settings, back to single
        // click / 1 s / disabled / disabled. The selected *scan mode* is deliberately left
        // alone: it is not one of these settings, and §8 requires it to survive.
        ProtectionState.resetScanModeInteraction(this)
        resetInteractionGroups()
        // The performance setting returns to 1 fps along with everything else.
        ProtectionState.setSamplingFps(this, DEFAULT_SAMPLING_FPS)
        samplingFpsInput.setText(formatFps(DEFAULT_SAMPLING_FPS))
        renderSamplingPreview()
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
     * Sends a sample text to the bot token + every configured recipient, so the channel can be
     * verified before the switch is armed. Disabled unless a well-formed token and at least one
     * recipient are present.
     */
    private fun testZalo() {
        val token = zaloTokenInput.text?.toString()?.trim().orEmpty()
        val recipients = ProtectionState.getZaloRecipients(this)
        if (!isValidZaloBotToken(token) || recipients.isEmpty()) {
            Toast.makeText(this, R.string.zalo_test_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        // Testing is the moment the developer asserts the token, so it doubles as Save for it.
        ProtectionState.setZaloToken(this, token)
        val sample = sampleDiscordAlert()
        ZaloNotifier.sendAll(
            token,
            recipients,
            zaloAlertMessage(sample.userName, ALERT_RISK_LABEL, sample.timestampMs, sample.device)
        ) { sent, total ->
            val message = if (sent == total) getString(R.string.zalo_test_sent_many, total)
            else getString(R.string.zalo_test_partial, sent, total)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Keeps "Gửi thử" honest: it only fires for a token and at least one recipient. */
    private fun renderZaloState() {
        val tokenValid = isValidZaloBotToken(zaloTokenInput.text?.toString().orEmpty())
        zaloTestButton.isEnabled = tokenValid && ProtectionState.getZaloRecipients(this).isNotEmpty()
        // Fetching the id needs only a token: it is the step *before* a recipient exists.
        zaloFetchIdButton.isEnabled = tokenValid
    }

    /**
     * Rebuilds the recipient list rows. Each row shows the label (with a group marker) and a
     * "Xóa" button, so individual recipients can be removed without clearing the whole list.
     */
    private fun renderZaloRecipients() {
        val recipients = ProtectionState.getZaloRecipients(this)
        zaloRecipientList.removeAllViews()
        zaloRecipientsEmpty.visibility = if (recipients.isEmpty()) View.VISIBLE else View.GONE
        recipients.forEach { recipient ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val label = TextView(this).apply {
                text = getString(
                    if (recipient.isGroup) R.string.zalo_recipient_group else R.string.zalo_recipient_private,
                    recipient.label
                )
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.btn_zalo_remove_recipient)
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    ProtectionState.setZaloRecipients(
                        this@HiddenSettingsActivity,
                        removeZaloRecipient(
                            ProtectionState.getZaloRecipients(this@HiddenSettingsActivity),
                            recipient.chatId
                        )
                    )
                    renderZaloRecipients()
                    renderZaloState()
                }
            }
            row.addView(label)
            row.addView(remove)
            zaloRecipientList.addView(row)
        }
    }

    /** Adds the chat id typed into the manual field as a recipient. */
    private fun addZaloManualRecipient() {
        val chatId = zaloAddIdInput.text?.toString()?.trim().orEmpty()
        if (chatId.isEmpty()) {
            Toast.makeText(this, R.string.zalo_add_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        persistZaloRecipient(ZaloRecipient(chatId, chatId))
        zaloAddIdInput.setText("")
    }

    /** Adds (or replaces) one recipient and refreshes the list + buttons. */
    private fun persistZaloRecipient(recipient: ZaloRecipient) {
        val updated = addZaloRecipient(ProtectionState.getZaloRecipients(this), recipient)
        ProtectionState.setZaloRecipients(this, updated)
        renderZaloRecipients()
        renderZaloState()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Shows which frame source is selected and, for the accessibility backend, whether the app's
     * accessibility service is actually enabled — the one thing the developer must set up
     * outside the app.
     */
    private fun renderCaptureBackendStatus() {
        val backend = ProtectionState.getCaptureBackend(this)
        captureBackendStatus.text = when (backend) {
            CaptureBackend.MEDIA_PROJECTION -> getString(R.string.capture_status_projection)
            CaptureBackend.ACCESSIBILITY ->
                if (isScreenshotAccessibilityServiceEnabled(this)) {
                    getString(R.string.capture_status_accessibility_ready)
                } else {
                    getString(R.string.capture_status_accessibility_not_enabled)
                }
        }
    }

    /** Opens the system Accessibility settings, where the screenshot service is enabled. */
    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                Toast.makeText(this, R.string.accessibility_settings_unavailable, Toast.LENGTH_SHORT)
                    .show()
            }
    }

    override fun onResume() {
        super.onResume()
        // The developer may have just toggled the service in Settings; refresh the status line.
        renderCaptureBackendStatus()
    }

    /**
     * "Lấy Chat ID" — reads the most recent message sent to the bot via `getUpdates` and **adds**
     * it to the recipient list (de-duplicating). The chat type is stored so a group shows as
     * "Nhóm"; the sender's display name becomes the label.
     *
     * Zalo only reports `chat.id` once the user has written to the bot, and `getUpdates` is
     * disabled while a webhook is configured, so the failure toasts say exactly which step is
     * missing rather than a bare HTTP code.
     */
    private fun fetchZaloChat() {
        val token = zaloTokenInput.text?.toString()?.trim().orEmpty()
        if (!isValidZaloBotToken(token)) {
            Toast.makeText(this, R.string.zalo_fetch_invalid_token, Toast.LENGTH_SHORT).show()
            return
        }
        // Persist the token first: fetching is the moment the developer asserts it is correct.
        ProtectionState.setZaloToken(this, token)
        Toast.makeText(this, R.string.zalo_fetch_searching, Toast.LENGTH_SHORT).show()
        ZaloNotifier.fetchLatestChat(token) { chat, code ->
            when {
                chat != null -> {
                    persistZaloRecipient(ZaloRecipient(chat.chatId, chat.name, chat.isGroup))
                    Toast.makeText(
                        this,
                        getString(R.string.zalo_fetch_found, chat.chatId),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                code == -1 ->
                    Toast.makeText(this, R.string.zalo_fetch_network, Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(this, R.string.zalo_fetch_none, Toast.LENGTH_SHORT).show()
            }
        }
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

    /**
     * The frame rate the developer typed, tolerant of a comma decimal separator (a Vietnamese
     * keyboard produces "0,5"). Blank or unparseable input falls back to the default; the
     * value is sanitised again when it is stored.
     */
    private fun enteredSamplingFps(): Float =
        samplingFpsInput.text?.toString()?.trim()?.replace(',', '.')?.toFloatOrNull()
            ?: DEFAULT_SAMPLING_FPS

    /** Shows a whole number without a trailing ".0" (1, not 1.0), else the decimal as typed. */
    private fun formatFps(fps: Float): String =
        if (fps % 1f == 0f) fps.toInt().toString() else fps.toString()

    /**
     * Live preview of the rate that will actually be saved, sanitisation included, so a value
     * outside 0.5–5 is seen to be clamped before the developer commits to it. Below 1 fps the
     * equivalent "one frame every N seconds" is spelled out, since that is the form the
     * overheating case cares about.
     */
    private fun renderSamplingPreview() {
        val fps = sanitizeSamplingFps(enteredSamplingFps())
        val intervalMs = samplingIntervalMs(fps)
        samplingPreview.text = if (intervalMs >= 1_000L) {
            getString(R.string.hidden_sampling_preview_slow, formatFps(fps), intervalMs / 1_000L)
        } else {
            getString(R.string.hidden_sampling_preview, formatFps(fps))
        }
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
