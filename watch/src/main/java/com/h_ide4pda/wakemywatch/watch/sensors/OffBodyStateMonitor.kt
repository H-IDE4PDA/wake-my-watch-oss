package com.h_ide4pda.wakemywatch.watch.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.KeyguardManager
import com.h_ide4pda.wakemywatch.core.AppSettings
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
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

    // Held only to log sensor transitions from onSensorChanged, which gets no context of its own.
    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun updateRegistration(context: Context, settings: AppSettings) {
        val app = context.applicationContext
        appContext = app
        val manager = sensorManager ?: app.getSystemService(SensorManager::class.java).also {
            sensorManager = it
        }
        val sensor = offBodySensor ?: manager.getDefaultSensor(TYPE_LOW_LATENCY_OFFBODY_DETECT).also {
            offBodySensor = it
        }
        supported = sensor != null

        val wakeProtectionNeeded = settings.screenWake && settings.skipWakeOffWrist
        // Vibration shares the off-wrist rule with sound, so it has to keep the sensor alive on
        // its own — otherwise a vibration-only setup would never learn the watch is off the wrist.
        val alertProtectionNeeded = (settings.soundMode != SoundMode.NONE || settings.silentVibrate) &&
            settings.skipSoundOffWrist
        // Paused app has nothing to wake/alert for, regardless of pauseKeepsAlarmAndDnd — the
        // sensor is unrelated to Alarm Bridge/DND Sync, so there is no exception here.
        val shouldRegister = sensor != null && !settings.appPaused && (wakeProtectionNeeded || alertProtectionNeeded)

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

    /**
     * Off-wrist decision used by the wake path.
     *
     * With [requiresLock] false this is exactly the previous behavior — the sensor reading alone.
     * With it true, off-wrist also requires a locked keyguard: the listener service process can be
     * torn down and rebuilt for every message, so the sensor re-registers and this can be evaluated
     * before a real reading arrives, and a background process the framework denies sensor access to
     * is handed zeroes — read as off-body. Wear OS locks the watch the moment it actually leaves the
     * wrist, so an unlocked watch is being worn no matter what the sensor claims. With no lock
     * configured, the keyguard says nothing useful and the sensor stays the only signal there is.
     */
    fun isOffWrist(context: Context, requiresLock: Boolean): Boolean {
        if (!supported || isWorn != false) return false
        if (!requiresLock) return true
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return true
        if (!runCatching { keyguard.isDeviceSecure }.getOrDefault(false)) return true
        return runCatching { keyguard.isKeyguardLocked }.getOrDefault(true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull()
        val worn = raw?.let { it >= 0.5f }
        val changed = worn != isWorn
        isWorn = worn
        // Diagnostic only — does not feed the off_wrist decision. Lets us see on our own hardware
        // whether the sensor reports off-wrist while the watch is actually being worn.
        if (changed) {
            appContext?.let { context ->
                EventHistoryStore.add(
                    context,
                    "OFF_BODY",
                    if (worn == true) "WORN" else "OFF_WRIST",
                    "raw=$raw",
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
