package com.h_ide4pda.wakemywatch.phone.notifications

class WakeRateLimiter {
    data class Decision(val skip: Boolean, val reason: String = "new")

    private data class RecentWake(
        val at: Long,
        val packageName: String,
        val contentHash: String,
    )

    private val recentByContent = mutableMapOf<String, RecentWake>()
    private var lastGlobalWakeAt: Long? = null
    private var lastGlobalWakePackage: String? = null

    /** Atomically evaluates all limits and records an accepted wake before returning. */
    @Synchronized
    fun decideAndMark(
        packageName: String,
        contentHash: String,
        now: Long = System.currentTimeMillis(),
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

        lastGlobalWakeAt?.let { lastAt ->
            val elapsed = now - lastAt
            if (elapsed in 0 until GLOBAL_WAKE_COOLDOWN_MS) {
                return Decision(
                    skip = true,
                    reason = "global_wake_cooldown source=${lastGlobalWakePackage.orEmpty()} remainingMs=${GLOBAL_WAKE_COOLDOWN_MS - elapsed}",
                )
            }
        }

        recentByContent[contentHash] = RecentWake(now, packageName, contentHash)
        lastGlobalWakeAt = now
        lastGlobalWakePackage = packageName
        cleanup(now)
        return Decision(skip = false)
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
        const val GLOBAL_WAKE_COOLDOWN_MS = 2_000L
        const val CROSS_APP_DUPLICATE_WINDOW_MS = 2_000L
        private const val MAX_RETENTION_MS = 60_000L
    }
}
