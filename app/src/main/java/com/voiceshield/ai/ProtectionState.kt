package com.voiceshield.ai

import android.content.Context
import android.content.Intent

/** Shared source of truth for the protection controls in the app and the bubble. */
object ProtectionState {
    private const val PREFERENCES = "protection_state"
    private const val KEY_ACTIVE = "active"
    const val ACTION_CHANGED = "com.voiceshield.ai.PROTECTION_STATE_CHANGED"
    const val EXTRA_ACTIVE = "active"

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
        context.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ACTIVE, active)
        )
    }
}
