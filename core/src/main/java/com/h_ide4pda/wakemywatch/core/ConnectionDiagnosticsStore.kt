package com.h_ide4pda.wakemywatch.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent connection/transport state shared by phone and watch diagnostics UIs. */
data class ConnectionSnapshot(
    val connectedNodeIds: Set<String> = emptySet(),
    val checkedAt: Long = 0L,
    val lastPeerConnectedAt: Long = 0L,
    val lastPeerDisconnectedAt: Long = 0L,
    val lastHandshakeAt: Long = 0L,
    val lastHandshakeDirection: String = "",
    val lastSendAt: Long = 0L,
    val lastSendPath: String = "",
    val lastSendResult: String = "",
    val lastSendDetail: String = "",
    val lastSendNodeId: String = "",
    val lastAckAt: Long = 0L,
    val lastAckEventId: String = "",
    val lastAckResult: String = "",
    val lastAckDetail: String = "",
    val lastAckNodeId: String = "",
) {
    fun isConnected(remoteNodeId: String?): Boolean =
        !remoteNodeId.isNullOrBlank() && connectedNodeIds.contains(remoteNodeId)

    fun toJson(): JSONObject = JSONObject()
        .put("connectedNodeIds", JSONArray(connectedNodeIds.toList()))
        .put("checkedAt", checkedAt)
        .put("lastPeerConnectedAt", lastPeerConnectedAt)
        .put("lastPeerDisconnectedAt", lastPeerDisconnectedAt)
        .put("lastHandshakeAt", lastHandshakeAt)
        .put("lastHandshakeDirection", lastHandshakeDirection)
        .put("lastSendAt", lastSendAt)
        .put("lastSendPath", lastSendPath)
        .put("lastSendResult", lastSendResult)
        .put("lastSendDetail", lastSendDetail)
        .put("lastSendNodeId", lastSendNodeId)
        .put("lastAckAt", lastAckAt)
        .put("lastAckEventId", lastAckEventId)
        .put("lastAckResult", lastAckResult)
        .put("lastAckDetail", lastAckDetail)
        .put("lastAckNodeId", lastAckNodeId)

    companion object {
        fun fromJson(json: JSONObject): ConnectionSnapshot {
            val ids = json.optJSONArray("connectedNodeIds") ?: JSONArray()
            val connected = buildSet {
                for (index in 0 until ids.length()) {
                    val nodeId = ids.optString(index)
                    if (nodeId.isNotBlank()) add(nodeId)
                }
            }
            return ConnectionSnapshot(
                connectedNodeIds = connected,
                checkedAt = json.optLong("checkedAt"),
                lastPeerConnectedAt = json.optLong("lastPeerConnectedAt"),
                lastPeerDisconnectedAt = json.optLong("lastPeerDisconnectedAt"),
                lastHandshakeAt = json.optLong("lastHandshakeAt"),
                lastHandshakeDirection = json.optString("lastHandshakeDirection"),
                lastSendAt = json.optLong("lastSendAt"),
                lastSendPath = json.optString("lastSendPath"),
                lastSendResult = json.optString("lastSendResult"),
                lastSendDetail = json.optString("lastSendDetail"),
                lastSendNodeId = json.optString("lastSendNodeId"),
                lastAckAt = json.optLong("lastAckAt"),
                lastAckEventId = json.optString("lastAckEventId"),
                lastAckResult = json.optString("lastAckResult"),
                lastAckDetail = json.optString("lastAckDetail"),
                lastAckNodeId = json.optString("lastAckNodeId"),
            )
        }
    }
}

object ConnectionDiagnosticsStore {
    const val PREFS_NAME = "wmw_connection_diagnostics"
    private const val SNAPSHOT = "snapshot"

    fun snapshot(context: Context): ConnectionSnapshot {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SNAPSHOT, null)
            ?: return ConnectionSnapshot()
        return runCatching { ConnectionSnapshot.fromJson(JSONObject(raw)) }
            .getOrDefault(ConnectionSnapshot())
    }

    @Synchronized
    private fun mutate(context: Context, block: (ConnectionSnapshot) -> ConnectionSnapshot) {
        val next = block(snapshot(context))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SNAPSHOT, next.toJson().toString())
            .apply()
    }

    fun updateConnectedNodes(context: Context, nodeIds: Collection<String>) {
        val now = System.currentTimeMillis()
        mutate(context) { it.copy(connectedNodeIds = nodeIds.filter { id -> id.isNotBlank() }.toSet(), checkedAt = now) }
    }

    fun peerConnected(context: Context, nodeId: String) {
        val now = System.currentTimeMillis()
        mutate(context) {
            it.copy(
                connectedNodeIds = it.connectedNodeIds + nodeId,
                checkedAt = now,
                lastPeerConnectedAt = now,
            )
        }
    }

    fun peerDisconnected(context: Context, nodeId: String) {
        val now = System.currentTimeMillis()
        mutate(context) {
            it.copy(
                connectedNodeIds = it.connectedNodeIds - nodeId,
                checkedAt = now,
                lastPeerDisconnectedAt = now,
            )
        }
    }

    fun recordHandshake(context: Context, direction: String, nodeId: String) {
        val now = System.currentTimeMillis()
        mutate(context) {
            it.copy(
                connectedNodeIds = it.connectedNodeIds + nodeId,
                checkedAt = now,
                lastHandshakeAt = now,
                lastHandshakeDirection = direction,
            )
        }
    }

    fun recordSend(context: Context, path: String, result: String, detail: String = "", nodeId: String? = null) {
        val now = System.currentTimeMillis()
        mutate(context) {
            it.copy(
                lastSendAt = now,
                lastSendPath = path,
                lastSendResult = result,
                lastSendDetail = detail,
                lastSendNodeId = nodeId.orEmpty(),
            )
        }
    }

    fun recordAck(
        context: Context,
        nodeId: String,
        eventId: String,
        result: String,
        detail: String,
    ) {
        val now = System.currentTimeMillis()
        mutate(context) {
            it.copy(
                connectedNodeIds = it.connectedNodeIds + nodeId,
                checkedAt = now,
                lastAckAt = now,
                lastAckEventId = eventId,
                lastAckResult = result,
                lastAckDetail = detail,
                lastAckNodeId = nodeId,
            )
        }
    }
}
