package com.h_ide4pda.wakemywatch.watch.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.h_ide4pda.wakemywatch.core.EventHistoryStore
import kotlin.math.PI
import kotlin.math.sin

object AlarmAlertController {
    private const val SAMPLE_RATE = 22_050
    private const val FALLBACK_DURATION_MS = 1_500
    private const val FALLBACK_TONE_HZ = 880.0

    private var ringtone: Ringtone? = null
    private var fallbackTrack: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var activeEventId: String? = null

    @Synchronized
    fun start(context: Context, eventId: String) {
        if (activeEventId == eventId && (ringtone?.isPlaying == true || fallbackTrack?.playState == AudioTrack.PLAYSTATE_PLAYING)) {
            return
        }
        stop(context, "restart")
        activeEventId = eventId
        EventHistoryStore.add(context, "ALARM_ALERT", "START", eventId)
        startSound(context)
        startVibration(context)
    }

    /**
     * Wear OS can silently drop the active vibration when the display turns off mid-alarm
     * without tearing down the Activity or the ringtone/AudioTrack session (see AlarmActivity's
     * ACTION_SCREEN_ON recovery). There is no callback for a vibration ending early, so this is
     * called on every screen-on while the alarm is still active to unconditionally restart it.
     */
    @Synchronized
    fun refreshForScreenOn(context: Context, eventId: String): Boolean {
        if (activeEventId != eventId) return false
        startVibration(context)
        return true
    }

    @Synchronized
    fun stop(context: Context, reason: String) {
        val hadActiveAlert = activeEventId != null || ringtone != null || fallbackTrack != null || vibrator != null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            fallbackTrack?.pause()
            fallbackTrack?.flush()
            fallbackTrack?.release()
        }
        fallbackTrack = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        activeEventId = null
        if (hadActiveAlert) {
            EventHistoryStore.add(context, "ALARM_ALERT", "STOP", reason)
        }
    }

    private fun startSound(context: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val ringtoneStarted = runCatching {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            requireNotNull(alarmUri) { "alarm_ringtone_uri_unavailable" }
            val alarmRingtone = RingtoneManager.getRingtone(context, alarmUri)
            requireNotNull(alarmRingtone) { "alarm_ringtone_unavailable" }
            alarmRingtone.audioAttributes = attrs
            alarmRingtone.isLooping = true
            alarmRingtone.volume = 1f
            alarmRingtone.play()
            ringtone = alarmRingtone
            EventHistoryStore.add(context, "ALARM_SOUND", "OK", "ringtone=$alarmUri")
            true
        }.getOrElse { error ->
            EventHistoryStore.add(context, "ALARM_SOUND", "RINGTONE_FAILED", error.message ?: error.javaClass.simpleName)
            false
        }

        if (!ringtoneStarted) {
            startFallbackTone(context, attrs)
        }
    }

    private fun startFallbackTone(context: Context, attrs: AudioAttributes) {
        val samples = buildFallbackWaveform()
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            val written = track.write(samples, 0, samples.size)
            require(written > 0) { "fallback_write_failed" }
            track.setLoopPoints(0, samples.size, -1)
            track.setVolume(0.55f)
            track.play()
            fallbackTrack = track
            EventHistoryStore.add(context, "ALARM_SOUND", "OK", "fallback_tone samples=$written")
        }.onFailure { error ->
            EventHistoryStore.add(context, "ALARM_SOUND", "FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun buildFallbackWaveform(): ShortArray {
        val totalSamples = SAMPLE_RATE * FALLBACK_DURATION_MS / 1_000
        val toneSamples = SAMPLE_RATE * 420 / 1_000
        val pauseSamples = SAMPLE_RATE * 250 / 1_000
        val attackSamples = SAMPLE_RATE * 30 / 1_000
        val releaseSamples = SAMPLE_RATE * 60 / 1_000
        val data = ShortArray(totalSamples)
        for (i in data.indices) {
            val cyclePosition = i % (toneSamples + pauseSamples)
            if (cyclePosition >= toneSamples) continue
            val envelope = when {
                cyclePosition < attackSamples -> cyclePosition.toDouble() / attackSamples.coerceAtLeast(1)
                cyclePosition > toneSamples - releaseSamples -> (toneSamples - cyclePosition).toDouble() / releaseSamples.coerceAtLeast(1)
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val sample = sin(2.0 * PI * FALLBACK_TONE_HZ * i / SAMPLE_RATE) * envelope * Short.MAX_VALUE * 0.45
            data[i] = sample.toInt().toShort()
        }
        return data
    }

    private fun startVibration(context: Context) {
        val vib = context.getSystemService(Vibrator::class.java)
        if (vib == null || !vib.hasVibrator()) {
            EventHistoryStore.add(context, "ALARM_VIBRATION", "FAILED", "vibrator_unavailable")
            return
        }
        runCatching {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 650, 250, 650, 700),
                intArrayOf(0, 210, 0, 210, 0),
                0,
            )
            vib.vibrate(effect)
            vibrator = vib
            EventHistoryStore.add(context, "ALARM_VIBRATION", "OK", "waveform_loop")
        }.onFailure { error ->
            EventHistoryStore.add(context, "ALARM_VIBRATION", "FAILED", error.message ?: error.javaClass.simpleName)
        }
    }
}
