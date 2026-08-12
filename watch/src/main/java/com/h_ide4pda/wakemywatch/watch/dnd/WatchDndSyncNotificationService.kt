package com.h_ide4pda.wakemywatch.watch.dnd

import android.service.notification.NotificationListenerService
import com.h_ide4pda.wakemywatch.core.EventHistoryStore

class WatchDndSyncNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        EventHistoryStore.add(this, "DND_SYNC", "WATCH_LISTENER_CONNECTED", "notification_listener")
        WatchDndPermissionState.sendStatusToPhone(this, "listener_connected")
    }

    override fun onListenerDisconnected() {
        EventHistoryStore.add(this, "DND_SYNC", "WATCH_LISTENER_DISCONNECTED", "notification_listener")
        super.onListenerDisconnected()
    }

    // DND sync is one-way (phone -> watch): the watch's own interruption filter changes are
    // never relayed back to the phone, so onInterruptionFilterChanged has nothing to forward.
}
