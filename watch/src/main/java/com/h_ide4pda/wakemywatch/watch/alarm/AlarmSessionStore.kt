package com.h_ide4pda.wakemywatch.watch.alarm

import android.content.Context
import org.json.JSONObject

data class AlarmSession(
    val eventId: String,
    val sourcePackage: String,
    val notificationId: Int,
    val postedAt: Long,
    val title: String,
    val text: String,
    val canSnooze: Boolean,
    val canDismiss: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("sourcePackage", sourcePackage)
        .put("notificationId", notificationId)
        .put("postedAt", postedAt)
        .put("title", title)
        .put("text", text)
        .put("canSnooze", canSnooze)
        .put("canDismiss", canDismiss)

    companion object {
        private const val GOOGLE_CLOCK_PACKAGE = "com.google.android.deskclock"

        fun fromPayload(payload: JSONObject): AlarmSession? {
            val eventId = payload.optString("alarmEventId").takeIf { it.isNotBlank() } ?: return null
            val sourcePackage = payload.optString("sourcePackage")
            if (sourcePackage != GOOGLE_CLOCK_PACKAGE) return null
            val postedAt = payload.optLong("postedAt", 0L)
            if (postedAt <= 0L) return null
            return AlarmSession(
                eventId = eventId,
                sourcePackage = sourcePackage,
                notificationId = payload.optInt("notificationId", 0),
                postedAt = postedAt,
                title = payload.optString("title", "Alarm"),
                text = payload.optString("text"),
                canSnooze = payload.optBoolean("canSnooze", false),
                canDismiss = payload.optBoolean("canDismiss", false),
            ).takeIf { it.canSnooze || it.canDismiss }
        }

        fun fromJson(json: JSONObject): AlarmSession? {
            val eventId = json.optString("eventId").takeIf { it.isNotBlank() } ?: return null
            val sourcePackage = json.optString("sourcePackage")
            if (sourcePackage != GOOGLE_CLOCK_PACKAGE) return null
            val postedAt = json.optLong("postedAt", 0L)
            if (postedAt <= 0L) return null
            return AlarmSession(
                eventId = eventId,
                sourcePackage = sourcePackage,
                notificationId = json.optInt("notificationId", 0),
                postedAt = postedAt,
                title = json.optString("title", "Alarm"),
                text = json.optString("text"),
                canSnooze = json.optBoolean("canSnooze", false),
                canDismiss = json.optBoolean("canDismiss", false),
            ).takeIf { it.canSnooze || it.canDismiss }
        }
    }
}

object AlarmSessionStore {
    private const val PREFS = "wmw_alarm_session"
    private const val KEY = "active"

    fun save(context: Context, session: AlarmSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, session.toJson().toString())
            .apply()
    }

    fun load(context: Context): AlarmSession? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching { AlarmSession.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun clear(context: Context, expectedEventId: String? = null): Boolean {
        val current = load(context) ?: return false
        if (expectedEventId != null && current.eventId != expectedEventId) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return true
    }
}
