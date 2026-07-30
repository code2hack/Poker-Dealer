package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ThreadCascadePreflight
import com.code2hack.pokerdealer.domain.ThreadWorkState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HostThreadLifecycle(
    private val appServer: CodexAppServerSession,
    private val descendantFilterQualified: Boolean,
) {
    suspend fun preflight(
        hostId: String,
        threadId: String,
        selectedArchived: Boolean = false,
    ): ThreadCascadePreflight {
        check(descendantFilterQualified) {
            "Archive/Delete unavailable: descendant filtering is not qualified for this host/app-server version"
        }
        val selected = appServer.threadReadMetadata(threadId)
            .threadObject()
            .toLifecycleThread(hostId, selectedArchived)
        require(selected.locator.threadId == threadId) { "thread/read returned a different thread" }
        val descendants = buildList {
            addAll(descendantPages(hostId, threadId, archived = false))
            addAll(descendantPages(hostId, threadId, archived = true))
        }.distinctBy(DiscoveredThread::locator)
        return ThreadCascadePreflight(selected, descendants)
    }

    private suspend fun descendantPages(
        hostId: String,
        ancestorThreadId: String,
        archived: Boolean,
    ): List<DiscoveredThread> = buildList {
        var cursor: String? = null
        do {
            val page = appServer.threadCascadeList(ancestorThreadId, archived, cursor)
            page.threadData().forEach {
                add(it.toLifecycleThread(hostId, archived))
            }
            cursor = page.lifecycleString("nextCursor")
        } while (cursor != null)
    }
}

private fun JsonObject.threadObject(): JsonObject =
    this["thread"] as? JsonObject ?: error("thread/read response did not include a thread")

private fun JsonObject.threadData(): List<JsonObject> =
    ((this["data"] ?: this["threads"]) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.toLifecycleThread(hostId: String, archived: Boolean): DiscoveredThread {
    val threadId = lifecycleString("id") ?: error("Thread metadata did not include an ID")
    val status = lifecycleStatus()
    return DiscoveredThread(
        locator = CodexThreadLocator(hostId, threadId),
        name = lifecycleString("name"),
        preview = lifecycleString("preview"),
        workingDirectory = lifecycleString("cwd"),
        updatedAtSeconds = lifecycleLong("updatedAt"),
        status = status,
        archived = archived,
        ephemeral = lifecycleBoolean("ephemeral"),
        workState = when (status) {
            "active" -> ThreadWorkState.BUSY
            "idle", "notLoaded" -> ThreadWorkState.READY
            else -> null
        },
    )
}

private fun JsonObject.lifecycleString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.lifecycleLong(name: String): Long? =
    lifecycleString(name)?.toLongOrNull()

private fun JsonObject.lifecycleBoolean(name: String): Boolean? =
    lifecycleString(name)?.toBooleanStrictOrNull()

private fun JsonObject.lifecycleStatus(): String? = when (val value = this["status"]) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> value.lifecycleString("type")
    else -> null
}
