package com.h_ide4pda.wakemywatch.core

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Shared vibration patterns for phone and watch, so both play identical timing.
 */
object VibroPatterns {

    /**
     * Two short pulses — the wrist alert for a forwarded notification. Deliberately shorter and
     * weaker than [playLookAtPhone] so the two stay distinguishable on the wrist, and short
     * enough not to overlap the sound correction that plays alongside it.
     *
     * Returns false when the device has no vibrator, so the caller can log it.
     */
    fun playNotification(context: Context): Boolean {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
        if (!vibrator.hasVibrator()) return false
        val timings = longArrayOf(0, 70, 90, 70)
        val amplitudes = intArrayOf(0, 180, 0, 180)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        return true
    }

    /** Two short pulses, ~1s pause, two short pulses — used for the "look at phone" hint. */
    fun playLookAtPhone(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val timings = longArrayOf(0, 60, 80, 60, 1000, 60, 80, 60)
        val amplitudes = intArrayOf(0, 130, 0, 130, 0, 130, 0, 130)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}
