package com.voiceshield.ai

import android.content.Context
import android.content.Intent
import kotlin.random.Random

/** Scan modes selectable on Page 2 (Settings). */
enum class ScanMode { SAFE, WARNING }

/**
 * An inclusive whole-second roll window for one scan mode.
 *
 * The defaults come from enable_floating_bubble.md §Business Rules (Safe 4–7 s,
 * Warning 5–8 s); the hidden developer page can persist an override.
 */
data class ScanInterval(val minSeconds: Int, val maxSeconds: Int)

// enable_floating_bubble.md §Business Rules detection windows, in whole
// seconds (inclusive bounds): Safe Mode 4–7 s, Warning Mode 5–8 s.
val DEFAULT_SAFE_INTERVAL = ScanInterval(4, 7)
val DEFAULT_WARNING_INTERVAL = ScanInterval(5, 8)

/** Hard limits the hidden developer page cannot push a window past. */
const val SCAN_INTERVAL_FLOOR_SECONDS = 1
const val SCAN_INTERVAL_CEILING_SECONDS = 600

/**
 * Pure normalisation for a developer-entered window.
 *
 * The hidden page accepts free-form integer input, so the rule has to turn any
 * pair into something safe to roll: both bounds are clamped into
 * [SCAN_INTERVAL_FLOOR_SECONDS]..[SCAN_INTERVAL_CEILING_SECONDS], and a reversed
 * pair is swapped rather than rejected. Swapping is what guarantees
 * `max - min + 1` is always >= 1 — a zero or negative span would make
 * `Random.nextLong` throw `IllegalArgumentException` inside the scan timer.
 */
fun sanitizeInterval(minSeconds: Int, maxSeconds: Int): ScanInterval {
    val lo = minSeconds.coerceIn(SCAN_INTERVAL_FLOOR_SECONDS, SCAN_INTERVAL_CEILING_SECONDS)
    val hi = maxSeconds.coerceIn(SCAN_INTERVAL_FLOOR_SECONDS, SCAN_INTERVAL_CEILING_SECONDS)
    return if (lo <= hi) ScanInterval(lo, hi) else ScanInterval(hi, lo)
}

/** Which configured window applies to [mode]. */
fun intervalFor(mode: ScanMode, safe: ScanInterval, warning: ScanInterval): ScanInterval =
    when (mode) {
        ScanMode.SAFE -> safe
        ScanMode.WARNING -> warning
    }

/**
 * Random delay, in milliseconds, before the scan popup appears for [mode]: by
 * default SAFE rolls 4–7 s and WARNING rolls 5–8 s.
 *
 * Deliberately a top-level pure function with no Android types so it is
 * unit-testable on the JVM without Robolectric. The windows are parameters — not
 * constants — because the hidden developer page can override them.
 */
fun scanDelayMs(
    mode: ScanMode,
    safe: ScanInterval = DEFAULT_SAFE_INTERVAL,
    warning: ScanInterval = DEFAULT_WARNING_INTERVAL,
    random: Random = Random
): Long {
    val (minSeconds, maxSeconds) = intervalFor(mode, safe, warning)
    // nextLong(until) is exclusive of `until`, so widen by one to include the max.
    val rolled = minSeconds + random.nextLong(maxSeconds - minSeconds + 1L)
    return rolled * 1000L
}

/**
 * Shared source of truth for the protection switch, the scan mode and the scan
 * roll windows, read by the Home screen, the Settings screen, the hidden
 * developer page and the floating bubble service. Backed by SharedPreferences so
 * all of it survives process death.
 */
object ProtectionState {
    private const val PREFERENCES = "protection_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_MODE = "mode"
    private const val KEY_SAFE_MIN = "safe_interval_min_seconds"
    private const val KEY_SAFE_MAX = "safe_interval_max_seconds"
    private const val KEY_WARNING_MIN = "warning_interval_min_seconds"
    private const val KEY_WARNING_MAX = "warning_interval_max_seconds"
    private const val KEY_FACE_GATE = "face_gate_enabled"

    // Hidden developer page — Discord alert mirror.
    private const val KEY_DISCORD_WEBHOOK = "discord_webhook"
    private const val KEY_DISCORD_ENABLED = "discord_enabled"
    private const val KEY_DISCORD_SCREENSHOT = "discord_screenshot"

    // Personal-information page.
    private const val KEY_USER_NAME = "user_name"

    // developer_setting_page.md §1–§3 and §7. Stored as enum *names* rather than ordinals,
    // so reordering an enum can never silently re-map a user's saved choice.
    private const val KEY_SCAN_MODE_ENTRY = "scan_mode_entry"
    private const val KEY_HOLD_DURATION = "hold_duration"
    private const val KEY_AUTO_MODE_SELECTION = "auto_mode_selection"
    private const val KEY_MODE_FEEDBACK = "mode_feedback"

