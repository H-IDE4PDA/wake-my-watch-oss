package com.h_ide4pda.wakemywatch.watch.ringer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.RingerSync
import com.h_ide4pda.wakemywatch.core.WearTransport

/**
 * Two-way sound-profile (ringer mode) sync, watch side. Applies changes the phone pushes and
 * pushes the watch's own changes back — the loop is broken with [RingerSync.shouldSuppressEcho].
 *
 * A distinct concern from [com.h_ide4pda.wakemywatch.watch.dnd.WatchDndSyncBridge]: this changes
 * the ringer mode (whether notifications make a sound), not the interruption filter (which
 * notifications get through), and unlike DND it is two-way.
 */
object WatchRingerSyncBridge {
    // Wear OS's own "match phone sound settings" (Settings.Secure.sync_parent_sounds) can, in
    // theory, re-assert the phone's profile on top of ours. Re-read the ringer mode this long
    // after applying and log it if it drifted, so on-device diagnostics show whether the
    // firmware is fighting us.
    private const val OVERRIDE_CHECK_DELAY_MS = 4_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun applyIncomingFromPhone(context: Context, envelope: MessageEnvelope): Pair<String, String> {
        val mode = RingerSync.modeFrom(envelope) ?: return "RINGER_SYNC_REJECTED" to "invalid_mode"
        val source = RingerSync.sourceFrom(envelope)
        val settings = AppSettingsStore.load(context)
        // ringerSyncEnabled is not re-checked here (mirrors WatchDndSyncBridge): it's the phone's
        // toggle, which already gated this message, and re-checking the watch's own possibly-stale
        // copy only adds a race. A full pause still silences it.
        if (settings.isFullyPaused) return "RINGER_SYNC_SKIPPED" to "app_paused"

        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return "RINGER_SYNC_FAILED_WATCH" to "no_audio_manager"
        val current = runCatching { audioManager.ringerMode }.getOrNull()
        if (current == mode) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "already_applied_watch source=$source mode=${RingerSync.name(mode)}")
            return "RINGER_SYNC_SKIPPED" to "already_applied_watch mode=${RingerSync.name(mode)}"
        }
        // Going to SILENT requires notification-policy access on Android 6+. VIBRATE/NORMAL do
        // not, but the check is cheap and the log is worth having when it's missing.
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val policyGranted = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)
        if (mode == AudioManager.RINGER_MODE_SILENT && !policyGranted) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "watch_policy_access_missing mode=SILENT")
            return "RINGER_SYNC_SKIPPED" to "watch_policy_access_missing"
        }

        return runCatching {
            // Mark BEFORE writing so the RINGER_MODE_CHANGED_ACTION this triggers is recognised
            // as an echo by onWatchRingerModeChanged the moment it fires — not sent back.
            RingerSync.markRemoteApplied(context, mode, source)
            audioManager.ringerMode = mode
            val applied = runCatching { audioManager.ringerMode }.getOrDefault(mode)
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                "APPLIED",
                "source=$source mode=${RingerSync.name(mode)} previous=${current?.let(RingerSync::name) ?: "unknown"} readback=${RingerSync.name(applied)}",
            )
            scheduleOverrideCheck(context, mode)
            "RINGER_SYNC_APPLIED_WATCH" to "mode=${RingerSync.name(mode)} previous=${current?.let(RingerSync::name) ?: "unknown"}"
        }.getOrElse { error ->
            val detail = error.message ?: error.javaClass.simpleName
            EventHistoryStore.add(context, "RINGER_SYNC", "APPLY_FAILED", "source=$source $detail")
            "RINGER_SYNC_FAILED_WATCH" to detail
        }
    }

    /**
     * The watch's own sound profile changed. Push it to the phone unless it's the echo of a
     * change the phone just pushed to us. Called from [WatchRingerModeWatcher].
     */
    fun onWatchRingerModeChanged(context: Context, mode: Int) {
        if (!RingerSync.isValidRingerMode(mode)) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "invalid_local_mode=$mode")
            return
        }
        val settings = AppSettingsStore.load(context)
        if (!settings.ringerSyncEnabled) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "disabled_watch_change mode=${RingerSync.name(mode)}")
            return
        }
        if (settings.isFullyPaused) {
            EventHistoryStore.add(context, "RINGER_SYNC", "SKIPPED", "app_paused mode=${RingerSync.name(mode)}")
            return
        }
        // Watch -> phone leg is opt-in (default off). When off, the sync is one way only.
        if (!settings.ringerReverseSyncEnabled) {
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                "SKIPPED",
                "${RingerSync.SKIP_REVERSE_DISABLED} mode=${RingerSync.name(mode)}",
            )
            return
        }
        // Never forward "silent" from the watch: it has no user-facing silent profile, so this
        // is the sleep/bedtime automation talking, not the user — forwarding it would drop the
        // phone into Do Not Disturb on its own at night. "Sound"/"vibrate" carry over as usual;
        // phone -> watch still sends "silent" as before.
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                "SKIPPED",
                "${RingerSync.SKIP_WATCH_SILENT} source=watch->phone (sleep-mode automation, not forwarded)",
            )
            return
        }
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
            payload = RingerSync.payload(mode, RingerSync.SOURCE_WATCH),
        )
        WearTransport.sendPreferred(context, Protocol.RINGER_SYNC, envelope) { result ->
            EventHistoryStore.add(
                context,
                "RINGER_SYNC",
                if (result.success) "SENT" else "SEND_FAILED",
                "source=watch->phone mode=${RingerSync.name(mode)} transport=${result.detail}",
            )
        }
    }

    /** Diagnostic only: logs if the firmware moved the ringer mode away from what we just set. */
    private fun scheduleOverrideCheck(context: Context, expectedMode: Int) {
        val appContext = context.applicationContext
        mainHandler.postDelayed({
            val now = runCatching { appContext.getSystemService(AudioManager::class.java).ringerMode }.getOrNull() ?: return@postDelayed
            if (now != expectedMode) {
                EventHistoryStore.add(
                    appContext,
                    "RINGER_SYNC",
                    "OVERRIDDEN",
                    "expected=${RingerSync.name(expectedMode)} actual=${RingerSync.name(now)} after=${OVERRIDE_CHECK_DELAY_MS}ms (likely sync_parent_sounds)",
                )
            }
        }, OVERRIDE_CHECK_DELAY_MS)
    }
}
