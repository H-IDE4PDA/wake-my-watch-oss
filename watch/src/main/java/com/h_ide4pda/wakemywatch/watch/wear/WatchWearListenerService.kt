package com.h_ide4pda.wakemywatch.watch.wear

import android.app.NotificationManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.ConnectionDiagnosticsStore
import com.h_ide4pda.wakemywatch.core.DeviceDescriptor
import com.h_ide4pda.wakemywatch.core.DeviceRole
import com.h_ide4pda.wakemywatch.core.DeviceStore
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.Handshake
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.SettingsAudit
import com.h_ide4pda.wakemywatch.core.SettingsSync
import com.h_ide4pda.wakemywatch.core.SoundMode
import com.h_ide4pda.wakemywatch.core.VibroPatterns
import com.h_ide4pda.wakemywatch.core.WearTransport
import com.h_ide4pda.wakemywatch.watch.BuildConfig
import com.h_ide4pda.wakemywatch.watch.WatchMainActivity
import com.h_ide4pda.wakemywatch.watch.alarm.AlarmActivity
import com.h_ide4pda.wakemywatch.watch.alarm.AlarmSession
import com.h_ide4pda.wakemywatch.watch.mirror.MirrorNotifier
import com.h_ide4pda.wakemywatch.watch.sensors.OffBodyStateMonitor
import com.h_ide4pda.wakemywatch.watch.sound.SoundController
import com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncBridge
import com.h_ide4pda.wakemywatch.watch.dnd.WatchDndPermissionState
import com.h_ide4pda.wakemywatch.watch.wake.WakeController
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class WatchWearListenerService : WearableListenerService() {
    private val diagnosticChunkChars = 24_000

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun executeIo(label: String, block: () -> Unit) {
        try {
            ioExecutor.execute(block)
        } catch (error: RejectedExecutionException) {
            EventHistoryStore.add(this, "THREAD", "REJECTED", "$label:${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun onPeerConnected(peer: Node) {
        ConnectionDiagnosticsStore.peerConnected(this, peer.id)
        EventHistoryStore.add(this, "CONNECTION", "PEER_CONNECTED", peer.displayName)
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
                Handshake.handleIncoming(this, event.sourceNodeId, envelope, DeviceRole.WATCH, Protocol.HELLO_ACK)
                EventHistoryStore.add(this, "HANDSHAKE", "HELLO_RECEIVED", event.sourceNodeId)
            }
            Protocol.HELLO_ACK -> {
                saveRemote(event, envelope)
                saveIncomingSettings(envelope)
                ConnectionDiagnosticsStore.recordHandshake(this, "INCOMING_HELLO_ACK", event.sourceNodeId)
                EventHistoryStore.add(this, "HANDSHAKE", "HELLO_ACK", event.sourceNodeId)
            }
            Protocol.WAKE -> handleWake(event, envelope)
            Protocol.DIAGNOSTICS_REQUEST -> handleDiagnosticsRequest(event, envelope)
            Protocol.SETTINGS -> {
                val applied = saveIncomingSettings(envelope)
                val result = if (applied) "SETTINGS_APPLIED" else "SETTINGS_IGNORED"
                EventHistoryStore.add(this, "SETTINGS", result, "from_phone")
                sendAck(event.sourceNodeId, envelope.eventId, result, "watch")
            }
            Protocol.DND_SYNC -> {
                // Unlike alarm messages, DND sync carries no risk that warrants the paired-node
                // trust check: applying the wrong interruption filter is not a security issue,
                // and gating on node identity here only added a silent failure mode (stale/
                // mismatched pairedNodeId blackholes every DND sync with a log-only rejection).
                val (result, detail) = WatchDndSyncBridge.applyIncomingFromPhone(this, envelope)
                sendAck(event.sourceNodeId, envelope.eventId, result, detail)
            }
            Protocol.DND_SETUP -> {
                if (!isTrustedAlarmNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SETUP", "REJECTED", "unexpected_source_node")
                    return
                }
                WatchDndPermissionState.requestSetup(this)
                EventHistoryStore.add(this, "DND_SETUP", "REQUEST_RECEIVED", "from_phone")
                openDndSetupScreen()
                WatchDndPermissionState.sendStatusToPhone(this, "request_received")
                sendAck(event.sourceNodeId, envelope.eventId, "DND_SETUP_OPENED", "watch")
            }
            Protocol.DND_SYNC_STATUS_REQUEST -> {
                if (!isTrustedAlarmNode(event.sourceNodeId)) {
                    EventHistoryStore.add(this, "DND_SYNC_STATUS", "REJECTED", "unexpected_source_node")
                    return
                }
                val value = try {
                    Settings.System.getInt(contentResolver, "oplus_sync_phone_dnd", -1)
                } catch (error: SecurityException) {
                    -2
                }
                val currentInterruptionFilter = WatchDndPermissionState.snapshot(this).currentInterruptionFilter
                EventHistoryStore.add(this, "DND_SYNC_STATUS", "REQUEST_RECEIVED", "value=$value filter=$currentInterruptionFilter")
                val response = MessageEnvelope(
                    type = "DND_SYNC_STATUS_RESPONSE",
                    eventId = envelope.eventId,
                    payload = JSONObject()
                        .put("value", value)
                        .put("currentInterruptionFilter", currentInterruptionFilter),
                )
                WearTransport.sendDirect(this, event.sourceNodeId, Protocol.DND_SYNC_STATUS_RESPONSE, response) { result ->
                    EventHistoryStore.add(
                        this,
                        "DND_SYNC_STATUS",
                        if (result.success) "RESPONSE_SENT" else "RESPONSE_SEND_FAILED",
                        "value=$value filter=$currentInterruptionFilter ${result.detail}",
                    )
                }
            }
            Protocol.ALARM_START -> {
                val synced = AppSettingsStore.load(this)
                when {
                    !isTrustedAlarmNode(event.sourceNodeId) ->
                        EventHistoryStore.add(this, "ALARM", "REJECTED", "unexpected_source_node")
                    synced.isFullyPaused -> {
                        // Defense in depth: the phone should already have stopped forwarding
                        // Alarm Bridge events while fully paused. pauseKeepsAlarmAndDnd=true
                        // deliberately falls through to the normal handling below.
                        EventHistoryStore.add(this, "ALARM", "ALARM_FAILED", "app_paused")
                        sendAck(event.sourceNodeId, envelope.eventId, "ALARM_FAILED", "app_paused")
                    }
                    else -> handleAlarmStart(event, envelope)
                }
            }
            Protocol.ALARM_CLOSE -> {
                if (isTrustedAlarmNode(event.sourceNodeId)) handleAlarmClose(event, envelope)
                else EventHistoryStore.add(this, "ALARM", "REJECTED", "unexpected_source_node")
            }
            Protocol.ACK -> {
                val result = envelope.payload.optString("result", "ACK")
                val detail = envelope.payload.optString("detail")
                if (result.startsWith("ALARM_ACTION_")) {
                    if (!isTrustedAlarmNode(event.sourceNodeId)) {
                        EventHistoryStore.add(this, "ALARM_ACTION", "REJECTED", "unexpected_source_node")
                        return
                    }
                    val alarmEventId = envelope.payload.optString("alarmEventId")
                    if (alarmEventId.isBlank()) {
                        EventHistoryStore.add(this, "ALARM_ACTION", "FAILED", "missing_alarm_event_id")
                        return
                    }
                    AlarmActivity.reportActionResult(
                        context = this,
                        requestId = envelope.eventId,
                        alarmEventId = alarmEventId,
                        result = result,
                        detail = detail,
                    )
                }
                DeviceStore.saveAck(this, System.currentTimeMillis(), result)
                ConnectionDiagnosticsStore.recordAck(this, event.sourceNodeId, envelope.eventId, result, detail)
                EventHistoryStore.add(this, "ACK", result, detail)
            }
            else -> EventHistoryStore.add(this, "MESSAGE", "IGNORED", event.path)
        }
    }

    companion object {
        private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        val applied = AppSettingsStore.saveIfNewer(this, incoming)
        if (applied) {
            SettingsAudit.recordChange(this, "phone_sync", previous, incoming)
            OffBodyStateMonitor.updateRegistration(this, incoming)
        }
        return applied
    }

    private fun handleDiagnosticsRequest(event: MessageEvent, envelope: MessageEnvelope) {
        if (!isTrustedAlarmNode(event.sourceNodeId)) {
            EventHistoryStore.add(this, "DIAGNOSTICS", "REJECTED", "unexpected_source_node")
            return
        }
        val requestId = envelope.eventId.ifBlank { Protocol.eventId() }
        EventHistoryStore.add(this, "DIAGNOSTICS", "REQUEST_RECEIVED", "requestId=$requestId node=${event.sourceNodeId}")
        val report = buildWatchDiagnosticReport(requestId)

        // Keep the efficient Channel API path, but also send compressed Message API chunks.
        // Some OxygenOS/Wear OS combinations acknowledge ordinary messages while never delivering
        // the remote onChannelOpened callback. The first successful transport completes the export.
        sendDiagnosticsChannel(event.sourceNodeId, requestId, report)
        sendDiagnosticsFallbackChunks(event.sourceNodeId, requestId, report)
    }

    private fun sendDiagnosticsChannel(nodeId: String, requestId: String, report: String) {
        val bytes = report.toByteArray(Charsets.UTF_8)
        val client = Wearable.getChannelClient(this)
        client.openChannel(nodeId, "${Protocol.DIAGNOSTICS_CHANNEL}/$requestId")
            .addOnSuccessListener { channel ->
                client.getOutputStream(channel)
                    .addOnSuccessListener { output ->
                        thread(name = "wmw-diagnostics-channel") {
                            val sent = runCatching {
                                output.use { stream ->
                                    stream.write(bytes)
                                    stream.flush()
                                }
                            }
                            client.close(channel)
                            if (sent.isSuccess) {
                                EventHistoryStore.add(this, "DIAGNOSTICS", "REPORT_CHANNEL_SENT", "requestId=$requestId bytes=${bytes.size}")
                            } else {
                                val error = sent.exceptionOrNull()?.message ?: "write_failed"
                                EventHistoryStore.add(this, "DIAGNOSTICS", "REPORT_CHANNEL_FAILED", "requestId=$requestId error=$error")
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        client.close(channel)
                        EventHistoryStore.add(this, "DIAGNOSTICS", "REPORT_CHANNEL_FAILED", "requestId=$requestId error=${error.message ?: error.javaClass.simpleName}")
                    }
            }
            .addOnFailureListener { error ->
                EventHistoryStore.add(this, "DIAGNOSTICS", "REPORT_CHANNEL_FAILED", "requestId=$requestId error=${error.message ?: error.javaClass.simpleName}")
            }
    }

    private fun sendDiagnosticsFallbackChunks(nodeId: String, requestId: String, report: String) {
        executeIo("diagnostics_chunks") {
            val encoded = runCatching {
                val compressed = ByteArrayOutputStream().use { output ->
                    GZIPOutputStream(output).use { gzip ->
                        gzip.write(report.toByteArray(Charsets.UTF_8))
                    }
                    output.toByteArray()
                }
                Base64.getEncoder().encodeToString(compressed)
            }.getOrElse { error ->
                EventHistoryStore.add(this, "DIAGNOSTICS", "REPORT_CHUNKS_FAILED", "requestId=$requestId error=${error.message ?: error.javaClass.simpleName}")
                return@executeIo
            }

            val chunks = encoded.chunked(diagnosticChunkChars)
            EventHistoryStore.add(
                this,
                "DIAGNOSTICS",
                "REPORT_CHUNKS_STARTED",
                "requestId=$requestId chunks=${chunks.size} encodedBytes=${encoded.length}",
            )
            chunks.forEachIndexed { index, chunk ->
                val message = MessageEnvelope(
                    type = "DIAGNOSTICS_REPORT_CHUNK",
                    eventId = requestId,
                    payload = JSONObject()
                        .put("index", index)
                        .put("total", chunks.size)
                        .put("encoding", "gzip+base64")
                        .put("data", chunk),
                )
                WearTransport.sendDirect(this, nodeId, Protocol.DIAGNOSTICS_REPORT_CHUNK, message) { result ->
                    if (!result.success) {
                        EventHistoryStore.add(
                            this,
                            "DIAGNOSTICS",
                            "REPORT_CHUNK_FAILED",
                            "requestId=$requestId index=$index/${chunks.size} error=${result.detail}",
                        )
                    } else if (index == chunks.lastIndex) {
                        EventHistoryStore.add(
                            this,
                            "DIAGNOSTICS",
                            "REPORT_CHUNKS_SENT",
                            "requestId=$requestId chunks=${chunks.size}",
                        )
                    }
                }
            }
        }
    }

    private fun buildWatchDiagnosticReport(requestId: String): String {
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        val settings = AppSettingsStore.load(this)
        val connection = ConnectionDiagnosticsStore.snapshot(this)
        val device = DeviceDescriptor.current(this, DeviceRole.WATCH)
        val events = EventHistoryStore.read(this)
        val power = getSystemService(PowerManager::class.java)
        val displayState = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.state
        val notificationManager = getSystemService(NotificationManager::class.java)
        val dnd = notificationManager.currentInterruptionFilter
        val dndPolicyAccess = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)
        val writeSecureSettings = checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        return buildString {
            append("Wake My Watch watch diagnostics\n")
            append("Request ID: ").append(requestId).append('\n')
            append("Watch app version: ").append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Generated: ").append(dateTime.format(Date())).append('\n')
            append("Watch: ").append(device.displayName)
                .append(" | Wear OS/Android ").append(Build.VERSION.RELEASE)
                .append(" | API ").append(Build.VERSION.SDK_INT)
                .append(" | build=").append(Build.DISPLAY).append('\n')
            append("Display: state=").append(displayStateLabel(displayState))
                .append(" interactive=").append(power.isInteractive).append('\n')
            append("DND interruptionFilter: ").append(dnd).append('\n')
            append("DND policy access: ").append(dndPolicyAccess).append('\n')
            append("DND notification listener access: ").append(WatchDndPermissionState.snapshot(this@WatchWearListenerService).notificationListenerGranted).append('\n')
            append("WRITE_SECURE_SETTINGS: ").append(writeSecureSettings).append('\n')
            append("Off-wrist sensor: supported=").append(OffBodyStateMonitor.supported)
                .append(" registered=").append(OffBodyStateMonitor.isRegistered)
                .append(" worn=").append(OffBodyStateMonitor.isWorn).append('\n')
            append("Sound controller: ").append(SoundController.diagnosticState(this@WatchWearListenerService)).append('\n')
            append("Settings: ").append(SettingsAudit.snapshot(settings)).append('\n')
            append("Connection: connectedNodes=").append(connection.connectedNodeIds.sorted().joinToString(","))
                .append(" lastHandshakeAt=").append(connection.lastHandshakeAt)
                .append(" lastHandshakeDirection=").append(connection.lastHandshakeDirection)
                .append(" lastSendAt=").append(connection.lastSendAt)
                .append(" lastSendPath=").append(connection.lastSendPath)
                .append(" lastSendResult=").append(connection.lastSendResult)
                .append(" lastAckAt=").append(connection.lastAckAt)
                .append(" lastAckResult=").append(connection.lastAckResult)
                .append(" lastAckDetail=").append(connection.lastAckDetail).append('\n')
            append("Events: ").append(events.size).append(" (newest first; store limit 500)\n\n")
            events.forEachIndexed { index, entry ->
                append('#').append(index + 1).append(" [").append(dateTime.format(Date(entry.time))).append("] ")
                append(entry.type).append(" · ").append(entry.result)
                if (entry.repeatCount > 1) {
                    append(" · repeated=").append(entry.repeatCount)
                    append(" first=").append(dateTime.format(Date(entry.firstTime)))
                    append(" last=").append(dateTime.format(Date(entry.lastTime)))
                }
                if (entry.detail.isNotBlank()) append('\n').append(entry.detail)
                append("\n\n")
            }
        }.trimEnd()
    }

    private fun displayStateLabel(state: Int?): String = when (state) {
        Display.STATE_OFF -> "off"
        Display.STATE_ON -> "on"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze_suspend"
        Display.STATE_VR -> "vr"
        Display.STATE_ON_SUSPEND -> "on_suspend"
        null -> "unknown"
        else -> "state_$state"
    }

    private fun handleWake(event: MessageEvent, envelope: MessageEnvelope) {
        val synced = AppSettingsStore.load(this)
        val p = envelope.payload
        val respectDnd = p.optBoolean("respectWatchDnd", synced.respectWatchDnd)
        val screenWake = p.optBoolean("screenWake", synced.screenWake)
        val phoneDndBlocksWake = p.optBoolean("phoneDndBlocksWake", false)
        val skipWakeOffWrist = p.optBoolean("skipWakeOffWrist", synced.skipWakeOffWrist)
        val skipSoundOffWrist = p.optBoolean("skipSoundOffWrist", synced.skipSoundOffWrist)
        val soundMode = SoundMode.fromWire(p.optInt("soundMode", synced.soundMode.wireValue))
        val vibrateOnWake = p.optBoolean("vibrateOnWake", synced.vibrateOnWake)
        val sourcePackage = p.optString("packageName", "unknown_source").ifBlank { "unknown_source" }

        val dndBlocked = respectDnd && getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val offWrist = OffBodyStateMonitor.isOffWrist(this)
        // Sound and vibration are both wrist alerts, so they share the off-wrist rule —
        // an unworn watch has nobody to alert.
        val alertAllowed = !(offWrist && skipSoundOffWrist)
        val result: String
        val detail: String

        if (synced.appPaused) {
            // Defense in depth: the phone should already have stopped sending WAKE
            // while paused, but a message can be in flight from just before the pause synced.
            result = "SKIPPED"
            detail = "app_paused"
        } else if (dndBlocked) {
            result = "SKIPPED"
            detail = "watch_dnd"
        } else if (offWrist && skipWakeOffWrist) {
            result = "SKIPPED"
            detail = "off_wrist(${OffBodyStateMonitor.decisionDetail(this)})"
        } else if (!screenWake) {
            // Screen wake switched off: the wrist alert is the whole point of the forward, so the
            // panel deliberately stays dark. Lighting it up shows the watch face and nothing else
            // whenever the phone demoted the notification below the threshold Wear OS needs to
            // draw a card — a flash about nothing that costs battery.
            val soundResult = if (soundMode != SoundMode.NONE && alertAllowed) {
                SoundController.playNotification(this, soundMode)
            } else {
                null
            }
            val vibroDetail = vibrateIfEnabled(vibrateOnWake && alertAllowed)
            result = "WAKE_OK"
            detail = if (soundResult != null) {
                "screen_wake_off:${soundResult.detail}$vibroDetail"
            } else {
                "screen_wake_off:sound_skipped$vibroDetail"
            }
        } else if (phoneDndBlocksWake) {
            // Only the screen wake is suppressed here — sound and vibration still play normally,
            // same as the regular path below (off-wrist alert rule still applies).
            val soundResult = if (soundMode != SoundMode.NONE && alertAllowed) {
                SoundController.playNotification(this, soundMode)
            } else {
                null
            }
            val vibroDetail = vibrateIfEnabled(vibrateOnWake && alertAllowed)
            result = "WAKE_OK"
            detail = if (soundResult != null) {
                "phone_dnd_wake_suppressed:${soundResult.detail}$vibroDetail"
            } else {
                "phone_dnd_wake_suppressed:sound_skipped$vibroDetail"
            }
        } else {
            val wakeResult = WakeController.wake(this, envelope.eventId)
            if (!wakeResult.success) {
                result = "WAKE_FAILED"
                detail = wakeResult.detail
            } else {
                val soundResult = if (soundMode != SoundMode.NONE && alertAllowed) {
                    SoundController.playNotification(this, soundMode)
                } else {
                    null
                }
                val vibroDetail = vibrateIfEnabled(vibrateOnWake && alertAllowed)
                result = "WAKE_OK"
                detail = when {
                    offWrist -> "${wakeResult.detail}:without_sound_off_wrist$vibroDetail"
                    soundResult != null -> "${wakeResult.detail}:${soundResult.detail}$vibroDetail"
                    else -> "${wakeResult.detail}:sound_skipped$vibroDetail"
                }
            }
        }

        // Only when something actually happened on the watch: a WAKE suppressed by DND, pause or
        // off-wrist must not leave a card behind that the user never got alerted about.
        val mirrorDetail = if (result == "SKIPPED") "" else mirrorIfRequested(p)
        val tracedDetail = "$sourcePackage:$detail$mirrorDetail"
        EventHistoryStore.add(this, "WAKE", result, tracedDetail)
        sendAck(event.sourceNodeId, envelope.eventId, result, tracedDetail)
    }

    /** Posts the notification ourselves when the phone flagged it as one the bridge will drop. */
    private fun mirrorIfRequested(payload: JSONObject): String {
        val mirror = payload.optJSONObject("mirror") ?: return ""
        val outcome = MirrorNotifier.show(
            context = this,
            title = mirror.optString("title"),
            text = mirror.optString("text"),
            appLabel = mirror.optString("appLabel"),
            notificationKey = payload.optString("notificationKey"),
        )
        return ":$outcome"
    }

    /** Plays the wrist alert for a forwarded notification; returns a suffix for the event log. */
    private fun vibrateIfEnabled(enabled: Boolean): String = when {
        !enabled -> ":vibro_skipped"
        VibroPatterns.playNotification(this) -> ":vibro"
        else -> ":vibro_unavailable"
    }

    private fun handleAlarmStart(event: MessageEvent, envelope: MessageEnvelope) {
        val session = AlarmSession.fromPayload(envelope.payload)
        if (session == null) {
            EventHistoryStore.add(this, "ALARM", "ALARM_FAILED", "invalid_alarm_payload")
            sendAck(event.sourceNodeId, envelope.eventId, "ALARM_FAILED", "invalid_alarm_payload")
            return
        }
        val launch = AlarmActivity.show(this, session)
        if (launch.isFailure) {
            val detail = launch.exceptionOrNull()?.message ?: "alarm_activity_start_failed"
            EventHistoryStore.add(this, "ALARM", "ALARM_FAILED", detail)
            sendAck(event.sourceNodeId, session.eventId, "ALARM_FAILED", detail)
            return
        }
        // AlarmActivity.show() already logged ALARM_LAUNCH_REQUESTED (direct startActivity() +
        // full-screen-intent safety net detail). ALARM_SHOWN is only logged once the activity
        // actually starts, from AlarmActivity.onCreate()/onNewIntent() — a launch request is not
        // proof the screen appeared. The wire ACK stays "ALARM_SHOWN" for the phone regardless
        // (protocol unchanged).
        sendAck(event.sourceNodeId, session.eventId, "ALARM_SHOWN", session.title)
    }


    private fun openDndSetupScreen() {
        val intent = Intent(this, WatchMainActivity::class.java)
            .putExtra(WatchMainActivity.EXTRA_OPEN_DND_SETUP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
            .onFailure { error ->
                EventHistoryStore.add(this, "DND_SETUP", "OPEN_ACTIVITY_FAILED", error.message ?: error.javaClass.simpleName)
            }
    }

    private fun handleAlarmClose(event: MessageEvent, envelope: MessageEnvelope) {
        val eventId = envelope.payload.optString("alarmEventId", envelope.eventId)
        AlarmActivity.close(this, eventId)
        EventHistoryStore.add(this, "ALARM", "ALARM_CLOSED", eventId)
        sendAck(event.sourceNodeId, eventId, "ALARM_CLOSED", "watch")
    }


    private fun isTrustedAlarmNode(nodeId: String): Boolean {
        val pairedNodeId = DeviceStore.remote(this)?.nodeId
        return pairedNodeId.isNullOrBlank() || pairedNodeId == nodeId
    }

    private fun sendAck(nodeId: String, eventId: String, result: String, detail: String) {
        val ack = MessageEnvelope(
            type = "ACK",
            eventId = eventId,
            payload = JSONObject()
                .put("result", result)
                .put("detail", detail)
                .put("receivedAt", System.currentTimeMillis()),
        )
        WearTransport.sendDirect(this, nodeId, Protocol.ACK, ack)
    }
}
