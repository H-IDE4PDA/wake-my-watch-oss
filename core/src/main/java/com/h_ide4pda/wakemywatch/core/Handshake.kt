package com.h_ide4pda.wakemywatch.core

import android.content.Context
import org.json.JSONObject

object Handshake {
    fun hello(context: Context, role: DeviceRole, callback: (WearTransport.Result) -> Unit = {}) {
        val descriptor = DeviceDescriptor.current(context, role)
        val payload = JSONObject()
            .put("device", descriptor.toJson())
            .put("settings", AppSettingsStore.load(context).toJson())
        val envelope = MessageEnvelope(type = "HELLO", payload = payload)
        WearTransport.sendPreferred(context, Protocol.HELLO, envelope, callback = callback)
    }

    fun handleIncoming(
        context: Context,
        sourceNodeId: String,
        envelope: MessageEnvelope,
        localRole: DeviceRole,
        replyPath: String,
    ): DeviceDescriptor? {
        if (envelope.protocolVersion != Protocol.VERSION) return null
        envelope.payload.optJSONObject("settings")?.let {
            runCatching { AppSettings.fromJson(it) }.getOrNull()?.let { incoming ->
                val previous = AppSettingsStore.load(context)
                if (AppSettingsStore.saveIfNewer(context, incoming)) {
                    SettingsAudit.recordChange(context, "handshake_incoming", previous, incoming)
                }
            }
        }
        val remoteJson = envelope.payload.optJSONObject("device") ?: return null
        val remote = DeviceDescriptor.fromJson(remoteJson).withNode(sourceNodeId)
        DeviceStore.saveRemote(context, remote)
        ConnectionDiagnosticsStore.recordHandshake(context, "INCOMING_HELLO", sourceNodeId)
        val local = DeviceDescriptor.current(context, localRole)
        val replyPayload = JSONObject()
            .put("device", local.toJson())
            .put("settings", AppSettingsStore.load(context).toJson())
        val reply = MessageEnvelope(type = "HELLO_ACK", payload = replyPayload)
        WearTransport.sendDirect(context, sourceNodeId, replyPath, reply)
        return remote
    }
}
