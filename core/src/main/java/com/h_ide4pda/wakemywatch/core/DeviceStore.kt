package com.h_ide4pda.wakemywatch.core

import android.content.Context

object DeviceStore {
    const val PREFS_NAME = "wmw_devices"
    private const val REMOTE = "remote_descriptor"
    private const val LAST_ACK = "last_ack"
    private const val LAST_ACK_RESULT = "last_ack_result"

    fun saveRemote(context: Context, descriptor: DeviceDescriptor) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(REMOTE, descriptor.toJson().toString())
            .apply()
    }

    fun remote(context: Context): DeviceDescriptor? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(REMOTE, null)
            ?: return null
        return runCatching { DeviceDescriptor.fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun saveAck(context: Context, timestamp: Long, result: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_ACK, timestamp)
            .putString(LAST_ACK_RESULT, result)
            .apply()
    }

    fun lastAck(context: Context): Pair<Long, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(LAST_ACK, 0L) to prefs.getString(LAST_ACK_RESULT, "").orEmpty()
    }
}
