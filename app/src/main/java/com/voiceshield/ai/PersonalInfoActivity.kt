package com.voiceshield.ai

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * The personal-information page, reached from the top-right person icon on Page 1.
 *
 * It holds the user's name — reported in every alert as "Tên người dùng" — together with the
 * two alert channels (Discord webhook and Zalo Bot) that a high-risk Warning-mode result is
 * mirrored to. Moving the channels here keeps the hidden developer page focused on detection
 * behaviour, while the personal page owns everything about *who* is told and *where*.
 *
 * Every value lives in [ProtectionState] beside the other settings, so it survives process
 * death and is read at alert time with no listener. The switches are applied on change; the
 * webhook URL and bot token are persisted by [save] (and by each "Test" button, which doubles
 * as Save for the field it exercised).
 */
class PersonalInfoActivity : AppCompatActivity() {

    private lateinit var nameInput: TextInputEditText

    // ── Discord webhook alert mirror ────────────────────────────────────────
    private lateinit var discordWebhookInput: TextInputEditText
    private lateinit var discordEnabledSwitch: SwitchMaterial
    private lateinit var discordTestButton: MaterialButton
    private lateinit var discordScreenshotSwitch: SwitchMaterial
    private lateinit var discordScreenshotRow: View

    // ── Zalo Bot alert channel ──────────────────────────────────────────────
    private lateinit var zaloTokenInput: TextInputEditText
    private lateinit var zaloAddIdInput: TextInputEditText
    private lateinit var zaloRecipientList: LinearLayout
    private lateinit var zaloRecipientsEmpty: TextView
    private lateinit var zaloEnabledSwitch: SwitchMaterial
    private lateinit var zaloTestButton: MaterialButton
    private lateinit var zaloFetchIdButton: MaterialButton

    /**
     * Arms the Discord mirror the moment the switch flips. The alert reads the persisted flag
     * at send time, so no Save is required and no broadcast is sent. A field (not inlined) so
     * [reset] can detach it while forcing the switch back off without its toast overwriting the
     * reset confirmation.
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

    /** Arms the Zalo channel the moment the switch flips, exactly like the Discord one. */
    private val zaloEnabledListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            ProtectionState.setZaloEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) R.string.hidden_zalo_on else R.string.hidden_zalo_off,
                Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personal_info)

        nameInput = findViewById(R.id.input_user_name)
        nameInput.setText(ProtectionState.getUserName(this))

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

        // The Discord mirror, bound the way the hidden page used to: switch first (listener
        // detached), URL echoed, then the listeners attached so restoring state is never
        // mistaken for a user action.
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

        findViewById<MaterialButton>(R.id.btn_profile_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_profile_reset).setOnClickListener { reset() }
        findViewById<MaterialButton>(R.id.btn_profile_back).setOnClickListener { finish() }
        discordTestButton.setOnClickListener { testDiscord() }
        zaloTestButton.setOnClickListener { testZalo() }
        zaloFetchIdButton.setOnClickListener { fetchZaloChat() }
        findViewById<MaterialButton>(R.id.btn_zalo_add_id).setOnClickListener {
            addZaloManualRecipient()
        }
        findViewById<MaterialButton>(R.id.btn_zalo_clear_recipients).setOnClickListener {
            ProtectionState.setZaloRecipients(this, emptyList())
            renderZaloRecipients()
            renderZaloState()
        }
    }

    /** Persists the name and the two alert channels, then confirms. */
    private fun save() {
        ProtectionState.setUserName(this, nameInput.text?.toString().orEmpty())
        // The webhook URL and bot token are saved here; their switches are applied on change,
        // so the "Lưu" toast never implies a channel was (dis)armed.
        ProtectionState.setDiscordWebhook(this, discordWebhookInput.text?.toString().orEmpty())
        ProtectionState.setZaloToken(this, zaloTokenInput.text?.toString().orEmpty())
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Restores everything shown on this page to its default: a blank name, and both alert
     * channels off with no webhook, token or recipients. Each programmatic flip detaches its
     * listener so the confirmation toast is not overwritten by the per-switch toasts.
     */
    private fun reset() {
        ProtectionState.setUserName(this, "")
        nameInput.setText("")

        ProtectionState.resetDiscord(this)
        discordEnabledSwitch.setOnCheckedChangeListener(null)
        discordEnabledSwitch.isChecked = false
        discordEnabledSwitch.setOnCheckedChangeListener(discordEnabledListener)
        discordScreenshotSwitch.setOnCheckedChangeListener(null)
        discordScreenshotSwitch.isChecked = false
        discordScreenshotSwitch.setOnCheckedChangeListener(discordScreenshotListener)
        discordWebhookInput.setText("")
        renderDiscordOptions()
        renderDiscordState()

        ProtectionState.resetZalo(this)
        zaloEnabledSwitch.setOnCheckedChangeListener(null)
        zaloEnabledSwitch.isChecked = false
        zaloEnabledSwitch.setOnCheckedChangeListener(zaloEnabledListener)
        zaloTokenInput.setText("")
        ProtectionState.setZaloRecipients(this, emptyList())
        renderZaloRecipients()
        renderZaloState()

        Toast.makeText(this, R.string.profile_reset, Toast.LENGTH_SHORT).show()
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
                        this@PersonalInfoActivity,
                        removeZaloRecipient(
                            ProtectionState.getZaloRecipients(this@PersonalInfoActivity),
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
     * webhook, not a live session. The name is the real one from the name field (blank shows
     * as "—"), and device data is real so the embed shows where it came from.
     */
    private fun sampleDiscordAlert() = DiscordAlert(
        userName = ProtectionState.getUserName(this),
        timestampMs = System.currentTimeMillis(),
        device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "(Android ${android.os.Build.VERSION.RELEASE})"
    )

    private fun afterTextChangedWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }
}
