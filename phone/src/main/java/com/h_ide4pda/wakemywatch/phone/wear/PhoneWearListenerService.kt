package com.h_ide4pda.wakemywatch.phone.wear

import android.content.Intent
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.h_ide4pda.wakemywatch.core.*
import com.h_ide4pda.wakemywatch.phone.PhoneMainActivity
import com.h_ide4pda.wakemywatch.phone.alarm.AlarmBridgeController
import com.h_ide4pda.wakemywatch.phone.dnd.PhoneDndSyncBridge
import com.h_ide4pda.wakemywatch.phone.permissions.PermissionProbe
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class PhoneWearListenerService : WearableListenerService() {
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPeerConnected(peer: Node) {
        ConnectionDiagnosticsStore.peerConnected(this, peer.id)
        EventHistoryStore.add(this, "CONNECTION", "PEER_CONNECTED", peer.displayName)
        ioExecutor.execute { PhoneDndSyncBridge.retryPendingClearIfNeeded(this, "peer_connected") }
    }

    override fun onPeerDisconnected(peer: Node) {
        ConnectionDiagnosticsStore.peerDisconnected(this, peer.id)
        EventHistoryStore.add(this, "CONNECTION", "PEER_DISCONNECTED", peer.displayName)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val envelope = runCatching { MessageEnvelope.fromBytes(event.data) }.getOrElse {
            EventHistoryStore.add(this, "MESSAGE", "FAILED", "invalid_payload")
            return
        }
        if (envelope.protocolVersion != Protocol.VERSION) {
            EventHistoryStore.add(this, "MESSAGE", "REJECTED", "protocol=${envelope.protocolVersion}")
            return
        }

        when (event.path) {
            Protocol.HELLO -> {
                Handshake.handleIncoming(this, event.sourceNodeId, envelope, DeviceRole.PHONE, Protocol.HELLO_ACK)
                EventHistoryStore.add(this, "HANDSHAKE", "HELLO_RECEIVED", event.sourceNodeId)
            }
            Protocol.HELLO_ACK -> {
                saveRemote(event, envelope)
                saveIncomingSettings(envelope)
                ConnectionDiagnosticsStore.recordHandshake(this, "INCOMING_HELLO_ACK", event.sourceNodeId)
                EventHistoryStore.add(this, "HANDSHAKE", "HELLO_ACK", event.sourceNodeId)
                ioExecutor.execute { PhoneDndSyncBridge.retryPendingClearIfNeeded(this, "hello_ack") }
            }
            Protocol.SETTINGS -> {
                val applied = saveIncomingSettings(envelope)
                val result = if (applied) "SETTINGS_APPLIED" else "SETTINGS_IGNORED"
                EventHistoryStore.add(this, "SETTINGS", result, "from_watch")
                sendAck(event.sourceNodeId, envelope.eventId, result, "phone")
                if (applied) {
                    SettingsSync.send(this, PhoneDndSyncBridge.maskForNativeOHealth(this, AppSettingsStore.load(this)))
                }
            }
            Protocol.DIAGNOSTICS_REPORT_CHUNK -> handleDiagnosticsChunk(event, envelope)
            Protocol.DND_SYNC -> {
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SYNC", "REJECTED", "unexpected_source_node")
                    return
                }
                val (result, detail) = PhoneDndSyncBridge.applyIncomingFromWatch(this, envelope)
                sendAck(event.sourceNodeId, envelope.eventId, result, detail)
            }
            Protocol.RINGER_SYNC -> {
                // Two-way, unlike DND_SYNC: the watch is allowed to drive the phone's ringer
                // mode. Echo protection lives in RingerSync (shouldSuppressEcho), not here.
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "RINGER_SYNC", "REJECTED", "unexpected_source_node")
                    return
                }
                val (result, detail) = com.h_ide4pda.wakemywatch.phone.ringer.RingerSyncBridge
                    .applyIncomingFromWatch(this, envelope)
                sendAck(event.sourceNodeId, envelope.eventId, result, detail)
            }
            Protocol.DND_PERMISSION_STATUS -> {
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SETUP", "STATUS_REJECTED", "unexpected_source_node")
                    return
                }
                val ready = envelope.payload.optBoolean("ready", false)
                val listener = envelope.payload.optBoolean("notificationListenerGranted", false)
                val policy = envelope.payload.optBoolean("dndPolicyAccessGranted", false)
                val reason = envelope.payload.optString("reason", "unknown")
                EventHistoryStore.add(
                    this,
                    "DND_SETUP",
                    if (ready) "WATCH_READY" else "WATCH_NOT_READY",
                    "reason=$reason notificationListener=$listener dndPolicy=$policy",
                )
                sendAck(event.sourceNodeId, envelope.eventId, "DND_PERMISSION_STATUS_RECEIVED", "ready=$ready")
            }
            Protocol.DND_SYNC_STATUS_RESPONSE -> {
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SYNC_STATUS", "RESPONSE_REJECTED", "unexpected_source_node")
                    return
                }
                val value = envelope.payload.optInt("value", -1)
                val currentInterruptionFilter = envelope.payload.optInt("currentInterruptionFilter", -1)
                val status = DndSyncStatus(
                    value = value,
                    currentInterruptionFilter = currentInterruptionFilter,
                    reachable = true,
                    watchNotificationListenerGranted = envelope.payload.optBoolean("watchNotificationListenerGranted", false),
                    watchDndPolicyAccessGranted = envelope.payload.optBoolean("watchDndPolicyAccessGranted", false),
                    watchPostNotificationsGranted = envelope.payload.optBoolean("watchPostNotificationsGranted", false),
                    watchRingerMode = envelope.payload.optInt("watchRingerMode", -1),
                )
                EventHistoryStore.add(
                    this,
                    "DND_SYNC_STATUS",
                    "RESPONSE_RECEIVED",
                    "value=$value filter=$currentInterruptionFilter listener=${status.watchNotificationListenerGranted} dndPolicy=${status.watchDndPolicyAccessGranted} post=${status.watchPostNotificationsGranted}",
                )
                PhoneDndSyncStatusTransfer.complete(envelope.eventId, status)
            }
            Protocol.DND_ADB_GUIDE -> {
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SETUP", "ADB_GUIDE_REJECTED", "unexpected_source_node")
                    return
                }
                PhoneMainActivity.requestOpenDndAdbGuide(this)
                VibroPatterns.playLookAtPhone(this)
                runCatching {
                    startActivity(
                        Intent(this, PhoneMainActivity::class.java)
                            .putExtra(PhoneMainActivity.EXTRA_OPEN_DND_ADB_GUIDE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }.onFailure { error ->
                    EventHistoryStore.add(this, "DND_SETUP", "ADB_GUIDE_OPEN_FAILED", error.message ?: error.javaClass.simpleName)
                }
                EventHistoryStore.add(this, "DND_SETUP", "ADB_GUIDE_OPEN_PHONE", envelope.payload.optString("reason", "watch_request"))
                sendAck(event.sourceNodeId, envelope.eventId, "DND_ADB_GUIDE_OPENED", "phone")
            }
            Protocol.ACK -> {
                val result = envelope.payload.optString("result", "ACK")
                val detail = envelope.payload.optString("detail")
                DeviceStore.saveAck(this, System.currentTimeMillis(), result)
                ConnectionDiagnosticsStore.recordAck(this, event.sourceNodeId, envelope.eventId, result, detail)
                EventHistoryStore.add(this, "ACK", result, detail)
                PhoneDndSyncAckTransfer.complete(envelope.eventId, result, detail)
            }
            Protocol.ALARM_ACTION -> {
                if (!isTrustedNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "ALARM_ACTION", "REJECTED", "unexpected_source_node")
                    return
                }
                val requestId = envelope.eventId
                val alarmEventId = envelope.payload.optString("alarmEventId")
                val actionName = envelope.payload.optString("action")
                val action = runCatching { AlarmBridgeController.Action.valueOf(actionName) }.getOrNull()
                val result: Result<Unit> = when {
                    requestId.isBlank() -> Result.failure(IllegalArgumentException("Missing action request ID"))
                    alarmEventId.isBlank() -> Result.failure(IllegalArgumentException("Missing alarm event ID"))
                    action == null -> Result.failure(IllegalArgumentException("Unknown alarm action: $actionName"))
                    else -> AlarmBridgeController.perform(this, alarmEventId, action)
                }
                val status = if (result.isSuccess) "ALARM_ACTION_OK" else "ALARM_ACTION_FAILED"
                val detail = result.exceptionOrNull()?.message ?: actionName
                EventHistoryStore.add(this, "ALARM_ACTION", status, "$alarmEventId:$actionName:$detail")
                sendAlarmActionAck(
                    nodeId = event.sourceNodeId,
                    requestId = requestId,
                    alarmEventId = alarmEventId,
                    actionName = actionName,
                    result = status,
                    detail = detail,
                )
            }
            else -> EventHistoryStore.add(this, "MESSAGE", "IGNORED", event.path)
        }
    }

    private fun handleDiagnosticsChunk(event: MessageEvent, envelope: MessageEnvelope) {
        if (!isTrustedNode(event.sourceNodeId)) {
            EventHistoryStore.add(this, "DIAGNOSTICS", "WATCH_REPORT_REJECTED", "unexpected_source_node")
            return
        }
        val requestId = envelope.eventId
        val payload = envelope.payload
        val index = payload.optInt("index", -1)
        val total = payload.optInt("total", -1)
        val encoding = payload.optString("encoding")
        val data = payload.optString("data")
        if (requestId.isBlank() || data.isBlank()) {
            EventHistoryStore.add(this, "DIAGNOSTICS", "WATCH_REPORT_FAILED", "invalid_chunk_payload")
            if (requestId.isNotBlank()) PhoneDiagnosticsTransfer.fail(requestId, "invalid_chunk_payload")
            return
        }

        val progress = PhoneDiagnosticsChunkAssembler.receive(requestId, index, total, encoding, data)
        when {
            progress.completed -> EventHistoryStore.add(
                this,
                "DIAGNOSTICS",
                "WATCH_REPORT_RECEIVED",
                "requestId=$requestId transport=message_chunks chunks=${progress.totalChunks}",
            )
            progress.error != null && progress.error != "request_not_pending" -> EventHistoryStore.add(
                this,
                "DIAGNOSTICS",
                "WATCH_REPORT_FAILED",
                "requestId=$requestId transport=message_chunks error=${progress.error}",
            )
            progress.accepted && progress.receivedChunks == 1 -> EventHistoryStore.add(
                this,
                "DIAGNOSTICS",
                "WATCH_REPORT_CHUNKS_STARTED",
                "requestId=$requestId total=${progress.totalChunks}",
            )
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(Protocol.DIAGNOSTICS_CHANNEL + "/")) return
        val requestId = channel.path.removePrefix(Protocol.DIAGNOSTICS_CHANNEL + "/").substringBefore('/')
        val client = Wearable.getChannelClient(this)
        if (requestId.isBlank() || !isTrustedNode(channel.nodeId)) {
            EventHistoryStore.add(this, "DIAGNOSTICS", "REJECTED", "invalid_channel requestId=$requestId node=${channel.nodeId}")
            if (requestId.isNotBlank()) PhoneDiagnosticsTransfer.fail(requestId, "unexpected_source_node")
            client.close(channel)
            return
        }

        client.getInputStream(channel)
            .addOnSuccessListener { input ->
                ioExecutor.execute {
                    val received = runCatching {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        val output = ByteArrayOutputStream()
                        input.use { source ->
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= MAX_DIAGNOSTIC_BYTES) { "watch_report_too_large" }
                                output.write(buffer, 0, read)
                            }
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    client.close(channel)
                    if (received.isSuccess) {
                        val report = received.getOrThrow()
                        PhoneDiagnosticsChunkAssembler.cancel(requestId)
                        PhoneDiagnosticsTransfer.complete(requestId, report)
                        EventHistoryStore.add(
                            this,
                            "DIAGNOSTICS",
                            "WATCH_REPORT_RECEIVED",
                            "requestId=$requestId transport=channel bytes=${report.toByteArray().size}",
                        )
                    } else {
                        val error = received.exceptionOrNull()?.message ?: "watch_report_read_failed"
                        EventHistoryStore.add(this, "DIAGNOSTICS", "WATCH_REPORT_CHANNEL_FAILED", "requestId=$requestId error=$error fallback=message_chunks")
                    }
                }
            }
            .addOnFailureListener { error ->
                client.close(channel)
                val detail = error.message ?: error.javaClass.simpleName
                EventHistoryStore.add(this, "DIAGNOSTICS", "WATCH_REPORT_CHANNEL_FAILED", "requestId=$requestId error=$detail fallback=message_chunks")
            }
    }

    private fun isTrustedNode(nodeId: String): Boolean {
        val pairedNodeId = DeviceStore.remote(this)?.nodeId
        return pairedNodeId.isNullOrBlank() || pairedNodeId == nodeId
    }

    private fun saveRemote(event: MessageEvent, envelope: MessageEnvelope) {
        envelope.payload.optJSONObject("device")
            ?.let(DeviceDescriptor::fromJson)
            ?.withNode(event.sourceNodeId)
            ?.let { DeviceStore.saveRemote(this, it) }
    }

    private fun saveIncomingSettings(envelope: MessageEnvelope): Boolean {
        val incoming = SettingsSync.extract(envelope) ?: return false
        val previous = AppSettingsStore.load(this)
        // Only the phone can know whether it's a native OnePlus/Oppo/Realme device, so the
        // pause-mode force/restore decision is always resolved here, regardless of which device
        // the toggle originated on. If this changes anything beyond what the watch sent, the
        // resulting settings get a newer revision so the watch's own saveIfNewer() accepts the
        // correction when it comes back via the SettingsSync.send() call below.
        val resolved = PauseModeTransition.resolve(previous, incoming, PermissionProbe.snapshot(this).nativeOHealthPhone)
        val toSave = if (resolved != incoming) resolved.nextRevision() else incoming
        val applied = AppSettingsStore.saveIfNewer(this, toSave)
        if (applied) SettingsAudit.recordChange(this, "watch_sync", previous, toSave)
        return applied
    }

    private fun sendAlarmActionAck(
        nodeId: String,
        requestId: String,
        alarmEventId: String,
        actionName: String,
        result: String,
        detail: String,
    ) {
        val ack = MessageEnvelope(
            type = "ACK",
            eventId = requestId,
            payload = JSONObject()
                .put("result", result)
                .put("detail", detail)
                .put("alarmEventId", alarmEventId)
                .put("action", actionName),
        )
        WearTransport.sendDirect(this, nodeId, Protocol.ACK, ack)
    }

    private fun sendAck(nodeId: String, eventId: String, result: String, detail: String) {
        val ack = MessageEnvelope(
            type = "ACK",
            eventId = eventId,
            payload = JSONObject().put("result", result).put("detail", detail),
        )
        WearTransport.sendDirect(this, nodeId, Protocol.ACK, ack)
    }

    companion object {
        private const val MAX_DIAGNOSTIC_BYTES = 2 * 1024 * 1024
    }
}
