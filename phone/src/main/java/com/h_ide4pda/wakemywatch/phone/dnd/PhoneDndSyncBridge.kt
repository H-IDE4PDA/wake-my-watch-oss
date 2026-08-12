package com.h_ide4pda.wakemywatch.phone.dnd

import android.app.NotificationManager
import android.content.Context
import com.h_ide4pda.wakemywatch.core.AppSettings
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.DndSync
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.WearTransport
import com.h_ide4pda.wakemywatch.phone.permissions.PermissionProbe
import com.h_ide4pda.wakemywatch.phone.wear.PhoneDndSyncAckTransfer
import java.util.concurrent.TimeUnit

object PhoneDndSyncBridge {
    /**
     * Defense in depth: on OnePlus/Oppo/Realme phones OHealth already syncs DND natively, and the
     * watch has no way to know the phone's brand — it trusts whatever dndSyncEnabled value the phone
     * sends. A stuck dndSyncEnabled=true (e.g. left over from a diagnostic branch) must never reach
     * the watch on these phones, so every settings payload sent to the watch is masked here first.
     */
    fun maskForNativeOHealth(context: Context, settings: AppSettings): AppSettings =
        if (PermissionProbe.snapshot(context).nativeOHealthPhone) settings.copy(dndSyncEnabled = false) else settings

    /** Marks diagnostics entries when the user has the software DND sync toggle on, so on-device
     * logs make it visible the sync ran via our own code path, not OHealth's native sync. */
    private fun modeSuffix(context: Context): String =
        if (AppSettingsStore.load(context).forceSoftwareDndSync) " mode=software" else ""

    /**
     * Sent directly (bypassing dndSyncEnabled, which the caller is about to flip to false) so the
     * watch still accepts it, regardless of the phone's own current DND state. Blocks (call off
     * the main thread) until the watch's real ACK confirms the filter was applied or was already
     * clear — not just that the message reached the transport — up to two 6s attempts.
     *
     * Ordering invariant: the caller MUST NOT send the disabling SETTINGS sync
     * (dndSyncEnabled=false) until this returns. That's what guarantees the watch still has
     * dndSyncEnabled=true — and therefore accepts the clear — when the command arrives.
     * Call sites should go through PhoneMainActivity's disableSoftwareDndAndClear(), which
     * enforces this ordering (blocking clear off-thread, then onSettings back on the main
     * dispatcher) for every place a DND sync toggle can turn off.
     *
     * If both attempts fail (watch genuinely unreachable — frozen process, dropped Wi-Fi/BT),
     * the watch is not abandoned in DND: a pending-clear flag is persisted and retried the next
     * time the watch reconnects (see retryPendingClearIfNeeded, called from
     * PhoneWearListenerService on peer-connect / handshake).
     */
    fun clearWatchDndBlocking(context: Context, trigger: String = "toggle_off"): Boolean {
        val applied = clearWatchDndOnce(context, trigger, attempt = 1) || clearWatchDndOnce(context, trigger, attempt = 2)
        if (applied) {
            PendingDndClear.clear(context)
            EventHistoryStore.add(context, "DND_SYNC", "CLEARED_ON_DISABLE", "trigger=$trigger")
        } else {
            PendingDndClear.set(context)
            EventHistoryStore.add(context, "DND_SYNC", "CLEAR_ON_DISABLE_FAILED", "trigger=$trigger")
            EventHistoryStore.add(context, "DND_SYNC", "CLEAR_DEFERRED", "watch_unreachable, will retry on next reconnect trigger=$trigger")
        }
        return applied
    }

    /**
     * Call opportunistically whenever the watch becomes reachable again (peer connect, HELLO_ACK).
     * No-ops if nothing is pending. Blocking; call off the main thread (e.g. ioExecutor).
     */
    fun retryPendingClearIfNeeded(context: Context, trigger: String) {
        if (!PendingDndClear.isPending(context)) return
        EventHistoryStore.add(context, "DND_SYNC", "CLEAR_DEFERRED_RETRY", "trigger=$trigger")
        clearWatchDndBlocking(context, trigger = "deferred_retry_$trigger")
    }

    /**
     * The user re-enabled software DND sync before a deferred clear ever ran — they now want the
     * watch under our control again, so an old "clear DND on next reconnect" intent is stale.
     */
    fun cancelPendingClear(context: Context) {
        if (!PendingDndClear.isPending(context)) return
        PendingDndClear.clear(context)
        EventHistoryStore.add(context, "DND_SYNC", "CLEAR_DEFERRED_CANCELLED", "reason=dnd_sync_reenabled")
    }

    private fun clearWatchDndOnce(context: Context, trigger: String, attempt: Int): Boolean {
        val (result, detail) = sendDndSyncAndAwaitAck(
            context,
            NotificationManager.INTERRUPTION_FILTER_ALL,
            reason = "sync_disabled_$trigger",
            trigger = trigger,
            attempt = attempt,
        )
        return result == "DND_SYNC_APPLIED_WATCH" ||
            (result == "DND_SYNC_SKIPPED" && detail.startsWith("already_applied_watch"))
    }

