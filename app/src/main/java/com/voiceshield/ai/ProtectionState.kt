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

    const val ACTION_CHANGED = "com.voiceshield.ai.PROTECTION_STATE_CHANGED"
    const val EXTRA_ACTIVE = "active"

    const val ACTION_MODE_CHANGED = "com.voiceshield.ai.SCAN_MODE_CHANGED"
    const val EXTRA_MODE = "mode"

    /** Hidden developer page saved a new roll window; a running scan re-arms. */
    const val ACTION_INTERVALS_CHANGED = "com.voiceshield.ai.SCAN_INTERVALS_CHANGED"

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
