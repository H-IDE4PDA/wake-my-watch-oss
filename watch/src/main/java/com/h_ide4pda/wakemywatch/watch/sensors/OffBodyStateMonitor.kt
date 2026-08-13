package com.h_ide4pda.wakemywatch.watch.sensors

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        val alertProtectionNeeded = (settings.soundMode != SoundMode.NONE || settings.vibrateOnWake) &&
            settings.skipSoundOffWrist
        // Paused app has nothing to wake/sound for, regardless of pauseKeepsAlarmAndDnd — the
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
     * The sensor reading alone is not enough to suppress anything.
     *
     * The listener service process is torn down between messages and rebuilt for each one, so
     * every notification re-registers the sensor and decides immediately, before a real reading
     * can arrive — and a background process the framework has flagged `has sensor access: false`
     * is handed zeroes, which read as "off body". Wear OS locks the watch the moment it leaves
     * the wrist, so an unlocked secure watch is being worn whatever our own reading claims.
     *
     * Confirmed 2026-08-13: the hardware reported on-body at 07:40:48 and never changed, yet an
     * hour of notifications was suppressed as off-wrist while the watch was worn and unlocked.
     *
     * With no lock configured the keyguard says nothing useful, and the sensor stays the only
     * signal there is.
     */
    fun isOffWrist(context: Context): Boolean {
        if (!supported || isWorn != false) return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return true
        if (!runCatching { keyguard.isDeviceSecure }.getOrDefault(false)) return true
        return runCatching { keyguard.isKeyguardLocked }.getOrDefault(true)
    }

    /** Inputs behind the decision, so the event log shows why a wake was or was not suppressed. */
    fun decisionDetail(context: Context): String {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val secure = runCatching { keyguard?.isDeviceSecure == true }.getOrDefault(false)
        val locked = runCatching { keyguard?.isKeyguardLocked == true }.getOrDefault(false)
        val sensor = when (isWorn) {
            null -> "unknown"
            true -> "worn"
            false -> "off"
        }
        return "sensor=$sensor,secureLock=${secure.bit()},locked=${locked.bit()},registered=${isRegistered.bit()}"
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull()
        val worn = raw?.let { it >= 0.5f }
        val changed = worn != isWorn
        isWorn = worn
        // Transitions were previously invisible: nothing about this sensor reached the event
        // history, so a wrong reading could only be reconstructed from suppressed notifications.
        if (changed) {
            appContext?.let { context ->
                EventHistoryStore.add(
                    context,
                    "OFF_BODY",
                    if (worn == true) "WORN" else "OFF_WRIST",
                    "raw=$raw ${decisionDetail(context)}",
                )
            }
        }
    }

    private fun Boolean.bit(): Int = if (this) 1 else 0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
