package com.h_ide4pda.wakemywatch.watch.ringer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.h_ide4pda.wakemywatch.core.EventHistoryStore

/**
 * Watches the watch's own sound-profile changes and hands them to [WatchRingerSyncBridge] so they
 * can be pushed to the phone. Mirror of the phone-side receiver in NotificationRelayService.
 *
 * RINGER_MODE_CHANGED_ACTION is not an implicit-broadcast exemption, so a manifest receiver never
 * gets it — it has to be context-registered from a running component. Registered once from
 * WakeMyWatchApplication.onCreate(); lives for the process, which is all we need (a ringer change
 * only happens while the watch is awake and the process running).
 */
object WatchRingerModeWatcher {
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
                val mode = intent.getIntExtra(
                    AudioManager.EXTRA_RINGER_MODE,
                    ctx.getSystemService(AudioManager::class.java).ringerMode,
                )
                WatchRingerSyncBridge.onWatchRingerModeChanged(app, mode)
            }
        }
        runCatching {
            app.registerReceiver(r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
            receiver = r
        }.onFailure {
            EventHistoryStore.add(app, "RINGER_SYNC", "RECEIVER_REGISTER_FAILED", it.message ?: it.javaClass.simpleName)
        }
    }
}
