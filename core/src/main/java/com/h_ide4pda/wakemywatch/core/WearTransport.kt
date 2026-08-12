package com.h_ide4pda.wakemywatch.core

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable

object WearTransport {
    data class Result(val success: Boolean, val detail: String, val nodeId: String? = null)

    fun refreshConnectionState(context: Context, callback: (ConnectionSnapshot) -> Unit = {}) {
        val app = context.applicationContext
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                ConnectionDiagnosticsStore.updateConnectedNodes(app, nodes.map { it.id })
                callback(ConnectionDiagnosticsStore.snapshot(app))
            }
            .addOnFailureListener { error ->
                ConnectionDiagnosticsStore.recordSend(
                    app,
                    path = "node_query",
                    result = "FAILED",
                    detail = error.message ?: error.javaClass.simpleName,
                )
                callback(ConnectionDiagnosticsStore.snapshot(app))
            }
    }

    fun sendPreferred(
        context: Context,
        path: String,
        envelope: MessageEnvelope,
        preferredNodeId: String? = DeviceStore.remote(context)?.nodeId,
        callback: (Result) -> Unit = {},
    ) {
        val app = context.applicationContext
        ConnectionDiagnosticsStore.recordSend(app, path, "CHECKING_NODES")
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                ConnectionDiagnosticsStore.updateConnectedNodes(app, nodes.map { it.id })
                val node = chooseNode(nodes, preferredNodeId)
                if (node == null) {
                    val detail = "No connected Wear OS node"
                    ConnectionDiagnosticsStore.recordSend(app, path, "FAILED", detail)
                    callback(Result(false, detail))
                    return@addOnSuccessListener
                }
                Wearable.getMessageClient(app).sendMessage(node.id, path, envelope.toBytes())
                    .addOnSuccessListener { requestId ->
                        val detail = "requestId=$requestId"
                        ConnectionDiagnosticsStore.peerConnected(app, node.id)
                        ConnectionDiagnosticsStore.recordSend(app, path, "SENT", detail, node.id)
                        callback(Result(true, detail, node.id))
                    }
                    .addOnFailureListener { error ->
                        val detail = error.message ?: error.javaClass.simpleName
                        ConnectionDiagnosticsStore.recordSend(app, path, "FAILED", detail, node.id)
                        callback(Result(false, detail, node.id))
                    }
            }
            .addOnFailureListener { error ->
                val detail = error.message ?: error.javaClass.simpleName
                ConnectionDiagnosticsStore.recordSend(app, path, "FAILED", detail)
                callback(Result(false, detail))
            }
    }

    fun sendDirect(
        context: Context,
        nodeId: String,
        path: String,
        envelope: MessageEnvelope,
        callback: (Result) -> Unit = {},
    ) {
        val app = context.applicationContext
        ConnectionDiagnosticsStore.recordSend(app, path, "SENDING", nodeId = nodeId)
        Wearable.getMessageClient(app)
            .sendMessage(nodeId, path, envelope.toBytes())
            .addOnSuccessListener { requestId ->
                val detail = "requestId=$requestId"
                ConnectionDiagnosticsStore.peerConnected(app, nodeId)
                ConnectionDiagnosticsStore.recordSend(app, path, "SENT", detail, nodeId)
                callback(Result(true, detail, nodeId))
            }
            .addOnFailureListener { error ->
                val detail = error.message ?: error.javaClass.simpleName
                ConnectionDiagnosticsStore.recordSend(app, path, "FAILED", detail, nodeId)
                callback(Result(false, detail, nodeId))
            }
    }

    private fun chooseNode(nodes: List<Node>, preferredNodeId: String?): Node? {
        if (preferredNodeId != null) nodes.firstOrNull { it.id == preferredNodeId }?.let { return it }
        return nodes.sortedWith(compareByDescending<Node> { it.isNearby }.thenBy { it.displayName }).firstOrNull()
    }
}
