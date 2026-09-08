package com.h_ide4pda.wakemywatch.phone.ringer

import android.content.Context
import android.media.AudioManager
import android.app.NotificationManager
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.RingerSync
import com.h_ide4pda.wakemywatch.core.WearTransport
import com.h_ide4pda.wakemywatch.phone.wear.PhoneDndSyncAckTransfer
import java.util.concurrent.TimeUnit

/**
 * Two-way sound-profile (ringer mode) sync. Structurally parallel to
 * [com.h_ide4pda.wakemywatch.phone.dnd.PhoneDndSyncBridge] but a distinct concern, a distinct
 * protocol path ([Protocol.RINGER_SYNC]) and — unlike DND — two-way. The loop is broken with
 * [RingerSync.shouldSuppressEcho], not by dropping the reverse path.
 */
object RingerSyncBridge {

    /** Called from the RINGER_MODE_CHANGED_ACTION receiver registered by NotificationRelayService. */
    fun onPhoneRingerModeChanged(context: Context, mode: Int) {
        if (!RingerSync.isValidRingerMode(mode)) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "invalid_local_mode=$mode")
            return
        }
        val settings = AppSettingsStore.load(context)
        if (!settings.ringerSyncEnabled) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "disabled_phone_change mode=${RingerSync.name(mode)}")
            return
        }
        if (settings.isFullyPaused) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "app_paused mode=${RingerSync.name(mode)}")
            return
        }
        // This local change is our own echo of a change the watch just pushed — don't send it
        // back, or phone and watch chase each other.
        if (RingerSync.shouldSuppressEcho(context, mode)) {
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                "SKIPPED",
                "echo source=${RingerSync.lastRemoteSource(context)} mode=${RingerSync.name(mode)}",
            )
            return
        }
        val envelope = MessageEnvelope(
            type = RingerSync.TYPE,
            payload = RingerSync.payload(mode, RingerSync.SOURCE_PHONE),
        )
        WearTransport.sendPreferred(context, Protocol.RINGER_SYNC, envelope) { result ->
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                if (result.success) "SENT" else "SEND_FAILED",
                "source=phone->watch mode=${RingerSync.name(mode)} transport=${result.detail}",
            )
        }
    }

    /** Applies a ringer-mode change the watch pushed. Mirror of
     * [com.h_ide4pda.wakemywatch.watch.ringer.WatchRingerSyncBridge.applyIncomingFromPhone]. */
    fun applyIncomingFromWatch(context: Context, envelope: MessageEnvelope): Pair<String, String> {
        val mode = RingerSync.modeFrom(envelope) ?: return "RINGER_SYNC_REJECTED" to "invalid_mode"
        val source = RingerSync.sourceFrom(envelope)
        val settings = AppSettingsStore.load(context)
        if (!settings.ringerSyncEnabled) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "disabled_incoming source=$source mode=${RingerSync.name(mode)}")
            return "RINGER_SYNC_SKIPPED" to "disabled_phone"
        }
        // Backstop for the watch-side gate (its settings copy can lag). Watch -> phone leg off:
        // drop the change instead of applying it.
        if (!settings.ringerReverseSyncEnabled) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "${RingerSync.SKIP_REVERSE_DISABLED} source=$source mode=${RingerSync.name(mode)}")
            return "RINGER_SYNC_SKIPPED" to RingerSync.SKIP_REVERSE_DISABLED
        }
        // Backstop for the watch-side filter: "silent" from the watch is sleep-mode automation,
        // not a user action, and must not drop the phone into Do Not Disturb by itself at night.
        if (source == RingerSync.SOURCE_WATCH && mode == AudioManager.RINGER_MODE_SILENT) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "${RingerSync.SKIP_WATCH_SILENT} source=$source")
            return "RINGER_SYNC_SKIPPED" to RingerSync.SKIP_WATCH_SILENT
        }
        if (settings.isFullyPaused) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "app_paused source=$source mode=${RingerSync.name(mode)}")
            return "RINGER_SYNC_SKIPPED" to "app_paused"
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return "RINGER_SYNC_FAILED_PHONE" to "no_audio_manager"
        val current = runCatching { audioManager.ringerMode }.getOrNull()
        if (current == mode) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "already_applied_phone source=$source mode=${RingerSync.name(mode)}")
            return "RINGER_SYNC_SKIPPED" to "already_applied_phone mode=${RingerSync.name(mode)}"
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val policyGranted = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)
        if (mode == AudioManager.RINGER_MODE_SILENT && !policyGranted) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "phone_policy_access_missing source=$source mode=SILENT")
            return "RINGER_SYNC_SKIPPED" to "phone_policy_access_missing"
        }
        return runCatching {
            // Mark BEFORE writing so the RINGER_MODE_CHANGED_ACTION this triggers is recognised
            // as an echo the moment it fires.
            RingerSync.markRemoteApplied(context, mode, source)
            audioManager.ringerMode = mode
            val readback = runCatching { audioManager.ringerMode }.getOrDefault(mode)
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                "APPLIED",
                "source=$source mode=${RingerSync.name(mode)} previous=${current?.let(RingerSync::name) ?: "unknown"} readback=${RingerSync.name(readback)}",
            )
            "RINGER_SYNC_APPLIED_PHONE" to "mode=${RingerSync.name(mode)}"
        }.getOrElse { error ->
            val detail = error.message ?: error.javaClass.simpleName
            EventHistoryStore.add(context, "RINGER_SYNC", "APPLY_FAILED", "source=$source $detail")
            "RINGER_SYNC_FAILED_PHONE" to detail
        }
    }

    /**
     * Turning the toggle on should push the phone's current profile to the watch right away, not
     * wait for the next time the profile happens to change — symmetric to DND's
     * applyCurrentDndOnEnableBlocking. Blocks (call off the main thread) until the watch's real
     * ACK confirms it applied or already matched, up to two 6s attempts.
     *
     * Unlike DND there is no ordering invariant with the SETTINGS sync: the watch does not gate
     * RINGER_SYNC on ringerSyncEnabled (a direct pipe, matching the phone which already decided
     * to send), so the caller may flip the setting before or after this.
     */
    fun applyCurrentRingerOnEnableBlocking(context: Context, trigger: String = "toggle_on"): Boolean {
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        val applied = applyOnceAndAwaitAck(context, mode, trigger, attempt = 1) ||
            applyOnceAndAwaitAck(context, mode, trigger, attempt = 2)
        EventHistoryStore.add(
            context,
            "RINGER_SYNC",
            if (applied) "APPLIED_ON_ENABLE" else "APPLY_ON_ENABLE_FAILED",
            "mode=${RingerSync.name(mode)} trigger=$trigger",
        )
        return applied
    }

    private fun applyOnceAndAwaitAck(context: Context, mode: Int, trigger: String, attempt: Int): Boolean {
        val requestId = Protocol.eventId()
        val future = PhoneDndSyncAckTransfer.prepare(requestId)
        val envelope = MessageEnvelope(
            type = RingerSync.TYPE,
            eventId = requestId,
            payload = RingerSync.payload(mode, RingerSync.SOURCE_PHONE, reason = "sync_enabled_$trigger"),
        )
        WearTransport.sendPreferred(context, Protocol.RINGER_SYNC, envelope) { result ->
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                if (result.success) "SENT_PHONE_TO_WATCH" else "SEND_FAILED_PHONE_TO_WATCH",
                "mode=${RingerSync.name(mode)} transport=${result.detail} trigger=$trigger attempt=$attempt",
            )
            if (!result.success) PhoneDndSyncAckTransfer.fail(requestId)
        }
        val (result, detail) = try {
            future.get(6, TimeUnit.SECONDS)
        } catch (error: Exception) {
            PhoneDndSyncAckTransfer.cancel(requestId)
            "TIMEOUT" to "no_ack"
        }
        return result == "RINGER_SYNC_APPLIED_WATCH" ||
            (result == "RINGER_SYNC_SKIPPED" && detail.startsWith("already_applied_watch"))
    }
}
