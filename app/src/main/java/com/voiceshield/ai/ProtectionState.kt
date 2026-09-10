package com.voiceshield.ai

import android.content.Context
import android.content.Intent

/** Scan modes selectable on Page 2 (Settings). */
enum class ScanMode { SAFE, WARNING }

/**
 * Shared source of truth for the protection switch and the scan mode, read by
 * the Home screen, the Settings screen and the floating bubble service.
 * Backed by SharedPreferences so both survive process death.
 */
object ProtectionState {
    private const val PREFERENCES = "protection_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_MODE = "mode"

    const val ACTION_CHANGED = "com.voiceshield.ai.PROTECTION_STATE_CHANGED"
    const val EXTRA_ACTIVE = "active"

    const val ACTION_MODE_CHANGED = "com.voiceshield.ai.SCAN_MODE_CHANGED"
    const val EXTRA_MODE = "mode"

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
