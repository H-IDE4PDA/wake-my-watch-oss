package com.h_ide4pda.wakemywatch.watch.wake

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Compatibility fallback used only if the short screen wake lock cannot be acquired. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.decorView.alpha = 0f
        Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, 300L)
    }

    companion object {
        fun show(context: Context, eventId: String): Result<Unit> = runCatching {
            context.startActivity(
                Intent(context, WakeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                    .putExtra("eventId", eventId),
            )
        }
    }
}
