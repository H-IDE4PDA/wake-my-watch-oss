package com.h_ide4pda.wakemywatch.phone.ringer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import com.h_ide4pda.wakemywatch.core.AppSettingsStore
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import com.h_ide4pda.wakemywatch.core.RingerSchedulePlan
import com.h_ide4pda.wakemywatch.core.RingerScheduleEval
import com.h_ide4pda.wakemywatch.core.RingerSync
import java.util.Calendar

/**
 * Time-of-day schedule that flips the PHONE between "sound" (NORMAL) and "vibrate" (VIBRATE).
 * The watch follows through the ordinary two-way ringer sync ([RingerSyncBridge]).
 *
 *  - A list of plans (name / start / end / weekdays / on-off). While ANY enabled plan covers the
 *    current instant the phone is held on VIBRATE; the rest of the time NORMAL. Overlapping plans
 *    are fine — the effect is the union of their quiet windows.
 *  - Windows that cross midnight belong to their start day: a 23:00→07:00 Mon-Fri plan is quiet
 *    from Monday 23:00 through Tuesday 07:00, … Friday 23:00 through Saturday 07:00. Sunday and
 *    Monday mornings are not quiet unless Sun is also ticked.
 *  - One exact alarm at a time, set to the nearest plan edge across all enabled plans. On fire:
 *    re-evaluate the union, apply, re-arm. Between edges the schedule does nothing, so a manual
 *    profile change mid-window is left alone until the next edge.
 *  - "Silent" is never set and never overridden.
 */
object RingerScheduleController {
    const val ACTION_APPLY = "com.h_ide4pda.wakemywatch.action.RINGER_SCHEDULE_APPLY"
    private const val REQUEST_CODE = 47_615
    private const val SEARCH_DAYS = 14

    /** Single entry point — safe to call on toggle, plan edit, boot, app open, or the alarm. */
    fun sync(context: Context, trigger: String) {
        val app = context.applicationContext
        val settings = AppSettingsStore.load(app)
        val plans = settings.ringerSchedulePlans
        val alarmManager = app.getSystemService(AlarmManager::class.java)

        if (!RingerScheduleEval.anyEnabled(plans) || settings.isFullyPaused) {
            cancel(app, alarmManager)
            EventHistoryStore.add(
                app,
                "RINGER_SCHEDULE",
                "DISABLED",
                "trigger=$trigger plans=${plans.size} enabled=${plans.count { it.enabled }} paused=${settings.isFullyPaused}",
            )
            return
        }

        applyForNow(app, plans, trigger)
        scheduleNextEdge(app, plans, alarmManager, trigger)
    }

    private fun applyForNow(context: Context, plans: List<RingerSchedulePlan>, trigger: String) {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dayMon0 = mondayZero(cal)
        val matching = RingerScheduleEval.matching(plans, dayMon0, nowMinutes)
        val target = if (matching.isNotEmpty()) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        val planLabel = matching.joinToString(",") { it.name.ifBlank { "#${it.id.take(4)}" } }.ifBlank { "none" }

        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val current = runCatching { audioManager.ringerMode }.getOrDefault(-1)

        if (current == AudioManager.RINGER_MODE_SILENT) {
            EventHistoryStore.add(
                context,
                "RINGER_SCHEDULE",
                "SKIPPED",
                "phone_silent trigger=$trigger time=${hhmm(nowMinutes)} wanted=${RingerSync.name(target)} plans=[$planLabel]",
            )
            return
        }
        if (current == target) {
            EventHistoryStore.add(
                context,
                "RINGER_SCHEDULE",
                "SKIPPED",
                "already_applied trigger=$trigger time=${hhmm(nowMinutes)} mode=${RingerSync.name(target)} plans=[$planLabel]",
            )
            return
        }
        // No echo marker: the change SHOULD propagate to the watch through the ringer sync.
        runCatching { audioManager.ringerMode = target }
        EventHistoryStore.add(
            context,
            "RINGER_SCHEDULE",
            "APPLIED",
            "trigger=$trigger time=${hhmm(nowMinutes)} day=${dayName(dayMon0)} mode=${RingerSync.name(target)} previous=${RingerSync.name(current)} plans=[$planLabel]",
        )
    }

    private fun scheduleNextEdge(
        context: Context,
        plans: List<RingerSchedulePlan>,
        alarmManager: AlarmManager,
        trigger: String,
    ) {
        val nextAt = nextEdgeMillis(plans)
        if (nextAt == null) {
            cancel(context, alarmManager)
            EventHistoryStore.add(context, "RINGER_SCHEDULE", "NOT_SCHEDULED", "no_edge trigger=$trigger")
            return
        }
        val pendingIntent = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent)
            }
        }.onFailure {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent)
        }
        val edgeCal = Calendar.getInstance().apply { timeInMillis = nextAt }
        EventHistoryStore.add(
            context,
            "RINGER_SCHEDULE",
            "SCHEDULED",
            "trigger=$trigger next=${dayName(mondayZero(edgeCal))} ${hhmm(edgeCal.get(Calendar.HOUR_OF_DAY) * 60 + edgeCal.get(Calendar.MINUTE))} inMs=${nextAt - System.currentTimeMillis()} exact=$exact",
        )
    }

    /** Earliest future edge (a plan start, or a plan end on the right day) across all enabled plans. */
    private fun nextEdgeMillis(plans: List<RingerSchedulePlan>): Long? {
        val now = System.currentTimeMillis()
        var best: Long? = null
        for (plan in plans) {
            if (!plan.enabled || plan.days == 0 || plan.startMinutes == plan.endMinutes) continue
            val wraps = plan.startMinutes > plan.endMinutes
            for (dayMon0 in 0..6) {
                if (!plan.dayEnabled(dayMon0)) continue
                // start edge — on this enabled day
                nextOccurrence(dayMon0, plan.startMinutes, now)?.let { if (best == null || it < best!!) best = it }
                // end edge — same day if the window doesn't wrap, next day if it does
                val endDay = if (wraps) (dayMon0 + 1) % 7 else dayMon0
                nextOccurrence(endDay, plan.endMinutes, now)?.let { if (best == null || it < best!!) best = it }
            }
        }
        return best
    }

    /** Next wall-clock millis, within [SEARCH_DAYS], that is weekday [dayMon0] at [minuteOfDay], after [after]. */
    private fun nextOccurrence(dayMon0: Int, minuteOfDay: Int, after: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(SEARCH_DAYS) {
            if (cal.timeInMillis > after && mondayZero(cal) == dayMon0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun cancel(context: Context, alarmManager: AlarmManager) {
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, RingerScheduleReceiver::class.java).setAction(ACTION_APPLY)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context.applicationContext, REQUEST_CODE, intent, flags)
    }

    /** 0 = Monday … 6 = Sunday. */
    private fun mondayZero(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun dayName(dayMon0: Int): String =
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").getOrElse(dayMon0) { "?" }

    fun hhmm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
}
