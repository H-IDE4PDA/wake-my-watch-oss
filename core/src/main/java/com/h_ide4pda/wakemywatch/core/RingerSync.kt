package com.h_ide4pda.wakemywatch.core

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

/**
 * Sound-profile (ringer mode) mirroring, phone <-> watch (two-way). Deliberately kept apart from
 * [DndSync]: Do Not Disturb filters *which* notifications get through, the ringer mode only
 * decides whether the ones that do get through make a sound.
 *
 * Two-way, unlike [DndSync] which stays phone->watch only. DND was two-way once and an echo of a
 * watch-side clear wiped the phone's DND (removed in vc75); the ringer mode is lower-stakes and
 * users want to flip it from either device, so the loop is broken with an echo-suppression
 * window instead — see [markRemoteApplied] / [shouldSuppressEcho].
 */
object RingerSync {
    const val TYPE = "RINGER_SYNC"
    const val SOURCE_PHONE = "phone"
    const val SOURCE_WATCH = "watch"

    /** Watch -> phone leg is switched off (default): the incoming change from the watch is dropped. */
    const val SKIP_REVERSE_DISABLED = "reverse_sync_disabled"

    /**
     * A SILENT change originating on the watch is never forwarded to the phone. The watch has no
     * user-facing "silent" profile, so this only comes from system automation (sleep/bedtime
     * mode) and forwarding it would put the phone into Do Not Disturb by itself at night.
     */
    const val SKIP_WATCH_SILENT = "watch_silent_not_forwarded"

    private const val PREFS = "wmw_ringer_sync_state"
    private const val LAST_REMOTE_MODE = "last_remote_mode"
    private const val LAST_REMOTE_AT = "last_remote_at"
    private const val LAST_REMOTE_SOURCE = "last_remote_source"

    /**
     * How long after applying a change received from the partner its echo (our own
     * RINGER_MODE_CHANGED_ACTION for that same change) is ignored. A cold Wear round-trip is
     * ~4 s, so this needs headroom past that; kept at 6 s so a deliberate re-flip by the user a
     * few seconds later still syncs. A user switching to a *different* mode inside the window is
     * never suppressed (the mode won't match [LAST_REMOTE_MODE]).
     */
    const val ECHO_SUPPRESS_MS = 6_000L

    /** AudioManager.RINGER_MODE_* : SILENT=0, VIBRATE=1, NORMAL=2. */
    fun isValidRingerMode(mode: Int): Boolean =
        mode == AudioManager.RINGER_MODE_SILENT ||
            mode == AudioManager.RINGER_MODE_VIBRATE ||
            mode == AudioManager.RINGER_MODE_NORMAL

    fun name(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
        else -> "UNKNOWN($mode)"
    }

    fun payload(mode: Int, source: String, reason: String = "local_change"): JSONObject = JSONObject()
        .put("ringerMode", mode)
        .put("ringerModeName", name(mode))
        .put("source", source)
        .put("reason", reason)

    fun modeFrom(envelope: MessageEnvelope): Int? {
        val value = envelope.payload.optInt("ringerMode", Int.MIN_VALUE)
        return value.takeIf(::isValidRingerMode)
    }

    fun sourceFrom(envelope: MessageEnvelope): String =
        envelope.payload.optString("source", "unknown").ifBlank { "unknown" }

    /** Records a change we just applied because the partner asked us to, so our own resulting
     * RINGER_MODE_CHANGED_ACTION broadcast is recognised as an echo and not sent back. */
    fun markRemoteApplied(context: Context, mode: Int, source: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_REMOTE_MODE, mode)
            .putLong(LAST_REMOTE_AT, System.currentTimeMillis())
            .putString(LAST_REMOTE_SOURCE, source)
            .apply()
    }

    /** True when [mode] matches the last partner-applied change and it happened within
     * [ECHO_SUPPRESS_MS] — i.e. this local RINGER_MODE_CHANGED is that change echoing back. */
    fun shouldSuppressEcho(context: Context, mode: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastMode = prefs.getInt(LAST_REMOTE_MODE, Int.MIN_VALUE)
        val lastAt = prefs.getLong(LAST_REMOTE_AT, 0L)
        return lastMode == mode && System.currentTimeMillis() - lastAt in 0..ECHO_SUPPRESS_MS
    }

    fun lastRemoteSource(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LAST_REMOTE_SOURCE, null) ?: "unknown"
}
