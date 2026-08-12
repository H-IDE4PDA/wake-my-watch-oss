package com.h_ide4pda.wakemywatch.core

import android.content.Context
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
}
