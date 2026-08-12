package com.h_ide4pda.wakemywatch.watch.dnd

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.WearTransport
import org.json.JSONObject

object WatchDndPermissionState {
    const val PREFS_NAME = "wmw_watch_dnd_permissions"
    private const val SETUP_REQUESTED_AT = "setup_requested_at"

    data class Snapshot(
        val notificationListenerGranted: Boolean,
        val dndPolicyAccessGranted: Boolean,
        val currentInterruptionFilter: Int,
    ) {
        val ready: Boolean get() = notificationListenerGranted && dndPolicyAccessGranted
    }

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        return Snapshot(
            notificationListenerGranted = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
            dndPolicyAccessGranted = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false),
            currentInterruptionFilter = runCatching { notificationManager.currentInterruptionFilter }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN),
        )
    }

    fun requestSetup(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(SETUP_REQUESTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun consumeSetupRequest(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestedAt = prefs.getLong(SETUP_REQUESTED_AT, 0L)
        if (requestedAt <= 0L) return false
        prefs.edit().remove(SETUP_REQUESTED_AT).apply()
        return true
    }

    fun sendStatusToPhone(context: Context, reason: String) {
        val status = snapshot(context)
        val envelope = MessageEnvelope(
            type = "DND_PERMISSION_STATUS",
            payload = JSONObject()
                .put("reason", reason)
                .put("notificationListenerGranted", status.notificationListenerGranted)
                .put("dndPolicyAccessGranted", status.dndPolicyAccessGranted)
                .put("ready", status.ready)
                .put("currentInterruptionFilter", status.currentInterruptionFilter),
        )
        WearTransport.sendPreferred(context, Protocol.DND_PERMISSION_STATUS, envelope) { result ->
            EventHistoryStore.add(
                context,
                "DND_SETUP",
                if (result.success) "WATCH_STATUS_SENT" else "WATCH_STATUS_SEND_FAILED",
                "reason=$reason ready=${status.ready} transport=${result.detail}",
            )
        }
    }
}
