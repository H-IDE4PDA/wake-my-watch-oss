package com.h_ide4pda.wakemywatch.core

import org.json.JSONObject
import java.util.UUID

object Protocol {
    const val VERSION = 1
    const val PREFIX = "/wakemywatch/v1"

    const val HELLO = "$PREFIX/handshake/hello"
    const val HELLO_ACK = "$PREFIX/handshake/hello_ack"
    const val WAKE = "$PREFIX/wake"
    const val SETTINGS = "$PREFIX/settings"
    const val DIAGNOSTICS_REQUEST = "$PREFIX/diagnostics/request"
    const val DIAGNOSTICS_CHANNEL = "$PREFIX/diagnostics/channel"
    const val DIAGNOSTICS_REPORT_CHUNK = "$PREFIX/diagnostics/report_chunk"
    const val ACK = "$PREFIX/ack"
    const val ALARM_START = "$PREFIX/alarm/start"
    const val ALARM_CLOSE = "$PREFIX/alarm/close"
    const val ALARM_ACTION = "$PREFIX/alarm/action"
    const val DND_SYNC = "$PREFIX/dnd/sync"
    const val DND_SETUP = "$PREFIX/dnd/setup"
    const val DND_PERMISSION_STATUS = "$PREFIX/dnd/permission_status"
    const val DND_ADB_GUIDE = "$PREFIX/dnd/adb_guide"
    const val DND_SYNC_STATUS_REQUEST = "$PREFIX/dnd/sync_status/request"
    const val DND_SYNC_STATUS_RESPONSE = "$PREFIX/dnd/sync_status/response"

    fun eventId(): String = UUID.randomUUID().toString()
}

enum class DeviceRole { PHONE, WATCH }

data class MessageEnvelope(
    val protocolVersion: Int = Protocol.VERSION,
    val eventId: String = Protocol.eventId(),
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JSONObject = JSONObject(),
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("eventId", eventId)
        .put("type", type)
        .put("timestamp", timestamp)
        .put("payload", payload)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): MessageEnvelope {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            return MessageEnvelope(
                protocolVersion = root.optInt("protocolVersion", 0),
                eventId = root.optString("eventId"),
                type = root.optString("type"),
                timestamp = root.optLong("timestamp"),
                payload = root.optJSONObject("payload") ?: JSONObject(),
            )
        }
    }
}
