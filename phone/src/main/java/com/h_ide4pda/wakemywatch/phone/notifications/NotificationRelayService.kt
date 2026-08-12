package com.h_ide4pda.wakemywatch.phone.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.MessageEnvelope
import com.h_ide4pda.wakemywatch.core.Protocol
import com.h_ide4pda.wakemywatch.core.WearTransport
import com.h_ide4pda.wakemywatch.phone.alarm.AlarmBridgeController
import com.h_ide4pda.wakemywatch.phone.dnd.PhoneDndSyncBridge
import com.h_ide4pda.wakemywatch.phone.settings.PhoneSettings
import com.h_ide4pda.wakemywatch.phone.settings.PhoneSettingsStore
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotificationRelayService : NotificationListenerService() {
    private val deduplicator = NotificationDeduplicator()
    private val wakeRateLimiter = WakeRateLimiter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, PendingForward>()
    private val listenerSessionId = UUID.randomUUID().toString().take(8)
    private var listenerConnectedElapsed = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnectedElapsed = SystemClock.elapsedRealtime()
        AlarmBridgeController.attach(this)
        EventHistoryStore.add(
            this,
            "LISTENER",
            "CONNECTED",
            "session=$listenerSessionId active=${activeNotifications.orEmpty().size}",
        )
        EventHistoryStore.add(
            this,
            "SETTINGS_SNAPSHOT",
            "listener_connected",
            com.h_ide4pda.wakemywatch.core.SettingsAudit.snapshot(PhoneSettingsStore.load(this)),
        )
        if (PhoneSettingsStore.load(this).alarmBridge) {
            activeNotifications.orEmpty().forEach { relayAlarmIfHandled(it) }
        }
    }

    override fun onListenerDisconnected() {
        val pendingCount = pending.size
        cancelPending()
        AlarmBridgeController.detach(this)
        EventHistoryStore.add(
            this,
            "LISTENER",
            "DISCONNECTED",
            "session=$listenerSessionId ageMs=${listenerAgeMs()} pending=$pendingCount",
        )
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cancelPending()
        AlarmBridgeController.detach(this)
        super.onDestroy()
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        super.onInterruptionFilterChanged(interruptionFilter)
        PhoneDndSyncBridge.onLocalInterruptionFilterChanged(this, interruptionFilter)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val settings = PhoneSettingsStore.load(this)

        // Alarm Bridge has its own explicit policy and must not be suppressed by the
        // ordinary phone-unlocked notification rule. A full pause silences it too; the
        // sleep-safe pause mode (pauseKeepsAlarmAndDnd) deliberately leaves it running.
        if (settings.alarmBridge && !settings.isFullyPaused && relayAlarmIfHandled(sbn)) return

        val now = System.currentTimeMillis()
        val phoneState = PhoneInteraction.read(this)
        val snapshot = inspectNotification(sbn, phoneState, settings, now)

        if (settings.appPaused) {
            skipNotification(sbn, "app_paused", snapshot)
            return
        }
        if (!settings.screenWake) {
            skipNotification(sbn, "screen_wake_disabled", snapshot)
            return
        }
        if (!settings.isPackageAllowed(sbn.packageName)) {
            skipNotification(sbn, "app_filter", snapshot)
            return
        }
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            skipNotification(sbn, "ongoing_event", snapshot)
            return
        }
        if (snapshot.isLocalOnly) {
            skipNotification(sbn, "local_only", snapshot)
            return
        }
        if (settings.skipSilentNotifications && snapshot.isSilentLowImportance) {
            skipNotification(sbn, "silent_low_importance", snapshot)
            return
        }
        if (snapshot.isInvisibleSystemNotification) {
            skipNotification(sbn, "invisible_system_notification", snapshot)
            return
        }
        if (snapshot.isCorePlatformNotification) {
            skipNotification(sbn, "skipped_system_notification", snapshot)
            return
        }
        if (snapshot.isUpcomingAlarmNotice) {
            skipNotification(sbn, "upcoming_alarm_notice", snapshot)
            return
        }
        // Remember contents before environmental suppression so an unchanged repost
        // cannot wake the watch after DND or the unlock state changes.
        val (skipDuplicate, duplicateReason) = deduplicator.shouldSkip(sbn, now)
        if (skipDuplicate) {
            if (duplicateReason == "same_key_unchanged" && pending.containsKey(sbn.key)) {
                // Keep one delayed delivery alive while an app rapidly republishes the
                // same notification key during the verification window. Content is
                // provably unchanged here, so the merged candidate stays stale-eligible.
                queueForVerification(sbn, snapshot, staleEligible = true)
            } else {
                // Age only relabels an already-unchanged duplicate for the event history
                // (a burst of identical reposts catching up after a reconnect); it never
                // relabels "new" (a messenger editing the same key with fresh content —
                // e.g. Telegram re-sends later messages without a title — which must
                // never be treated as stale regardless of delivery delay).
                val reason = if (duplicateReason == "same_key_unchanged" && snapshot.postAgeMs > STALE_NOTIFICATION_MS) {
                    "stale_notification_repeat ageMs=${snapshot.postAgeMs}"
                } else {
                    duplicateReason
                }
                skipNotification(sbn, reason, snapshot)
            }
            return
        }
        if (settings.skipWhenPhoneUnlocked && phoneState.isUnlockedInUse) {
            skipNotification(sbn, "phone_unlocked", snapshot)
            return
        }

        queueForVerification(sbn, snapshot, staleEligible = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        pending.remove(sbn.key)?.let { candidate ->
            mainHandler.removeCallbacks(candidate.runnable)
            EventHistoryStore.add(
                this,
                "NOTIFICATION",
                "SKIPPED",
                "removed_before_forward | ${candidate.snapshot.compactDetail}",
            )
        }
        AlarmBridgeController.remove(this, sbn.key)?.let { alarm ->
            val envelope = MessageEnvelope(
                type = "ALARM_CLOSE",
                eventId = alarm.eventId,
                payload = JSONObject().put("alarmEventId", alarm.eventId),
            )
            WearTransport.sendPreferred(this, Protocol.ALARM_CLOSE, envelope) { result ->
                EventHistoryStore.add(this, "ALARM_CLOSE", if (result.success) "SENT" else "FAILED", result.detail)
            }
        }
        deduplicator.onRemoved(sbn.key)
        EventHistoryStore.add(this, "NOTIFICATION_REMOVED", "OK", "${sbn.packageName}:${sbn.id}")
    }

    private fun queueForVerification(
        sbn: StatusBarNotification,
        snapshot: NotificationSnapshot,
        staleEligible: Boolean,
    ) {
        pending.remove(sbn.key)?.let { mainHandler.removeCallbacks(it.runnable) }
        lateinit var candidate: PendingForward
        val runnable = Runnable {
            if (!pending.remove(sbn.key, candidate)) return@Runnable
            verifyAndForward(candidate)
        }
        candidate = PendingForward(
            key = sbn.key,
            expectedPostTime = sbn.postTime,
            contentHash = deduplicator.contentFingerprint(sbn),
            snapshot = snapshot,
            staleEligible = staleEligible,
            runnable = runnable,
        )
        pending[sbn.key] = candidate
        mainHandler.postDelayed(runnable, VERIFY_DELAY_MS)
    }

    private fun verifyAndForward(candidate: PendingForward) {
        val active = runCatching {
            activeNotifications.orEmpty().firstOrNull { it.key == candidate.key }
        }.getOrNull()
        if (active == null) {
            EventHistoryStore.add(
                this,
                "NOTIFICATION",
                "SKIPPED",
                "removed_before_forward | ${candidate.snapshot.compactDetail}",
            )
            return
        }
        if (active.postTime != candidate.expectedPostTime) {
            EventHistoryStore.add(
                this,
                "NOTIFICATION",
                "SKIPPED",
                "superseded_before_forward expectedPost=${candidate.expectedPostTime} activePost=${active.postTime} | ${candidate.snapshot.compactDetail}",
            )
            return
        }

        val now = System.currentTimeMillis()
        val settings = PhoneSettingsStore.load(this)
        val phoneState = PhoneInteraction.read(this)
        val snapshot = inspectNotification(active, phoneState, settings, now)

        if (settings.appPaused) {
            // Re-checked here: pause may have been switched on during the 400 ms verification
            // window after the notification was already queued in onNotificationPosted().
            skipNotification(active, "app_paused", snapshot)
            return
        }
        if (!settings.screenWake || !settings.isPackageAllowed(active.packageName)) {
            skipNotification(active, if (!settings.screenWake) "screen_wake_disabled" else "app_filter", snapshot)
            return
        }
        if (snapshot.isLocalOnly) {
            skipNotification(active, "local_only", snapshot)
            return
        }
        if (settings.skipSilentNotifications && snapshot.isSilentLowImportance) {
            skipNotification(active, "silent_low_importance", snapshot)
            return
        }
        if (snapshot.isInvisibleSystemNotification) {
            skipNotification(active, "invisible_system_notification", snapshot)
            return
        }
        if (snapshot.isCorePlatformNotification) {
            skipNotification(active, "skipped_system_notification", snapshot)
            return
        }
        if (snapshot.isUpcomingAlarmNotice) {
            skipNotification(active, "upcoming_alarm_notice", snapshot)
            return
        }
        if (candidate.staleEligible && snapshot.postAgeMs > STALE_NOTIFICATION_MS) {
            skipNotification(active, "stale_notification_repeat ageMs=${snapshot.postAgeMs}", snapshot)
            return
        }
        if (settings.skipWhenPhoneUnlocked && phoneState.isUnlockedInUse) {
            skipNotification(active, "phone_unlocked", snapshot)
            return
        }

        val rateDecision = wakeRateLimiter.decideAndMark(
            packageName = active.packageName,
            contentHash = candidate.contentHash,
            now = now,
        )
        if (rateDecision.skip) {
            skipNotification(active, rateDecision.reason, snapshot)
            return
        }

        forwardNotification(active, snapshot, settings)
    }

    private fun forwardNotification(
        sbn: StatusBarNotification,
        snapshot: NotificationSnapshot,
        settings: PhoneSettings,
    ) {
        val triggerDetail = snapshot.compactDetail
        EventHistoryStore.add(this, "NOTIFICATION", "FORWARDED", triggerDetail)

        val payload = JSONObject()
            .put("packageName", sbn.packageName)
            .put("notificationKey", sbn.key)
            .put("notificationId", sbn.id)
            .put("groupKey", sbn.groupKey)
            .put("postTime", sbn.postTime)
            .put("phoneDndBlocksWake", isPhoneDndBlocking() && !settings.wakeScreenOnPhoneDnd)
            .put("respectWatchDnd", settings.respectWatchDnd)
            .put("skipWakeOffWrist", settings.skipWakeOffWrist)
            .put("skipSoundOffWrist", settings.skipSoundOffWrist)
            .put("soundMode", settings.soundMode.wireValue)
        val envelope = MessageEnvelope(type = "WAKE", payload = payload)
        WearTransport.sendPreferred(this, Protocol.WAKE, envelope) { result ->
            EventHistoryStore.add(
                this,
                "WAKE_SEND",
                if (result.success) "SENT" else "FAILED",
                "$triggerDetail | transport=${result.detail}",
            )
        }
    }

    private fun relayAlarmIfHandled(sbn: StatusBarNotification): Boolean {
        val registration = AlarmBridgeController.register(this, sbn)
        if (!registration.handled) return false
        val alarm = registration.changedSession
        if (alarm == null) {
            EventHistoryStore.add(this, "ALARM_START", "SKIPPED", registration.detail)
            return true
        }
        val envelope = MessageEnvelope(type = "ALARM_START", eventId = alarm.eventId, payload = alarm.payload())
        WearTransport.sendPreferred(this, Protocol.ALARM_START, envelope) { result ->
            val detail = if (registration.detail.isNotEmpty()) {
                "${registration.detail} | ${result.detail}"
            } else {
                result.detail
            }
            EventHistoryStore.add(this, "ALARM_START", if (result.success) "SENT" else "FAILED", detail)
        }
        return true
    }

    private fun isPhoneDndBlocking(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun skipNotification(
        sbn: StatusBarNotification,
        reason: String,
        snapshot: NotificationSnapshot,
    ) {
        val detail = "$reason | ${snapshot.compactDetail}"
        if (reason == "ongoing_event") {
            EventHistoryStore.addAggregated(
                context = this,
                type = "NOTIFICATION",
                result = "SKIPPED",
                detail = detail,
                aggregationKey = "ongoing_event:${snapshot.packageName}:${snapshot.channelId.orEmpty()}",
            )
        } else {
            EventHistoryStore.add(this, "NOTIFICATION", "SKIPPED", detail)
        }
    }

    private fun inspectNotification(
        sbn: StatusBarNotification,
        phoneState: PhoneInteractionState,
        settings: PhoneSettings,
        observedAt: Long = System.currentTimeMillis(),
    ): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val hasTitle = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_CONVERSATION_TITLE,
        ).any { extras.getCharSequence(it).hasText() }
        val hasText = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_VERIFICATION_TEXT,
        ).any { extras.getCharSequence(it).hasText() } ||
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().any { it.hasText() } ||
            !extras.getParcelableArray(Notification.EXTRA_MESSAGES).isNullOrEmpty()
        val actionCount = notification.actions?.size ?: 0
        val hasUserAction = actionCount > 0 ||
            notification.contentIntent != null ||
            notification.fullScreenIntent != null ||
            notification.deleteIntent != null
        val systemPackage = isSystemPackage(sbn.packageName)
        val importance = runCatching {
            val ranking = Ranking()
            if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else null
        }.getOrNull()

        return NotificationSnapshot(
            packageName = sbn.packageName,
            notificationId = sbn.id,
            postTime = sbn.postTime,
            observedAt = observedAt,
            listenerAgeMs = listenerAgeMs(),
            listenerSessionId = listenerSessionId,
            channelId = notification.channelId,
            notificationKey = sbn.key,
            tag = sbn.tag,
            groupKey = sbn.groupKey,
            category = notification.category,
            importance = importance,
            flags = notification.flags,
            hasTitle = hasTitle,
            hasText = hasText,
            actionCount = actionCount,
            hasUserAction = hasUserAction,
            hasFullScreenIntent = notification.fullScreenIntent != null,
            isClearable = sbn.isClearable,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isSystemPackage = systemPackage,
            filterMode = settings.appFilterMode.name,
            filterAllowed = settings.isPackageAllowed(sbn.packageName),
            phoneState = phoneState,
        )
    }

    private fun cancelPending() {
        pending.values.forEach { mainHandler.removeCallbacks(it.runnable) }
        pending.clear()
    }

    private fun listenerAgeMs(): Long = if (listenerConnectedElapsed == 0L) {
        -1L
    } else {
        (SystemClock.elapsedRealtime() - listenerConnectedElapsed).coerceAtLeast(0L)
    }

    @Suppress("DEPRECATION")
    private fun isSystemPackage(packageName: String): Boolean = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        (info.flags and systemFlags) != 0
    }.getOrDefault(false)

    private fun CharSequence?.hasText(): Boolean = !this.isNullOrBlank()

    private data class PendingForward(
        val key: String,
        val expectedPostTime: Long,
        val contentHash: String,
        val snapshot: NotificationSnapshot,
        val staleEligible: Boolean,
        val runnable: Runnable,
    )

    private data class NotificationSnapshot(
        val packageName: String,
        val notificationId: Int,
        val postTime: Long,
        val observedAt: Long,
        val listenerAgeMs: Long,
        val listenerSessionId: String,
        val channelId: String?,
        val notificationKey: String,
        val tag: String?,
        val groupKey: String?,
        val category: String?,
        val importance: Int?,
        val flags: Int,
        val hasTitle: Boolean,
        val hasText: Boolean,
        val actionCount: Int,
        val hasUserAction: Boolean,
        val hasFullScreenIntent: Boolean,
        val isClearable: Boolean,
        val isGroupSummary: Boolean,
        val isSystemPackage: Boolean,
        val filterMode: String,
        val filterAllowed: Boolean,
        val phoneState: PhoneInteractionState,
    ) {
        val postAgeMs: Long
            get() = (observedAt - postTime).coerceAtLeast(0L)

        val isInvisibleSystemNotification: Boolean
            get() = isSystemPackage && !hasTitle && !hasText && !hasUserAction

        // Exact package match only — never the broad isSystemPackage/FLAG_SYSTEM check, which
        // also covers OEM apps like com.oplus.battery that must keep waking the watch.
        val isCorePlatformNotification: Boolean
            get() = packageName == "android" || packageName == "com.android.systemui"

        // OnePlus/OPPO clock's "upcoming alarm" reminder (posted well before the alarm actually
        // rings, e.g. "Alarm in 5 minutes"). Collapsed, non-actionable — the user sees the watch
        // face, not a notification — so it must not wake the screen. Distinct from the alarm
        // actually ringing (fullScreen=1, category=CATEGORY_ALARM), which goes through Alarm
        // Bridge instead of this relay entirely and is untouched by this check.
        val isUpcomingAlarmNotice: Boolean
            get() = channelId == "com.oplus.alarmclock.next.alarm"

        val isLocalOnly: Boolean
            get() = (flags and Notification.FLAG_LOCAL_ONLY) != 0

        val channelLooksSilent: Boolean
            get() = channelId?.contains("silent", ignoreCase = true) == true

        val isSilentLowImportance: Boolean
            get() = ((importance ?: NotificationManager.IMPORTANCE_DEFAULT) <= NotificationManager.IMPORTANCE_LOW || channelLooksSilent) &&
                !hasFullScreenIntent && category != Notification.CATEGORY_CALL && category != Notification.CATEGORY_ALARM

        val compactDetail: String
            get() = buildString {
                append(packageName).append(':').append(notificationId)
                append(":post=").append(postTime)
                append(" postAgeMs=").append(postAgeMs)
                append(" listenerAgeMs=").append(listenerAgeMs)
                append(" session=").append(listenerSessionId)
                append(" | phone=").append(phoneState.label)
                append(" system=").append(isSystemPackage.asBit())
                append(" key=").append(notificationKey)
                append(" tag=").append(tag.orEmpty().ifBlank { "none" })
                append(" groupKey=").append(groupKey.orEmpty().ifBlank { "none" })
                append(" channel=").append(channelId.orEmpty().ifBlank { "none" })
                append(" category=").append(category.orEmpty().ifBlank { "none" })
                append(" importance=").append(importance ?: -1)
                append(" flags=0x").append(flags.toUInt().toString(16))
                append(" title=").append(hasTitle.asBit())
                append(" text=").append(hasText.asBit())
                append(" actions=").append(actionCount)
                append(" intent=").append(hasUserAction.asBit())
                append(" fullScreen=").append(hasFullScreenIntent.asBit())
                append(" clearable=").append(isClearable.asBit())
                append(" summary=").append(isGroupSummary.asBit())
                append(" localOnly=").append(isLocalOnly.asBit())
                append(" silentHint=").append(channelLooksSilent.asBit())
                append(" filter=").append(if (filterAllowed) "allowed" else "blocked")
                append(" filterMode=").append(filterMode)
            }

        private fun Boolean.asBit(): Int = if (this) 1 else 0

    }

    private companion object {
        const val VERIFY_DELAY_MS = 400L
        const val STALE_NOTIFICATION_MS = 5_000L
    }
}