    const val ACTION_CHANGED = "com.voiceshield.ai.PROTECTION_STATE_CHANGED"
    const val EXTRA_ACTIVE = "active"

    const val ACTION_MODE_CHANGED = "com.voiceshield.ai.SCAN_MODE_CHANGED"
    const val EXTRA_MODE = "mode"

    /** Hidden developer page saved a new roll window; a running scan re-arms. */
    const val ACTION_INTERVALS_CHANGED = "com.voiceshield.ai.SCAN_INTERVALS_CHANGED"

    /** Hidden developer page toggled "Chế độ nhận diện khuôn mặt". */
    const val ACTION_FACE_GATE_CHANGED = "com.voiceshield.ai.FACE_GATE_CHANGED"
    const val EXTRA_FACE_GATE = "face_gate_enabled"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
        context.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ACTIVE, active)
        )
    }

    /** Defaults to [ScanMode.SAFE] so a fresh install shows the reassuring popup. */
    fun getMode(context: Context): ScanMode {
        val stored = prefs(context).getString(KEY_MODE, ScanMode.SAFE.name)
        return runCatching { ScanMode.valueOf(stored.orEmpty()) }.getOrDefault(ScanMode.SAFE)
    }

    fun setMode(context: Context, mode: ScanMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        context.sendBroadcast(
            Intent(ACTION_MODE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MODE, mode.name)
        )
    }

    /**
     * The Safe-Mode roll window. Unset or corrupt values fall back to the spec
     * default, and the result is always re-sanitised so a hand-edited
     * SharedPreferences file can never produce a throwing timer.
     */
    fun getSafeInterval(context: Context): ScanInterval = sanitizeInterval(
        prefs(context).getInt(KEY_SAFE_MIN, DEFAULT_SAFE_INTERVAL.minSeconds),
        prefs(context).getInt(KEY_SAFE_MAX, DEFAULT_SAFE_INTERVAL.maxSeconds)
    )

    /** The Warning-Mode roll window; see [getSafeInterval]. */
    fun getWarningInterval(context: Context): ScanInterval = sanitizeInterval(
        prefs(context).getInt(KEY_WARNING_MIN, DEFAULT_WARNING_INTERVAL.minSeconds),
        prefs(context).getInt(KEY_WARNING_MAX, DEFAULT_WARNING_INTERVAL.maxSeconds)
    )

    /** Persists both windows, sanitised, and tells a running scan to re-roll. */
    fun setIntervals(context: Context, safe: ScanInterval, warning: ScanInterval) {
        val normalizedSafe = sanitizeInterval(safe.minSeconds, safe.maxSeconds)
        val normalizedWarning = sanitizeInterval(warning.minSeconds, warning.maxSeconds)
        prefs(context).edit()
            .putInt(KEY_SAFE_MIN, normalizedSafe.minSeconds)
            .putInt(KEY_SAFE_MAX, normalizedSafe.maxSeconds)
            .putInt(KEY_WARNING_MIN, normalizedWarning.minSeconds)
            .putInt(KEY_WARNING_MAX, normalizedWarning.maxSeconds)
            .apply()
        context.sendBroadcast(
            Intent(ACTION_INTERVALS_CHANGED).setPackage(context.packageName)
        )
    }

    /** Restores the enable_floating_bubble.md defaults (Safe 4–7 s, Warning 5–8 s). */
    fun resetIntervals(context: Context) =
        setIntervals(context, DEFAULT_SAFE_INTERVAL, DEFAULT_WARNING_INTERVAL)

    /**
     * The hidden developer option "Chế độ nhận diện khuôn mặt".
     *
     * Defaults to **off**, which is the behaviour enable_floating_bubble.md specifies: a
     * double-tap arms the random popup window immediately. When it is on, the scan waits
     * for a real human face to appear on screen before that window — and therefore
     * before any warning — can start. The rule itself lives in [shouldArmScanTimer].
     */
    fun isFaceGateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FACE_GATE, false)

    /** Persists the face gate and tells a running scan to follow the new setting. */
    fun setFaceGateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FACE_GATE, enabled).apply()
        context.sendBroadcast(
            Intent(ACTION_FACE_GATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_FACE_GATE, enabled)
        )
    }

    /**
     * The hidden developer option "DISCORD WEBHOOK" — the URL a Warning-mode high-risk
     * result is mirrored to.
     *
     * Defaults to blank. A blank value (or [isDiscordEnabled] off) makes the mirror a
     * no-op; nothing is ever sent without both being set.
     */
    fun getDiscordWebhook(context: Context): String =
        prefs(context).getString(KEY_DISCORD_WEBHOOK, "").orEmpty()

    /** Persists the webhook URL. Blank clears it. No broadcast: only the next alert reads it. */
    fun setDiscordWebhook(context: Context, url: String) {
        prefs(context).edit().putString(KEY_DISCORD_WEBHOOK, url.trim()).apply()
    }

    /** The hidden developer switch that arms the Discord mirror. Defaults to **off**. */
    fun isDiscordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCORD_ENABLED, false)

    /** Persists the Discord mirror switch. No broadcast: the alert reads it at send time. */
    fun setDiscordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCORD_ENABLED, enabled).apply()
    }

    /** Restores the hidden Discord mirror to its default (off, no webhook). */
    fun resetDiscord(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_DISCORD_ENABLED, false)
            .putString(KEY_DISCORD_WEBHOOK, "")
            .putBoolean(KEY_DISCORD_SCREENSHOT, false)
            .apply()
    }

    /**
     * Hidden developer option "Gửi ảnh chụp màn hình" — attach the newest face-bearing
     * screen frame to the Discord alert.
     *
     * Defaults to **off** and is only shown on the hidden page while the Discord mirror
     * itself is on. When on, [FaceFrameCache] holds the most recent frame that contained a
     * face and the alert attaches it.
     */
    fun isDiscordScreenshotEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCORD_SCREENSHOT, false)

    /** Persists the screenshot toggle. No broadcast: the capture path reads it per frame. */
    fun setDiscordScreenshotEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCORD_SCREENSHOT, enabled).apply()
    }

    /**
     * Personal-information page — the user's display name, shown in the Discord alert as
     * "Tên người dùng". Defaults to blank; a blank name is reported as "—" in the embed.
     */
    fun getUserName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, "").orEmpty()

    /** Persists the user's name. */
    fun setUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    /**
     * The developer_setting_page.md §1–§3/§7 interaction settings, read as one bundle.
     *
     * Each value is parsed defensively ([scanModeEntryFrom] and friends), so a hand-edited
     * SharedPreferences file, or an enum renamed by a later refactor, degrades to the spec
     * default instead of crashing the very gesture it configures.
     *
     * Deliberately does *not* include the selected [ScanMode]: keeping the two apart is what
     * makes "Changing the hold duration does not change the selected scan mode" impossible
     * to get wrong.
     */
    fun getScanModeInteraction(context: Context) = ScanModeInteractionSettings(
        entry = scanModeEntryFrom(prefs(context).getString(KEY_SCAN_MODE_ENTRY, null)),
        holdDuration = holdDurationFrom(prefs(context).getString(KEY_HOLD_DURATION, null)),
        autoSelection = autoModeSelectionFrom(
            prefs(context).getString(KEY_AUTO_MODE_SELECTION, null)
        ),
        feedback = modeFeedbackFrom(prefs(context).getString(KEY_MODE_FEEDBACK, null))
    )

    /** §1: how the "Chế độ quét" page is opened from the version footer. */
    fun getScanModeEntry(context: Context): ScanModeEntry =
        getScanModeInteraction(context).entry

    /** §2: the press-and-hold threshold shared by every hold gesture in the spec. */
    fun getHoldDuration(context: Context): HoldDuration =
        getScanModeInteraction(context).holdDuration

    /** §2 in milliseconds — the form the gesture plumbing actually schedules with. */
    fun holdDurationMs(context: Context): Long = getHoldDuration(context).milliseconds

    /** §3: the single automatic mode-selection method, if any. */
    fun getAutoModeSelection(context: Context): AutoModeSelection =
        getScanModeInteraction(context).autoSelection

    /** §7: what the app does after the scan mode has really changed. */
    fun getModeFeedback(context: Context): ModeFeedback =
        getScanModeInteraction(context).feedback

    /**
     * Persists the four interaction settings.
     *
     * No broadcast is sent: unlike [setMode] and [setIntervals], nothing has to react while
     * running. The footer re-reads the bundle on every ACTION_DOWN and the bubble re-reads
     * the auto-selection on every completed snap, so a change made on the hidden page takes
     * effect on the next gesture without any listener.
     */
    fun setScanModeInteraction(context: Context, settings: ScanModeInteractionSettings) {
        prefs(context).edit()
            .putString(KEY_SCAN_MODE_ENTRY, settings.entry.name)
            .putString(KEY_HOLD_DURATION, settings.holdDuration.name)
            .putString(KEY_AUTO_MODE_SELECTION, settings.autoSelection.name)
            .putString(KEY_MODE_FEEDBACK, settings.feedback.name)
            .apply()
    }

    /** Restores the developer_setting_page.md defaults for all four interaction settings. */
    fun resetScanModeInteraction(context: Context) =
        setScanModeInteraction(context, DEFAULT_SCAN_MODE_INTERACTION)

    /**
     * The delay the bubble service should actually schedule: the pure
     * [scanDelayMs] fed with this device's persisted windows.
     *
     * Named apart from the top-level [scanDelayMs] on purpose — a member with the
     * same signature would shadow it and recurse.
     */
    fun scanDelayMsFor(context: Context, mode: ScanMode, random: Random = Random): Long =
        scanDelayMs(mode, getSafeInterval(context), getWarningInterval(context), random)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
