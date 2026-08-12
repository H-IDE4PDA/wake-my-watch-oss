package com.h_ide4pda.wakemywatch.watch.dnd

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.DndSync
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope

object WatchDndSyncBridge {
    // The system needs a moment after setInterruptionFilter(ALL) to actually lift its own DND
    // enforcement — a buzz fired immediately still lands while the old (blocking) policy is in
    // effect. Delaying only the clear-side buzz past that window is cheaper and more portable
    // than relying on a specific VibrationAttributes usage being exempt on every OEM skin.
    private const val CLEAR_VIBRATE_DELAY_MS = 300L
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Marks diagnostics entries when the user has the software DND sync toggle on, so on-device
     * logs make it visible the sync ran via our own code path, not OHealth's native sync. */
    private fun modeSuffix(forceSoftwareDndSync: Boolean): String = if (forceSoftwareDndSync) " mode=software" else ""

    fun applyIncomingFromPhone(context: Context, envelope: MessageEnvelope): Pair<String, String> {
        val filter = DndSync.filterFrom(envelope) ?: return "DND_SYNC_REJECTED" to "invalid_filter"
        val settings = AppSettingsStore.load(context)
        // dndSyncEnabled is not re-checked here: it's a mirror of the phone's own toggle, which
        // already gated this message before it was sent, and re-checking the watch's local copy
        // only reproduced the "disabled_on_watch" race described above (SETTINGS sync landing
        // after DND_SYNC) — direct pipe, matching the phone side that already decided to send.
        if (settings.isFullyPaused) return "DND_SYNC_SKIPPED" to "app_paused"

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val current = notificationManager.currentInterruptionFilter
        if (current == filter) return "DND_SYNC_SKIPPED" to "already_applied_watch filter=$filter"
        if (!runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)) {
            WatchDndPermissionState.requestSetup(context)
            WatchDndPermissionState.sendStatusToPhone(context, "watch_dnd_policy_missing")
            return "DND_SYNC_SKIPPED" to "watch_dnd_policy_missing"
        }

        return runCatching {
            DndSync.markRemoteApplied(context, filter, DndSync.SOURCE_PHONE)
            notificationManager.setInterruptionFilter(filter)
            if (settings.dndSyncVibrate) {
                // Turning DND on: the old (permissive) policy is still what's enforced for a
                // moment, so an immediate buzz goes through untouched. Clearing DND: the old
                // (blocking) policy is still enforced for a moment, so the buzz must wait it out.
                if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    scheduleDelayedVibrate(context)
                } else {
                    vibrate(context)
                }
            }
            EventHistoryStore.add(context, "DND_SYNC", "APPLIED_PHONE_TO_WATCH", "filter=$filter previous=$current${modeSuffix(settings.forceSoftwareDndSync)}")
            "DND_SYNC_APPLIED_WATCH" to "filter=$filter previous=$current"
        }.getOrElse { error ->
            val detail = error.message ?: error.javaClass.simpleName
            EventHistoryStore.add(context, "DND_SYNC", "APPLY_FAILED_PHONE_TO_WATCH", detail)
            "DND_SYNC_FAILED_WATCH" to detail
        }
    }

    /** Fired only for the clear side (filter == ALL): posts the buzz CLEAR_VIBRATE_DELAY_MS after
     * setInterruptionFilter() already returned, so the actual DND apply and this function's
     * return are never delayed — only the vibration itself waits. By the time it fires, the
     * system has had time to actually lift its own DND enforcement, which the immediate call
     * used to race against and lose (a USAGE_HARDWARE_FEEDBACK attribute alone wasn't enough on
     * this OEM skin). Uses the application context since the calling service may already be
     * gone by the time this runs. */
    private fun scheduleDelayedVibrate(context: Context) {
        val appContext = context.applicationContext
        mainHandler.postDelayed({ vibrate(appContext) }, CLEAR_VIBRATE_DELAY_MS)
    }

    /** Short confirmation buzz on a real DND change. Turning DND on calls this immediately (the
     * old, permissive policy is still in effect for a moment, so the buzz goes through
     * untouched); clearing DND goes through scheduleDelayedVibrate() instead, since an immediate
     * call there still races the system's own DND enforcement. USAGE_HARDWARE_FEEDBACK is kept
     * as a second line of defense — a direct user-feedback usage that Zen policy shouldn't gate
     * at all — but the delay is what actually fixes the clear-side case. Best-effort: a missing
     * vibrator or a vibration failure must never fail the DND apply itself. */
    private fun vibrate(context: Context) {
        runCatching {
            val vibratorManager = context.getSystemService(VibratorManager::class.java) ?: return
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                .build()
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE),
                attributes,
            )
        }
    }
}
