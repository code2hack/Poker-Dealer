package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ThreadWorkState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ThreadDiscoveryLocalState(
    val attached: Boolean = false,
    val unreadCount: Int = 0,
    val intendedControlSurface: ControlSurface = ControlSurface.NONE,
)

class HostThreadDiscovery(
    private val appServer: CodexAppServerSession,
) {
    suspend fun discover(
        hostId: String,
        localState: (CodexThreadLocator) -> ThreadDiscoveryLocalState = { ThreadDiscoveryLocalState() },
    ): List<DiscoveredThread> {
        val loaded = appServer.threadLoadedListOrNull().loadedThreadIds()
        return buildList {
            addAll(pages(hostId, archived = false, loaded, localState))
            addAll(pages(hostId, archived = true, loaded, localState))
        }.distinctBy(DiscoveredThread::locator)
    }

    private suspend fun pages(
        hostId: String,
        archived: Boolean,
        loaded: Set<String>,
        localState: (CodexThreadLocator) -> ThreadDiscoveryLocalState,
    ): List<DiscoveredThread> = buildList {
        var cursor: String? = null
        do {
            val page = appServer.threadDiscoveryList(archived, cursor)
            page.data().forEach { thread ->
                if (thread.string("source") !in USER_THREAD_SOURCES) return@forEach
                val threadId = thread.string("id") ?: return@forEach
                val locator = CodexThreadLocator(hostId, threadId)
                val local = localState(locator)
                val status = thread.status()
                add(
                    DiscoveredThread(
                        locator = locator,
                        name = thread.string("name"),
                        preview = thread.string("preview"),
                        workingDirectory = thread.string("cwd"),
                        updatedAtSeconds = thread.long("updatedAt"),
                        status = status,
                        archived = archived,
                        loaded = threadId in loaded,
                        workState = when (status) {
                            "active" -> ThreadWorkState.BUSY
                            "idle" -> ThreadWorkState.READY
                            else -> null
                        },
                        attached = local.attached,
                        unreadCount = local.unreadCount,
                        intendedControlSurface = local.intendedControlSurface,
                    ),
                )
            }
            cursor = page.string("nextCursor")
        } while (cursor != null)
    }

    private companion object {
        val USER_THREAD_SOURCES = setOf("cli", "vscode", "appServer")
    }
}

private fun JsonObject.data(): List<JsonObject> =
    ((this["data"] ?: this["threads"]) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject?.loadedThreadIds(): Set<String> =
    this?.data().orEmpty().mapNotNull { it.string("id") }.toSet()

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
private fun JsonObject.status(): String? = when (val value = this["status"]) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> value.string("type")
    else -> null
}
