package com.h_ide4pda.wakemywatch.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object EventHistoryStore {
    private const val PREFS = "wmw_history"
    private const val EVENTS = "events"
    private const val MAX_EVENTS = 500

    data class Entry(
        val time: Long,
        val type: String,
        val result: String,
        val detail: String,
        val repeatCount: Int = 1,
        val firstTime: Long = time,
        val lastTime: Long = time,
        val aggregationKey: String = "",
    )

    @Synchronized
    fun add(context: Context, type: String, result: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val nextEntry = JSONObject()
            .put("time", now)
            .put("type", type)
            .put("result", result)
            .put("detail", detail)
            .put("repeatCount", 1)
            .put("firstTime", now)
            .put("lastTime", now)
        prepend(context, nextEntry)
    }

    /**
     * Coalesces a noisy repeated event into one history row while preserving how often and for
     * how long it repeated. A new row starts after [windowMs], so long-running system noise does
     * not hide the time range in which it occurred.
     */
    @Synchronized
    fun addAggregated(
        context: Context,
        type: String,
        result: String,
        detail: String,
        aggregationKey: String,
        windowMs: Long = 60_000L,
    ) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = readArray(prefs.getString(EVENTS, "[]"))
        var matchingIndex = -1
        var matching: JSONObject? = null
        for (index in 0 until minOf(old.length(), MAX_EVENTS)) {
            val item = old.optJSONObject(index) ?: continue
            val sameKey = item.optString("aggregationKey") == aggregationKey
            val lastTime = item.optLong("lastTime", item.optLong("time"))
            if (sameKey && now - lastTime <= windowMs) {
                matchingIndex = index
                matching = item
                break
            }
        }

        val nextEntry = if (matching != null) {
            JSONObject()
                .put("time", now)
                .put("type", type)
                .put("result", result)
                .put("detail", detail)
                .put("repeatCount", matching.optInt("repeatCount", 1) + 1)
                .put("firstTime", matching.optLong("firstTime", matching.optLong("time", now)))
                .put("lastTime", now)
                .put("aggregationKey", aggregationKey)
        } else {
            JSONObject()
                .put("time", now)
                .put("type", type)
                .put("result", result)
                .put("detail", detail)
                .put("repeatCount", 1)
                .put("firstTime", now)
                .put("lastTime", now)
                .put("aggregationKey", aggregationKey)
        }

        val next = JSONArray().put(nextEntry)
        for (index in 0 until old.length()) {
            if (index == matchingIndex) continue
            if (next.length() >= MAX_EVENTS) break
            old.optJSONObject(index)?.let(next::put)
        }
        prefs.edit().putString(EVENTS, next.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(EVENTS).apply()
    }

    fun read(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = readArray(prefs.getString(EVENTS, "[]"))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val time = item.optLong("time")
                add(
                    Entry(
                        time = time,
                        type = item.optString("type"),
                        result = item.optString("result"),
                        detail = item.optString("detail"),
                        repeatCount = item.optInt("repeatCount", 1).coerceAtLeast(1),
                        firstTime = item.optLong("firstTime", time),
                        lastTime = item.optLong("lastTime", time),
                        aggregationKey = item.optString("aggregationKey"),
                    ),
                )
            }
        }
    }

    @Synchronized
    private fun prepend(context: Context, nextEntry: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = readArray(prefs.getString(EVENTS, "[]"))
        val next = JSONArray().put(nextEntry)
        for (index in 0 until minOf(old.length(), MAX_EVENTS - 1)) {
            old.optJSONObject(index)?.let(next::put)
        }
        prefs.edit().putString(EVENTS, next.toString()).apply()
    }

    private fun readArray(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
}
