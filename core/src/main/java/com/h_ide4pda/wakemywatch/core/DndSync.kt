package com.h_ide4pda.wakemywatch.core

import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

object DndSync {
    const val TYPE = "DND_SYNC"
    // Sync is one-way (phone -> watch), so SOURCE_PHONE is the only source that ever gets sent.
    const val SOURCE_PHONE = "phone"

    private const val PREFS = "wmw_dnd_sync_state"
    private const val LAST_REMOTE_FILTER = "last_remote_filter"
    private const val LAST_REMOTE_AT = "last_remote_at"
    private const val LAST_REMOTE_SOURCE = "last_remote_source"
    private const val ECHO_SUPPRESS_MS = 3_000L

    fun isValidInterruptionFilter(filter: Int): Boolean = filter in NotificationManager.INTERRUPTION_FILTER_UNKNOWN..NotificationManager.INTERRUPTION_FILTER_ALARMS

    fun isDndOn(filter: Int): Boolean = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    fun payload(filter: Int, source: String, reason: String = "local_change"): JSONObject = JSONObject()
        .put("interruptionFilter", filter)
        .put("dndOn", isDndOn(filter))
        .put("source", source)
        .put("reason", reason)

    fun filterFrom(envelope: MessageEnvelope): Int? {
        val value = envelope.payload.optInt("interruptionFilter", Int.MIN_VALUE)
        return value.takeIf(::isValidInterruptionFilter)
    }

    fun sourceFrom(envelope: MessageEnvelope): String = envelope.payload.optString("source", "unknown")

    fun shouldSuppressEcho(context: Context, filter: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastFilter = prefs.getInt(LAST_REMOTE_FILTER, Int.MIN_VALUE)
        val lastAt = prefs.getLong(LAST_REMOTE_AT, 0L)
        return lastFilter == filter && System.currentTimeMillis() - lastAt in 0..ECHO_SUPPRESS_MS
    }

    fun markRemoteApplied(context: Context, filter: Int, source: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_REMOTE_FILTER, filter)
            .putLong(LAST_REMOTE_AT, System.currentTimeMillis())
            .putString(LAST_REMOTE_SOURCE, source)
            .apply()
    }

    fun lastRemoteSource(context: Context): String? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LAST_REMOTE_SOURCE, null)
}
