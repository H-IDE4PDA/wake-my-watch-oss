package com.h_ide4pda.wakemywatch.phone.notifications

class WakeRateLimiter {
    data class Decision(
        val skip: Boolean,
        val reason: String = "new",
        /** Forward, but tell the watch to leave the panel dark: too soon after the last wake. */
        val suppressScreenWake: Boolean = false,
    )

    private data class RecentWake(
        val at: Long,
        val packageName: String,
        val contentHash: String,
    )

    private val recentByContent = mutableMapOf<String, RecentWake>()
    private var lastGlobalWakeAt: Long? = null
    private var lastGlobalWakePackage: String? = null

    /**
     * Atomically evaluates all limits and records an accepted wake before returning.
     *
     * [carriesMirror] marks a notification the watch will never receive any other way, so the
     * rate limit does not apply to it: dropping it does not save a screen wake, it destroys the
     * message. The cooldown was written when lighting the panel was the only reaction there was.
     */
    @Synchronized
    fun decideAndMark(
        packageName: String,
        contentHash: String,
        now: Long = System.currentTimeMillis(),
        carriesMirror: Boolean = false,
    ): Decision {
        cleanup(now)

        val recent = recentByContent[contentHash]
        if (recent != null && recent.packageName != packageName) {
            val age = now - recent.at
            if (age in 0..CROSS_APP_DUPLICATE_WINDOW_MS) {
                return Decision(
                    skip = true,
                    reason = "cross_app_duplicate source=${recent.packageName} ageMs=$age",
                )
            }
        }

        var suppressScreenWake = false
        lastGlobalWakeAt?.let { lastAt ->
            val elapsed = now - lastAt
            when {
                // Content the bridge will never deliver outranks the whole rate limit.
                carriesMirror -> Unit
                elapsed in 0 until ALERT_COOLDOWN_MS -> return Decision(
                    skip = true,
                    reason = "global_wake_cooldown source=${lastGlobalWakePackage.orEmpty()} remainingMs=${ALERT_COOLDOWN_MS - elapsed}",
                )
                // Past the buzz threshold but not the screen one: the wrist alert is worth
                // repeating for every message, a second panel flash this soon adds nothing.
                elapsed in 0 until SCREEN_WAKE_COOLDOWN_MS -> suppressScreenWake = true
            }
        }

        recentByContent[contentHash] = RecentWake(now, packageName, contentHash)
        lastGlobalWakeAt = now
        lastGlobalWakePackage = packageName
        cleanup(now)
        return Decision(skip = false, suppressScreenWake = suppressScreenWake)
    }

    @Synchronized
    private fun cleanup(now: Long) {
        recentByContent.entries.removeIf { now - it.value.at > MAX_RETENTION_MS }
        if (lastGlobalWakeAt?.let { now - it > MAX_RETENTION_MS } == true) {
            lastGlobalWakeAt = null
            lastGlobalWakePackage = null
        }
    }

    companion object {
        /** A second panel flash this soon after the last one carries no extra information. */
        const val SCREEN_WAKE_COOLDOWN_MS = 2_000L

        /**
         * A wrist buzz costs almost nothing, so it only needs a floor that stops a burst of
         * messages turning into continuous shaking — not the screen's full two seconds. Every
         * message in a conversation gets its own buzz again.
         */
        const val ALERT_COOLDOWN_MS = 700L
        const val CROSS_APP_DUPLICATE_WINDOW_MS = 2_000L
        private const val MAX_RETENTION_MS = 60_000L
    }
}