    /**
     * Turning software DND sync on should immediately reflect the phone's current DND state on
     * the watch, not wait for the next time the phone's DND happens to change — otherwise a
     * phone that's already in DND when sync is enabled leaves the watch out of DND indefinitely
     * (symmetric to the clear-on-disable problem above). Blocks (call off the main thread) until
     * the watch's real ACK confirms the filter was applied or was already matching — up to two
     * 6s attempts.
     *
     * Ordering invariant: the caller MUST send the enabling SETTINGS sync (dndSyncEnabled=true)
     * BEFORE calling this — reversed from the disable case — so the watch already has
     * dndSyncEnabled=true when this command arrives. Call sites should go through PhoneMainActivity's
     * enableSoftwareDndAndApply(), which enforces this ordering for every place a DND sync toggle
     * can turn on.
     */
    fun applyCurrentDndOnEnableBlocking(context: Context, trigger: String = "toggle_on"): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val applied = applyDndOnEnableOnce(context, filter, trigger, attempt = 1) ||
            applyDndOnEnableOnce(context, filter, trigger, attempt = 2)
        EventHistoryStore.add(
            context,
            "DND_SYNC",
            if (applied) "APPLIED_ON_ENABLE" else "APPLY_ON_ENABLE_FAILED",
            "filter=$filter trigger=$trigger",
        )
        return applied
    }

    private fun applyDndOnEnableOnce(context: Context, filter: Int, trigger: String, attempt: Int): Boolean {
        val (result, detail) = sendDndSyncAndAwaitAck(
            context,
            filter,
            reason = "sync_enabled_$trigger",
            trigger = trigger,
            attempt = attempt,
        )
        return result == "DND_SYNC_APPLIED_WATCH" ||
            (result == "DND_SYNC_SKIPPED" && detail.startsWith("already_applied_watch"))
    }

    private fun sendDndSyncAndAwaitAck(context: Context, filter: Int, reason: String, trigger: String, attempt: Int): Pair<String, String> {
        val requestId = Protocol.eventId()
        val future = PhoneDndSyncAckTransfer.prepare(requestId)
        val envelope = MessageEnvelope(
            type = DndSync.TYPE,
            eventId = requestId,
            payload = DndSync.payload(filter, DndSync.SOURCE_PHONE, reason = reason),
        )
        WearTransport.sendPreferred(context, Protocol.DND_SYNC, envelope) { result ->
            EventHistoryStore.add(
                context,
                "DND_SYNC",
                if (result.success) "SENT_PHONE_TO_WATCH" else "SEND_FAILED_PHONE_TO_WATCH",
                "filter=$filter transport=${result.detail} trigger=$trigger attempt=$attempt",
            )
            if (!result.success) PhoneDndSyncAckTransfer.fail(requestId)
        }
        return try {
            future.get(6, TimeUnit.SECONDS)
        } catch (error: Exception) {
            PhoneDndSyncAckTransfer.cancel(requestId)
            "TIMEOUT" to "no_ack"
        }
    }

    fun onLocalInterruptionFilterChanged(context: Context, interruptionFilter: Int) {
        if (!DndSync.isValidInterruptionFilter(interruptionFilter)) {
            EventHistoryStore.add(context, "DND_SYNC", "SKIPPED", "invalid_local_filter=$interruptionFilter")
            return
        }
        val settings = AppSettingsStore.load(context)
        if (!settings.dndSyncEnabled) {
            EventHistoryStore.add(context, "DND_SYNC", "SKIPPED", "disabled_phone_change filter=$interruptionFilter")
            return
        }
        if (settings.isFullyPaused) {
            EventHistoryStore.add(context, "DND_SYNC", "SKIPPED", "app_paused filter=$interruptionFilter")
            return
        }
        val probe = PermissionProbe.snapshot(context)
        if (probe.nativeOHealthPhone) {
            EventHistoryStore.add(context, "DND_SYNC", "SKIPPED", "native_ohealth_phone filter=$interruptionFilter")
            return
        }
        if (DndSync.shouldSuppressEcho(context, interruptionFilter)) {
            EventHistoryStore.add(
                context,
                "DND_SYNC",
                "SKIPPED",
                "echo_from_${DndSync.lastRemoteSource(context).orEmpty()} filter=$interruptionFilter",
            )
            return
        }

        val envelope = MessageEnvelope(
            type = DndSync.TYPE,
            payload = DndSync.payload(interruptionFilter, DndSync.SOURCE_PHONE),
        )
        WearTransport.sendPreferred(context, Protocol.DND_SYNC, envelope) { result ->
            EventHistoryStore.add(
                context,
                "DND_SYNC",
                if (result.success) "SENT_PHONE_TO_WATCH" else "SEND_FAILED_PHONE_TO_WATCH",
                "filter=$interruptionFilter transport=${result.detail}${modeSuffix(context)}",
            )
        }
    }

    /**
     * DND sync is one-way (phone -> watch): the watch's own DND is never allowed to write back
     * into the phone's real NotificationManager, since a toggle in this app must only ever affect
     * the watch. This handler now only exists so an ACK is still returned if an older watch build
     * (or any stray in-flight message) sends DND_SYNC — it never touches the phone's DND.
     */
    fun applyIncomingFromWatch(context: Context, envelope: MessageEnvelope): Pair<String, String> {
        val filter = DndSync.filterFrom(envelope) ?: return "DND_SYNC_REJECTED" to "invalid_filter"
        EventHistoryStore.add(context, "DND_SYNC", "IGNORED_WATCH_TO_PHONE", "filter=$filter one_way_phone_to_watch_only")
        return "DND_SYNC_IGNORED" to "one_way_phone_to_watch_only"
    }
}
