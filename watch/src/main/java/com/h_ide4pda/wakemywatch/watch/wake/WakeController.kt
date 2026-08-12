package com.h_ide4pda.wakemywatch.watch.wake

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.h_ide4pda.wakemywatch.core.EventHistoryStore

/** Wakes the display without bringing Wake My Watch's normal UI to the foreground. */
object WakeController {
    data class Result(val success: Boolean, val detail: String)

    // How long to wait after acquiring the wake lock before re-reading the display state.
    // Needed to tell "lock accepted" apart from "screen actually lit up" — see SCREEN_WAKE
    // diagnostic logging below. Blocks the caller (and thus delays the WAKE_OK ack) by this
    // much; kept short since this only runs on the already-slow "not already on" path.
    private const val AFTER_CHECK_DELAY_MS = 200L

    @Suppress("DEPRECATION")
    fun wake(context: Context, eventId: String): Result {
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        val displayManager = app.getSystemService(DisplayManager::class.java)
        val displayState = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state
        val stateLabel = displayStateLabel(displayState)
        val beforeInteractive = power.isInteractive

        // On this watch isInteractive can remain true while the panel is in AOD/doze.
        // Trust the actual display state first; use isInteractive only as a fallback
        // when no default display is exposed by the service context.
        if (displayState == Display.STATE_ON) {
            logScreenWake(app, stateLabel, lockAcquired = false, afterOn = true)
            return Result(true, "already_display_on:interactive=$beforeInteractive")
        }
        if (displayState == null && beforeInteractive) {
            logScreenWake(app, "unknown_interactive", lockAcquired = false, afterOn = true)
            return Result(true, "already_interactive_fallback:display=unknown")
        }

        val wakeLockResult = runCatching {
            val flags = PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            power.newWakeLock(flags, "${app.packageName}:wake:${eventId.take(24)}").apply {
                setReferenceCounted(false)
                acquire(1_000L)
            }
        }
        if (wakeLockResult.isSuccess) {
            val afterOn = confirmDisplayOn(displayManager, power)
            logScreenWake(app, stateLabel, lockAcquired = true, afterOn = afterOn)
            return Result(true, "wake_lock:display=$stateLabel:interactive=$beforeInteractive:after_on=$afterOn")
        }

        val fallback = WakeActivity.show(app, eventId)
        return if (fallback.isSuccess) {
            val afterOn = confirmDisplayOn(displayManager, power)
            logScreenWake(app, stateLabel, lockAcquired = false, afterOn = afterOn, viaActivity = true)
            Result(true, "activity_fallback:display=$stateLabel:interactive=$beforeInteractive:after_on=$afterOn")
        } else {
            val detail = fallback.exceptionOrNull()?.message
                ?: wakeLockResult.exceptionOrNull()?.message
                ?: "screen_wake_failed"
            logScreenWake(app, stateLabel, lockAcquired = false, afterOn = false)
            Result(false, "$detail:display=$stateLabel:interactive=$beforeInteractive")
        }
    }

    /** Re-reads the display state shortly after a wake attempt so we can tell a lock that was
     * accepted apart from a screen that actually turned on. */
    @Suppress("DEPRECATION")
    private fun confirmDisplayOn(displayManager: DisplayManager, power: PowerManager): Boolean {
        Thread.sleep(AFTER_CHECK_DELAY_MS)
        val state = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state
        return if (state != null) state == Display.STATE_ON else power.isInteractive
    }

    private fun logScreenWake(
        context: Context,
        beforeLabel: String,
        lockAcquired: Boolean,
        afterOn: Boolean,
        viaActivity: Boolean = false,
    ) {
        val result = if (afterOn) "SCREEN_ON" else "SCREEN_OFF_FAILED"
        val afterLabel = if (afterOn) "on" else "off"
        val lockPart = if (viaActivity) "lockAcquired=нет(activity_fallback)" else "lockAcquired=${if (lockAcquired) "да" else "нет"}"
        val outcome = if (afterOn) "включился" else "не включился"
        val detail = "before=$beforeLabel $lockPart after=$afterLabel result=$outcome"
        EventHistoryStore.add(context, "SCREEN_WAKE", result, detail)
    }

    private fun displayStateLabel(state: Int?): String = when (state) {
        Display.STATE_OFF -> "off"
        Display.STATE_ON -> "on"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze_suspend"
        Display.STATE_VR -> "vr"
        Display.STATE_ON_SUSPEND -> "on_suspend"
        null -> "unknown"
        else -> "state_$state"
    }
}
