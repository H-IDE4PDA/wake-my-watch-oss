package com.h_ide4pda.wakemywatch.phone.ringer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivers the ringer-schedule boundary alarm, and re-arms the schedule after events that clear
 * pending alarms or move the clock: reboot, app update, manual time / timezone change.
 */
class RingerScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            RingerScheduleController.ACTION_APPLY -> "alarm"
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "pkg_replaced"
            Intent.ACTION_TIME_CHANGED -> "time_set"
            Intent.ACTION_TIMEZONE_CHANGED -> "tz_changed"
            else -> "unknown"
        }
        val pending = goAsync()
        try {
            RingerScheduleController.sync(context, trigger)
        } finally {
            pending.finish()
        }
    }
}
