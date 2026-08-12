package com.h_ide4pda.wakemywatch.phone.wear

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates a DND_SYNC command sent to the watch with the watch's real Protocol.ACK reply
 * (applyIncomingFromPhone's result), so a caller can wait for confirmation that the watch
 * actually processed the change — not just that the message was handed to the transport.
 *
 * Carries the raw (result, detail) pair rather than a pre-computed success boolean: "success"
 * means different things for a clear-on-disable (a "disabled_on_watch" reply means the watch is
 * already out of our control, which is fine) versus an apply-on-enable (the same reply means our
 * enabling SETTINGS hasn't reached the watch yet, so nothing was actually applied — a real
 * failure that must be retried). Each caller in PhoneDndSyncBridge interprets the pair itself.
 */
object PhoneDndSyncAckTransfer {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Pair<String, String>>>()

    fun prepare(requestId: String): CompletableFuture<Pair<String, String>> =
        CompletableFuture<Pair<String, String>>().also { pending[requestId] = it }

    fun complete(requestId: String, result: String, detail: String) {
        pending.remove(requestId)?.complete(result to detail)
    }

    fun fail(requestId: String) {
        pending.remove(requestId)?.complete("SEND_FAILED" to "transport")
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)?.cancel(true)
    }
}
