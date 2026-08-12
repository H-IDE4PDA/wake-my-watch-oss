package com.h_ide4pda.wakemywatch.phone.wear

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

object PhoneDiagnosticsChunkAssembler {
    private const val MAX_CHUNKS = 256
    private const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    data class Progress(
        val accepted: Boolean,
        val completed: Boolean,
        val receivedChunks: Int,
        val totalChunks: Int,
        val error: String? = null,
    )

    private data class Assembly(
        val totalChunks: Int,
        val chunks: Array<String?>,
        var encodedBytes: Int = 0,
    )

    private val assemblies = ConcurrentHashMap<String, Assembly>()

    @Synchronized
    fun receive(
        requestId: String,
        index: Int,
        totalChunks: Int,
        encoding: String,
        data: String,
    ): Progress {
        if (encoding != "gzip+base64") {
            fail(requestId, "unsupported_encoding:$encoding")
            return Progress(false, false, 0, totalChunks, "unsupported_encoding")
        }
        if (totalChunks !in 1..MAX_CHUNKS || index !in 0 until totalChunks) {
            fail(requestId, "invalid_chunk_index:$index/$totalChunks")
            return Progress(false, false, 0, totalChunks, "invalid_chunk_index")
        }

        val assembly = assemblies.getOrPut(requestId) {
            Assembly(totalChunks = totalChunks, chunks = arrayOfNulls(totalChunks))
        }
        if (assembly.totalChunks != totalChunks) {
            fail(requestId, "chunk_count_changed:${assembly.totalChunks}/$totalChunks")
            return Progress(false, false, 0, totalChunks, "chunk_count_changed")
        }

        if (assembly.chunks[index] == null) {
            assembly.encodedBytes += data.length
            if (assembly.encodedBytes > MAX_ENCODED_BYTES) {
                fail(requestId, "watch_report_too_large")
                return Progress(false, false, 0, totalChunks, "watch_report_too_large")
            }
            assembly.chunks[index] = data
        }

        val received = assembly.chunks.count { it != null }
        if (received != totalChunks) return Progress(true, false, received, totalChunks)

        val decoded = runCatching {
            val encoded = assembly.chunks.joinToString(separator = "") { requireNotNull(it) }
            val compressed = Base64.getDecoder().decode(encoded)
            GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return if (decoded.isSuccess) {
            assemblies.remove(requestId)
            PhoneDiagnosticsTransfer.complete(requestId, decoded.getOrThrow())
            Progress(true, true, totalChunks, totalChunks)
        } else {
            val detail = decoded.exceptionOrNull()?.message ?: "chunk_decode_failed"
            fail(requestId, detail)
            Progress(false, false, received, totalChunks, detail)
        }
    }

    @Synchronized
    fun cancel(requestId: String) {
        assemblies.remove(requestId)
    }

    private fun fail(requestId: String, detail: String) {
        assemblies.remove(requestId)
        PhoneDiagnosticsTransfer.fail(requestId, detail)
    }
}
