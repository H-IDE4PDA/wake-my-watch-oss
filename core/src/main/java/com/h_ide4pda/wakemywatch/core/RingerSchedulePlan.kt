package com.h_ide4pda.wakemywatch.core

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry in the ringer-mode schedule. While *any* enabled plan covers the current instant the
 * phone is held on "vibrate"; the rest of the time on "sound". Plans never touch "silent".
 *
 * [days] is a 7-bit mask, bit 0 = Monday … bit 6 = Sunday. [startMinutes]/[endMinutes] are
 * minutes since local midnight (0..1439). start > end means the quiet window crosses midnight —
 * it then also covers [0, end) of the day *after* an enabled start day (see [RingerScheduleEval]).
 */
data class RingerSchedulePlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val startMinutes: Int = 22 * 60,
    val endMinutes: Int = 7 * 60,
    val days: Int = ALL_DAYS,
    val enabled: Boolean = true,
) {
    fun dayEnabled(dayMon0: Int): Boolean = (days shr dayMon0) and 1 == 1

    fun withDay(dayMon0: Int, on: Boolean): RingerSchedulePlan =
        copy(days = if (on) days or (1 shl dayMon0) else days and (1 shl dayMon0).inv())

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("startMinutes", startMinutes)
        .put("endMinutes", endMinutes)
        .put("days", days)
        .put("enabled", enabled)

    companion object {
        const val ALL_DAYS = 0b1111111

        fun fromJson(json: JSONObject): RingerSchedulePlan = RingerSchedulePlan(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            startMinutes = json.optInt("startMinutes", 22 * 60).coerceIn(0, 1439),
            endMinutes = json.optInt("endMinutes", 7 * 60).coerceIn(0, 1439),
            days = json.optInt("days", ALL_DAYS) and ALL_DAYS,
            enabled = json.optBoolean("enabled", true),
        )

        fun listToJson(plans: List<RingerSchedulePlan>): JSONArray =
            JSONArray().apply { plans.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray?): List<RingerSchedulePlan> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(fromJson(it)) }
                }
            }
        }
    }
}

/** Pure evaluation of a plan list against a wall-clock instant. No Android dependencies. */
object RingerScheduleEval {

    /** True when [plan] (must be enabled) covers day [dayMon0] (0=Mon..6=Sun) at [minuteOfDay]. */
    fun covers(plan: RingerSchedulePlan, dayMon0: Int, minuteOfDay: Int): Boolean {
        if (!plan.enabled) return false
        val s = plan.startMinutes
        val e = plan.endMinutes
        if (s == e) return false
        return if (s < e) {
            plan.dayEnabled(dayMon0) && minuteOfDay in s until e
        } else {
            // Crosses midnight: [s, 1440) on the start day, plus [0, e) on the following day.
            (plan.dayEnabled(dayMon0) && minuteOfDay >= s) ||
                (plan.dayEnabled((dayMon0 + 6) % 7) && minuteOfDay < e)
        }
    }

    /** Plans (of [plans]) covering the given instant — empty means "sound", non-empty "vibrate". */
    fun matching(plans: List<RingerSchedulePlan>, dayMon0: Int, minuteOfDay: Int): List<RingerSchedulePlan> =
        plans.filter { it.enabled && covers(it, dayMon0, minuteOfDay) }

    fun anyEnabled(plans: List<RingerSchedulePlan>): Boolean = plans.any { it.enabled && it.days != 0 && it.startMinutes != it.endMinutes }
}
