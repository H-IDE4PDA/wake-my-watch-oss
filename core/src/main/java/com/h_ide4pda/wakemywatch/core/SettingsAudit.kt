package com.h_ide4pda.wakemywatch.core

import android.content.Context

/** Records human-readable settings changes and complete snapshots for field diagnostics. */
object SettingsAudit {
    fun recordChange(
        context: Context,
        source: String,
        previous: AppSettings,
        current: AppSettings,
    ) {
        changes(previous, current).forEach { detail ->
            EventHistoryStore.add(context, "SETTINGS_CHANGE", source, detail)
        }
        EventHistoryStore.add(context, "SETTINGS_SNAPSHOT", source, snapshot(current))
    }

    fun snapshot(settings: AppSettings): String = buildString {
        append("revision=").append(settings.revision)
        append(" updatedAt=").append(settings.updatedAt)
        append(" screenWake=").append(settings.screenWake.bit())
        append(" skipWhenPhoneUnlocked=").append(settings.skipWhenPhoneUnlocked.bit())
        append(" skipSilentNotifications=").append(settings.skipSilentNotifications.bit())
        append(" respectPhoneDnd=").append(settings.respectPhoneDnd.bit())
        append(" wakeScreenOnPhoneDnd=").append(settings.wakeScreenOnPhoneDnd.bit())
        append(" respectWatchDnd=").append(settings.respectWatchDnd.bit())
        append(" globalWakeCooldownMs=2000")
        append(" skipWakeOffWrist=").append(settings.skipWakeOffWrist.bit())
        append(" skipSoundOffWrist=").append(settings.skipSoundOffWrist.bit())
        append(" soundMode=").append(settings.soundMode.name)
        append(" alarmBridge=").append(settings.alarmBridge.bit())
        append(" dndSyncEnabled=").append(settings.dndSyncEnabled.bit())
        append(" appFilterMode=").append(settings.appFilterMode.name)
        append(" appPackages=[").append(settings.appPackages.sorted().joinToString(",")).append(']')
        append(" appPaused=").append(settings.appPaused.bit())
        append(" pauseKeepsAlarmAndDnd=").append(settings.pauseKeepsAlarmAndDnd.bit())
    }

    private fun changes(old: AppSettings, new: AppSettings): List<String> = buildList {
        change("screenWake", old.screenWake, new.screenWake)
        change("skipWhenPhoneUnlocked", old.skipWhenPhoneUnlocked, new.skipWhenPhoneUnlocked)
        change("skipSilentNotifications", old.skipSilentNotifications, new.skipSilentNotifications)
        change("respectPhoneDnd", old.respectPhoneDnd, new.respectPhoneDnd)
        change("wakeScreenOnPhoneDnd", old.wakeScreenOnPhoneDnd, new.wakeScreenOnPhoneDnd)
        change("respectWatchDnd", old.respectWatchDnd, new.respectWatchDnd)
        change("skipWakeOffWrist", old.skipWakeOffWrist, new.skipWakeOffWrist)
        change("skipSoundOffWrist", old.skipSoundOffWrist, new.skipSoundOffWrist)
        change("soundMode", old.soundMode.name, new.soundMode.name)
        change("alarmBridge", old.alarmBridge, new.alarmBridge)
        change("dndSyncEnabled", old.dndSyncEnabled, new.dndSyncEnabled)
        change("appFilterMode", old.appFilterMode.name, new.appFilterMode.name)
        change("appPaused", old.appPaused, new.appPaused)
        change("pauseKeepsAlarmAndDnd", old.pauseKeepsAlarmAndDnd, new.pauseKeepsAlarmAndDnd)
        if (old.appPackages != new.appPackages) {
            val added = (new.appPackages - old.appPackages).sorted().joinToString(",").ifBlank { "none" }
            val removed = (old.appPackages - new.appPackages).sorted().joinToString(",").ifBlank { "none" }
            add("key=appPackages added=[$added] removed=[$removed]")
        }
    }

    private fun <T> MutableList<String>.change(key: String, old: T, new: T) {
        if (old != new) add("key=$key old=$old new=$new")
    }

    private fun Boolean.bit(): Int = if (this) 1 else 0
}
