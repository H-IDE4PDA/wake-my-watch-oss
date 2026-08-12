package com.h_ide4pda.wakemywatch.watch.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.h_ide4pda.wakemywatch.core.SoundMode

object SoundController {
    /**
     * A full second ringtone is clearly heard as a different notification after OHealth's sound.
     * Keep only its initial audible fragment. Unlike the previous ToneGenerator experiment, this
     * uses the real notification audio path already proven to work on the OnePlus Watch.
     */
    private const val SYSTEM_CORRECTION_PLAYBACK_MS = 250L

    // Rebuilding a fresh MediaPlayer (create + setDataSource + prepareAsync) on every single
    // notification was measured adding 76-312 ms of start delay before any sound came out. One
    // player is prepared once and reused (pause + seekTo(0) + start) for every correction; it's
    // only torn down if the default ringtone changes or after a long idle gap, as a defensive
    // measure against the system reclaiming a long-unused player.
    private const val IDLE_RECREATE_MS = 5 * 60 * 1000L
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Result(
        val systemPlayed: Boolean,
        val detail: String,
    )

    @Volatile private var active: Any? = null
    @Volatile private var reusablePlayer: MediaPlayer? = null
    @Volatile private var reusableUri: Uri? = null
    @Volatile private var reusableReady: Boolean = false
    @Volatile private var lastUsedAt: Long = 0L
    @Volatile private var lastPlaybackAt: Long = 0L
    @Volatile private var lastPlaybackDetail: String = "never"

    /**
     * NONE: Wake My Watch stays silent.
     * SYSTEM: plays only the first 250 ms of the watch's default notification ringtone as a
     * correction tail. It does not and cannot copy OHealth's per-channel sound.
     */
    fun playNotification(context: Context, mode: SoundMode): Result {
        if (mode == SoundMode.NONE) return record(Result(false, "sound_disabled"))

        val result = when (mode) {
            SoundMode.NONE -> Result(false, "sound_disabled")
            SoundMode.SYSTEM -> {
                val systemPlayed = playSystemCorrection(context, System.currentTimeMillis())
                Result(
                    systemPlayed,
                    if (systemPlayed) "system_correction_${SYSTEM_CORRECTION_PLAYBACK_MS}ms_mediaplayer" else "system_unavailable",
                )
            }
        }
        return record(result)
    }

    fun diagnosticState(context: Context): String {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        val title = runCatching { uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) } }.getOrNull()
        return buildString {
            append("defaultUri=").append(uri ?: "none")
            append(" defaultTitle=").append(title ?: "unknown")
            append(" correctionMs=").append(SYSTEM_CORRECTION_PLAYBACK_MS)
            append(" reusablePlayer=").append(reusablePlayer != null)
            append(" reusableReady=").append(reusableReady)
            append(" lastPlaybackAt=").append(lastPlaybackAt)
            append(" lastPlaybackDetail=").append(lastPlaybackDetail)
        }
    }

    @Synchronized
    private fun playSystemCorrection(context: Context, requestedAt: Long): Boolean {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            ?: return false
        val playback = Any()
        active = playback

        val needsFreshPlayer = reusablePlayer == null ||
            reusableUri != uri ||
            requestedAt - lastUsedAt > IDLE_RECREATE_MS
        lastUsedAt = requestedAt

        return runCatching {
            if (needsFreshPlayer) {
                createReusablePlayer(context, uri, playback)
            } else if (reusableReady) {
                restartReusablePlayer(playback)
            }
            // else: a previous prepareAsync() is still in flight. Its own onPrepared handler
            // checks `active` and will pick up this request once ready; nothing to do here.
            true
        }.getOrElse {
            // Reuse failed (e.g. the system reclaimed the player) — rebuild once and retry.
            runCatching {
                createReusablePlayer(context, uri, playback)
                true
            }.getOrDefault(false)
        }
    }

    private fun restartReusablePlayer(playback: Any) {
        val player = reusablePlayer ?: return
        player.seekTo(0)
        player.start()
        scheduleStop(player, playback)
    }

    private fun createReusablePlayer(context: Context, uri: Uri, playback: Any) {
        releaseReusablePlayer()
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        player.setDataSource(context.applicationContext, uri)
        player.setOnPreparedListener { prepared ->
            reusableReady = true
            if (active === playback) {
                runCatching { prepared.start() }
                    .onFailure { if (active === playback) active = null }
                scheduleStop(prepared, playback)
            }
            // Otherwise a newer request already superseded this one before prepare finished —
            // the player is now ready and will simply be reused by the next call.
        }
        player.setOnErrorListener { _, _, _ ->
            reusableReady = false
            if (active === playback) active = null
            true
        }
        reusablePlayer = player
        reusableUri = uri
        reusableReady = false
        player.prepareAsync()
    }

    private fun scheduleStop(player: MediaPlayer, playback: Any) {
        mainHandler.postDelayed({
            if (active === playback) {
                // pause(), not stop(): stopping would require a full prepare() cycle again
                // before the player could be reused for the next notification.
                runCatching { if (player.isPlaying) player.pause() }
                active = null
            }
        }, SYSTEM_CORRECTION_PLAYBACK_MS)
    }

    private fun releaseReusablePlayer() {
        reusablePlayer?.let { runCatching { it.release() } }
        reusablePlayer = null
        reusableUri = null
        reusableReady = false
    }

    private fun record(result: Result): Result {
        lastPlaybackAt = System.currentTimeMillis()
        lastPlaybackDetail = result.detail
        return result
    }
}
