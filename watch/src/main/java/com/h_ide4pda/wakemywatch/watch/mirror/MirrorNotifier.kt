package com.h_ide4pda.wakemywatch.watch.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.h_ide4pda.wakemywatch.watch.R

/**
 * Shows a notification the bridge never delivered.
 *
 * Only used for notifications the phone marked as provably undelivered (a lone group summary —
 * Reddit chat DMs), so this never competes with what OHealth already puts on the watch.
 *
 * The channel is IMPORTANCE_HIGH so Wear OS draws a real card and a watch-face dot — the whole
 * point is visibility. Its own sound and vibration are switched off: Wake My Watch has already
 * buzzed the wrist by the time this is posted, and a second buzz would read as two notifications.
 */
object MirrorNotifier {
    private const val CHANNEL_ID = "wmw_mirrored_notifications"

    fun show(
        context: Context,
        title: String,
        text: String,
        appLabel: String,
        notificationKey: String,
    ): String {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return "mirror_blocked_no_permission"

        ensureChannel(context)
        val heading = title.ifBlank { appLabel }
        val body = text.ifBlank { appLabel }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(appLabel)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .build()

        // Keyed on the phone-side notification key so an edit of the same conversation replaces
        // the card instead of stacking a second one.
        return runCatching {
            manager.notify(notificationKey.hashCode(), notification)
            "mirror_shown"
        }.getOrDefault("mirror_failed")
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mirror_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }
}
