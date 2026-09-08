package com.h_ide4pda.wakemywatch.phone.wear

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class DndSyncStatus(
    val value: Int,
    val currentInterruptionFilter: Int,
    /** false when no reachable answer arrived — the UI must show "couldn't check", not "missing". */
    val reachable: Boolean = true,
    val watchNotificationListenerGranted: Boolean = false,
    val watchDndPolicyAccessGranted: Boolean = false,
    val watchPostNotificationsGranted: Boolean = false,
    val watchRingerMode: Int = -1,
)

/** Correlates an on-demand watch DND-sync status request with its async response. */
object PhoneDndSyncStatusTransfer {
    private val pending = ConcurrentHashMap<String, CompletableFuture<DndSyncStatus>>()

    fun prepare(requestId: String): CompletableFuture<DndSyncStatus> =
        CompletableFuture<DndSyncStatus>().also { pending[requestId] = it }

    fun complete(requestId: String, status: DndSyncStatus) {
        pending.remove(requestId)?.complete(status)
    }

    fun fail(requestId: String) {
        pending.remove(requestId)?.complete(DndSyncStatus(-1, -1, reachable = false))
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)?.cancel(true)
    }
}
