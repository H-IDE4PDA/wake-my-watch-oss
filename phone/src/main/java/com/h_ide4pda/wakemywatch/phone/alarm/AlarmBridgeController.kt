package com.h_ide4pda.wakemywatch.phone.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.h_ide4pda.wakemywatch.core.Protocol
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AlarmBridgeController {
    private const val TAG = "AlarmBridgeController"
    const val GOOGLE_CLOCK_PACKAGE = "com.google.android.deskclock"

    enum class Action { SNOOZE, DISMISS }

    data class Session(
        val eventId: String,
        val notificationKey: String,
        val notificationId: Int,
        val postedAt: Long,
        val title: String,
        val text: String,
        val canSnooze: Boolean,
        val canDismiss: Boolean,
    ) {
        fun payload(): JSONObject = JSONObject()
            .put("alarmEventId", eventId)
            .put("notificationKey", notificationKey)
            .put("notificationId", notificationId)
            .put("sourcePackage", GOOGLE_CLOCK_PACKAGE)
            .put("postedAt", postedAt)
            .put("title", title)
            .put("text", text)
            .put("canSnooze", canSnooze)
            .put("canDismiss", canDismiss)
    }

    /** handled=true prevents Google Clock's firing notification from becoming a normal wake event. */
    data class Registration(
        val handled: Boolean,
        val changedSession: Session? = null,
        val detail: String = "",
    )

    private data class Registered(
        val session: Session,
        val snooze: PendingIntent?,
        val dismiss: PendingIntent?,
    )

    private data class ExtractedActions(
        val snooze: PendingIntent?,
        val dismiss: PendingIntent?,
        val detail: String = "",
    )

    private data class ActionInfo(
        val index: Int,
        val originalTitle: String,
        val normalizedTitle: String,
        val semanticAction: Int,
        val hasIntent: Boolean,
        val intent: PendingIntent?,
        val classified: Action?,
    )

    private const val PREFS = "wmw_alarm_bridge"
    private const val PREFIX_EVENT = "event:"
    private const val TIMER_GROUP_KEY_MARKER = "c:Firing"
    private val byEvent = ConcurrentHashMap<String, Registered>()
    private val eventByNotification = ConcurrentHashMap<String, String>()
    @Volatile private var listener: NotificationListenerService? = null

    fun attach(service: NotificationListenerService) {
        listener = service
    }

    fun detach(service: NotificationListenerService) {
        if (listener === service) listener = null
    }

    fun register(context: Context, sbn: StatusBarNotification): Registration {
        if (!isPotentialGoogleClockFiring(sbn)) {
            if (sbn.packageName == GOOGLE_CLOCK_PACKAGE && isFiringTimerGroupKey(sbn.groupKey)) {
                Log.i(TAG, "rejected_timer_groupkey groupKey=${sbn.groupKey.orDash()}")
            }
            return Registration(handled = false)
        }

        // Temporary diagnostics (see CLAUDE.md roadmap: timer-vs-alarm channel investigation).
        // isPotentialGoogleClockFiring() currently can't tell a firing timer from a firing alarm
        // apart, so we log the fields most likely to distinguish them for later analysis; this
        // does not change which notifications are treated as alarms.
        val notificationDiagnostic = notificationDiagnostics(sbn)

        val actions = extractActions(sbn)
        // Google Clock alarms expose both Snooze and Dismiss. Its timers use another
        // action set, so requiring both avoids presenting a timer as an alarm.
        if (actions.snooze == null || actions.dismiss == null) {
            return Registration(handled = true, detail = "${actions.detail} | $notificationDiagnostic")
        }

        val eventId = eventByNotification[sbn.key]
            ?: findPersistedEventForKey(context, sbn.key)
            ?: Protocol.eventId()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            .ifBlank { "Google Clock" }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val session = Session(
            eventId = eventId,
            notificationKey = sbn.key,
            notificationId = sbn.id,
            postedAt = sbn.postTime,
            title = title,
            text = text,
            canSnooze = true,
            canDismiss = true,
        )
        val previous = byEvent[eventId]
        val registered = Registered(session, actions.snooze, actions.dismiss)
        byEvent[eventId] = registered
        eventByNotification[sbn.key] = eventId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREFIX_EVENT + eventId, sbn.key)
            .apply()

        val baseDetail = if (previous?.session == session) "alarm_unchanged" else "alarm_registered"
        return Registration(
            handled = true,
            changedSession = session.takeIf { previous?.session != session },
            detail = buildString {
                append(baseDetail)
                if (actions.detail.isNotEmpty()) append(" | ").append(actions.detail)
                append(" | ").append(notificationDiagnostic)
            },
        )
    }

    fun remove(context: Context, notificationKey: String): Session? {
        val eventId = eventByNotification.remove(notificationKey)
            ?: findPersistedEventForKey(context, notificationKey)
            ?: return null
        val registered = byEvent.remove(eventId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREFIX_EVENT + eventId)
            .apply()
        return registered?.session ?: Session(
            eventId = eventId,
            notificationKey = notificationKey,
            notificationId = 0,
            postedAt = 0L,
            title = "",
            text = "",
            canSnooze = false,
            canDismiss = false,
        )
    }

    fun perform(context: Context, eventId: String, action: Action): Result<Unit> = runCatching {
        val registered = byEvent[eventId] ?: rebuild(context, eventId)
            ?: error("Active Google Clock alarm was not found")
        val pendingIntent = when (action) {
            Action.SNOOZE -> registered.snooze
            Action.DISMISS -> registered.dismiss
        } ?: error("Requested alarm action is unavailable")
        pendingIntent.send()
    }

    private fun rebuild(context: Context, eventId: String): Registered? {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_EVENT + eventId, null) ?: return null
        val sbn = listener?.activeNotifications?.firstOrNull { it.key == key } ?: return null
        if (!isPotentialGoogleClockFiring(sbn)) return null
        val actions = extractActions(sbn)
        if (actions.snooze == null || actions.dismiss == null) return null
        val extras = sbn.notification.extras
        val session = Session(
            eventId = eventId,
            notificationKey = key,
            notificationId = sbn.id,
            postedAt = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { "Google Clock" },
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            canSnooze = true,
            canDismiss = true,
        )
        return Registered(session, actions.snooze, actions.dismiss).also {
            byEvent[eventId] = it
            eventByNotification[key] = eventId
        }
    }

    private fun isPotentialGoogleClockFiring(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != GOOGLE_CLOCK_PACKAGE) return false
        val notification = sbn.notification
        val looksLikeFiringAlarm = notification.category == Notification.CATEGORY_ALARM &&
            notification.fullScreenIntent != null
        // Block-list, not allow-list: a firing timer also matches category=alarm +
        // fullScreenIntent, same as a firing alarm. Its groupKey reliably contains "c:Firing" on
        // both RU and DE system language (confirmed on-device); a firing alarm's groupKey never
        // does. On any doubt (unrecognized groupKey shape) a real alarm must still get through.
        return looksLikeFiringAlarm && !isFiringTimerGroupKey(sbn.groupKey)
    }

    private fun isFiringTimerGroupKey(groupKey: String?): Boolean =
        groupKey != null && groupKey.contains(TIMER_GROUP_KEY_MARKER)

    private fun notificationDiagnostics(sbn: StatusBarNotification): String {
        val notification = sbn.notification
        return "channel=${notification.channelId.orDash()} category=${notification.category.orDash()} " +
            "groupKey=${sbn.groupKey.orDash()} tag=${sbn.tag.orDash()}"
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "none"

    private fun extractActions(sbn: StatusBarNotification): ExtractedActions {
        val actionInfos = sbn.notification.actions.orEmpty().mapIndexed { index, action ->
            action.describe(index)
        }
        var snooze: PendingIntent? = null
        var dismiss: PendingIntent? = null
        actionInfos.forEach { action ->
            when (action.classified) {
                Action.SNOOZE -> if (snooze == null) snooze = action.intent
                Action.DISMISS -> if (dismiss == null) dismiss = action.intent
                null -> Unit
            }
        }
        if (snooze != null && dismiss != null) {
            return ExtractedActions(snooze, dismiss)
        }

        val recognized = actionInfos.filter { it.classified != null }
        if (canUseGoogleClockOrderFallback(sbn, actionInfos)) {
            if (recognized.size == 1) {
                val known = recognized.single()
                val other = actionInfos.first { it.index != known.index }
                return when (known.classified) {
                    Action.SNOOZE -> ExtractedActions(known.intent, other.intent)
                    Action.DISMISS -> ExtractedActions(other.intent, known.intent)
                    null -> ExtractedActions(snooze, dismiss, unsupportedAlarmActionsDetail(sbn.packageName, actionInfos))
                }
            }
            if (recognized.isEmpty()) {
                // Neither semantic action nor keywords matched either button (fully unknown
                // system language). We still assume Google Clock always adds Snooze before
                // Dismiss, but since this is a guess rather than a confirmed match, log it so a
                // wrong assumption is visible in diagnostics instead of silently mis-mapping.
                return ExtractedActions(
                    snooze = actionInfos[0].intent,
                    dismiss = actionInfos[1].intent,
                    detail = orderFallbackDetail(sbn.packageName, actionInfos),
                )
            }
        }

        return ExtractedActions(
            snooze = snooze,
            dismiss = dismiss,
            detail = unsupportedAlarmActionsDetail(sbn.packageName, actionInfos),
        )
    }

    private fun Notification.Action.describe(index: Int): ActionInfo {
        val originalTitle = title?.toString().orEmpty()
        val normalizedTitle = originalTitle.lowercase(Locale.ROOT)
        return ActionInfo(
            index = index,
            originalTitle = originalTitle,
            normalizedTitle = normalizedTitle,
            semanticAction = semanticAction,
            hasIntent = actionIntent != null,
            intent = actionIntent,
            classified = classify(this, normalizedTitle),
        )
    }

    private fun classify(action: Notification.Action, normalizedTitle: String): Action? {
        if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_DELETE) {
            return Action.DISMISS
        }
        if (normalizedTitle.containsAny("snooze", "отлож", "drzem", "pospon", "później")) return Action.SNOOZE
        if (normalizedTitle.containsAny("dismiss", "stop", "cancel", "останов", "выключ", "откл", "zatrzymaj", "wyłącz", "odrzuć", "anuluj")) {
            return Action.DISMISS
        }
        return null
    }

    private fun canUseGoogleClockOrderFallback(sbn: StatusBarNotification, actions: List<ActionInfo>): Boolean {
        if (sbn.packageName != GOOGLE_CLOCK_PACKAGE) return false
        val notification = sbn.notification
        val alarmLike = notification.category == Notification.CATEGORY_ALARM ||
            notification.fullScreenIntent != null
        return alarmLike && actions.size == 2 && actions.all { it.hasIntent }
    }

    private fun unsupportedAlarmActionsDetail(packageName: String, actions: List<ActionInfo>): String =
        buildString {
            append("unsupported_alarm_actions package=").append(packageName)
            append(" actions=").append(actions.joinToString(prefix = "[", postfix = "]") {
                "${it.index}:\"${it.originalTitle.diagnosticSafe()}\""
            })
            append(" semantics=").append(actions.joinToString(prefix = "[", postfix = "]") {
                "${it.index}:${it.semanticAction}"
            })
            append(" hasIntent=").append(actions.joinToString(prefix = "[", postfix = "]") {
                "${it.index}:${it.hasIntent}"
            })
        }

    private fun orderFallbackDetail(packageName: String, actions: List<ActionInfo>): String =
        buildString {
            append("alarm_actions_order_fallback_used package=").append(packageName)
            append(" assumed=[0:SNOOZE,1:DISMISS]")
            append(" actions=").append(actions.joinToString(prefix = "[", postfix = "]") {
                "${it.index}:\"${it.originalTitle.diagnosticSafe()}\""
            })
        }

    private fun String.diagnosticSafe(): String =
        replace('\\', '/')
            .replace('"', '\'')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(80)

    private fun String.containsAny(vararg needles: String): Boolean = needles.any(::contains)

    private fun findPersistedEventForKey(context: Context, key: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.entries
            .firstOrNull { it.key.startsWith(PREFIX_EVENT) && it.value == key }
            ?.key
            ?.removePrefix(PREFIX_EVENT)
}
