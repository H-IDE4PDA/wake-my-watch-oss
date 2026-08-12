package com.h_ide4pda.wakemywatch.phone.wear

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class DndSyncStatus(val value: Int, val currentInterruptionFilter: Int)

/** Correlates an on-demand watch DND-sync status request with its async response. */
object PhoneDndSyncStatusTransfer {
    private val pending = ConcurrentHashMap<String, CompletableFuture<DndSyncStatus>>()

    fun prepare(requestId: String): CompletableFuture<DndSyncStatus> =
        CompletableFuture<DndSyncStatus>().also { pending[requestId] = it }

    fun complete(requestId: String, value: Int, currentInterruptionFilter: Int) {
        pending.remove(requestId)?.complete(DndSyncStatus(value, currentInterruptionFilter))
    }

    fun fail(requestId: String) {
        pending.remove(requestId)?.complete(DndSyncStatus(-1, -1))
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)?.cancel(true)
    }
}
