package com.h_ide4pda.wakemywatch.phone.dnd

import android.content.Context

/**
 * Persists "the watch might still be stuck in a DND we forced, and control was already handed
 * back off" across process restarts and unreachable-watch windows, so the clear is retried the
 * next time the watch is actually reachable instead of being lost.
 */
object PendingDndClear {
    private const val PREFS = "wmw_pending_dnd_clear"
    private const val KEY_PENDING = "pending"

    fun set(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING, true).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    fun isPending(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING, false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
