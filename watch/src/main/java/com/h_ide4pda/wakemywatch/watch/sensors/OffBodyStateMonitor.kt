package com.h_ide4pda.wakemywatch.watch.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.h_ide4pda.wakemywatch.core.AppSettings
import com.h_ide4pda.wakemywatch.core.SoundMode

/**
 * Keeps the low-latency off-body sensor registered only while a setting actually needs it.
 * This avoids a permanent sensor listener when both off-wrist protections are irrelevant.
 */
object OffBodyStateMonitor : SensorEventListener {
    private const val TYPE_LOW_LATENCY_OFFBODY_DETECT = 34

    @Volatile
    var supported: Boolean = false
        private set

    @Volatile
    var isWorn: Boolean? = null
        private set

    @Volatile
    var isRegistered: Boolean = false
        private set

    private var sensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null

    @Synchronized
    fun updateRegistration(context: Context, settings: AppSettings) {
        val app = context.applicationContext
        val manager = sensorManager ?: app.getSystemService(SensorManager::class.java).also {
            sensorManager = it
        }
        val sensor = offBodySensor ?: manager.getDefaultSensor(TYPE_LOW_LATENCY_OFFBODY_DETECT).also {
            offBodySensor = it
        }
        supported = sensor != null

        val wakeProtectionNeeded = settings.screenWake && settings.skipWakeOffWrist
        val soundProtectionNeeded = settings.soundMode != SoundMode.NONE && settings.skipSoundOffWrist
        // Paused app has nothing to wake/sound for, regardless of pauseKeepsAlarmAndDnd — the
        // sensor is unrelated to Alarm Bridge/DND Sync, so there is no exception here.
        val shouldRegister = sensor != null && !settings.appPaused && (wakeProtectionNeeded || soundProtectionNeeded)

        when {
            shouldRegister && !isRegistered -> {
                val availableSensor = sensor ?: return
                isRegistered = manager.registerListener(this, availableSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            !shouldRegister && isRegistered -> {
                manager.unregisterListener(this)
                isRegistered = false
                isWorn = null
            }
        }
    }

    @Synchronized
    fun stop() {
        sensorManager?.unregisterListener(this)
        isRegistered = false
        isWorn = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        isWorn = event.values.firstOrNull()?.let { it >= 0.5f }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
