package com.h_ide4pda.wakemywatch.phone.wear

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object PhoneDiagnosticsTransfer {
    data class Result(
        val requestId: String,
        val report: String? = null,
        val error: String? = null,
    )

    private val pending = ConcurrentHashMap<String, CompletableFuture<Result>>()

    fun prepare(requestId: String): CompletableFuture<Result> =
        CompletableFuture<Result>().also { pending[requestId] = it }

    fun complete(requestId: String, report: String) {
        pending.remove(requestId)?.complete(Result(requestId = requestId, report = report))
    }

    fun fail(requestId: String, error: String) {
        pending.remove(requestId)?.complete(Result(requestId = requestId, error = error))
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)?.cancel(true)
    }
}
