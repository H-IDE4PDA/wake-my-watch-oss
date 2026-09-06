package com.h_ide4pda.wakemywatch.phone.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class NotificationDeduplicator {
    private data class Seen(val at: Long, val contentHash: String, val key: String, val group: String?, val postTime: Long)
    private val byKey = ConcurrentHashMap<String, Seen>()
    private val recentByPackage = ConcurrentHashMap<String, Seen>()

    fun shouldSkip(sbn: StatusBarNotification, now: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        val n = sbn.notification
        // Group summaries used to be dropped unconditionally, on the assumption that a summary
        // always ships alongside children and the children are the real notifications. Reddit's
        // chat DMs break it: each one is posted as a lone summary (id=0, tag=room) with no child
        // at all, so the message vanished before it could reach the watch. Summaries now go
        // through the ordinary rules — the related_duplicate check below already collapses a
        // summary and its children into one wake, and the wake cooldown catches late stragglers.
        val contentHash = contentFingerprint(sbn)
        val group = sbn.groupKey
        val current = Seen(now, contentHash, sbn.key, group, sbn.postTime)

        val sameKey = byKey.put(sbn.key, current)
        // postTime is a safety-net differentiator: messenger apps (MessagingStyle, e.g.
        // Telegram) often keep EXTRA_TITLE/EXTRA_TEXT/EXTRA_BIG_TEXT static across messages in
        // the same conversation, with the real per-message text only in EXTRA_MESSAGES (read
        // below). If that still somehow hashes identically, a genuinely new post always carries
        // a fresh postTime, while a true re-post of the same notification keeps the same one.
        if (sameKey != null && sameKey.contentHash == contentHash && sameKey.postTime == sbn.postTime) {
            return true to "same_key_unchanged"
        }

        val packageSeen = recentByPackage.put(sbn.packageName, current)
        if (packageSeen != null && now - packageSeen.at < 750) {
            val relatedGroup = group != null && group == packageSeen.group
            val sameContent = contentHash == packageSeen.contentHash
            if ((relatedGroup || sameContent) && packageSeen.key != sbn.key) return true to "related_duplicate"
        }

        cleanup(now)
        return false to "new"
    }

    fun onRemoved(notificationKey: String) {
        byKey.remove(notificationKey)
        recentByPackage.entries.removeIf { it.value.key == notificationKey }
    }

    fun contentFingerprint(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            .orEmpty()
            .joinToString("\n") { it?.toString().orEmpty() }
        // MessagingStyle apps (Telegram and most chat apps) keep EXTRA_TITLE/EXTRA_TEXT/
        // EXTRA_BIG_TEXT static (a generic fallback for older surfaces) — the real per-message
        // text only lives in EXTRA_MESSAGES. Without reading it, every message in the same
        // conversation hashes identically and gets dropped as an unchanged repost.
        val lastMessage = runCatching {
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                ?.lastOrNull()
        }.getOrNull()
        val fields = listOf(
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            lines,
            lastMessage?.text?.toString().orEmpty(),
            lastMessage?.timestamp?.toString().orEmpty(),
            n.category.orEmpty(),
        )
        val separator = 0.toChar().toString()
        return hash(fields.joinToString(separator))
    }

    private fun cleanup(now: Long) {
        byKey.entries.removeIf { now - it.value.at > 60_000 }
        recentByPackage.entries.removeIf { now - it.value.at > 60_000 }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
