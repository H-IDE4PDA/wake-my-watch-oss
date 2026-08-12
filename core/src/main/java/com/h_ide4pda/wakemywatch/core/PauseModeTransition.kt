package com.h_ide4pda.wakemywatch.core

/**
 * Decides whether entering/leaving the sleep-safe pause mode (pauseKeepsAlarmAndDnd) should
 * force Alarm Bridge/DND Sync on or restore their pre-pause values. Must only be evaluated on
 * the phone: nativeOHealthPhone is a fact about the phone's own manufacturer/brand, which the
 * watch cannot determine for itself. The watch only ever applies whatever the phone already
 * resolved and synced over.
 */
object PauseModeTransition {
    fun resolve(previous: AppSettings, requested: AppSettings, nativeOHealthPhone: Boolean): AppSettings {
        val entering = requested.isPausedButAlarmDndAllowed && !previous.isPausedButAlarmDndAllowed
        val leaving = previous.isPausedButAlarmDndAllowed && !requested.isPausedButAlarmDndAllowed
        return when {
            entering -> requested.copy(
                alarmBridge = true,
                dndSyncEnabled = if (nativeOHealthPhone) requested.dndSyncEnabled else true,
                savedAlarmBridgeBeforePause = previous.alarmBridge,
                savedDndSyncEnabledBeforePause = if (nativeOHealthPhone) null else previous.dndSyncEnabled,
            )
            leaving -> requested.copy(
                alarmBridge = previous.savedAlarmBridgeBeforePause ?: requested.alarmBridge,
                dndSyncEnabled = previous.savedDndSyncEnabledBeforePause ?: requested.dndSyncEnabled,
                savedAlarmBridgeBeforePause = null,
                savedDndSyncEnabledBeforePause = null,
            )
            else -> requested
        }
    }
}
