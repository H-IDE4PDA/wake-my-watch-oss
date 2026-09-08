package com.h_ide4pda.wakemywatch.core

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Shared vibration patterns for phone and watch, so both play identical timing.
 */
object VibroPatterns {

    /** Two short pulses, ~1s pause, two short pulses — used for the "look at phone" hint. */
    fun playLookAtPhone(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val timings = longArrayOf(0, 60, 80, 60, 1000, 60, 80, 60)
        val amplitudes = intArrayOf(0, 130, 0, 130, 0, 130, 0, 130)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /**
     * Two short pulses — the wrist alert for a forwarded notification. Deliberately shorter and
     * weaker than [playLookAtPhone] so the two stay distinguishable on the wrist.
     *
     * Uses USAGE_HARDWARE_FEEDBACK: WatchDndSyncBridge's DND confirmation buzz found this usage is
     * a real (if partial) defense against Zen muting a vibrate() call on this OEM skin — kept
     * here as the same best-effort mitigation, not a guarantee. Unlike that buzz, this fires on
     * an ordinary notification, not a DND state transition, so there is no "wait for the system
     * to actually lift enforcement" delay to add.
     *
     * Returns false when the device has no vibrator, so the caller can log it.
     */
    fun playNotification(context: Context): Boolean {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
        if (!vibrator.hasVibrator()) return false
        val timings = longArrayOf(0, 70, 90, 70)
        val amplitudes = intArrayOf(0, 180, 0, 180)
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), attributes)
        return true
    }
}
