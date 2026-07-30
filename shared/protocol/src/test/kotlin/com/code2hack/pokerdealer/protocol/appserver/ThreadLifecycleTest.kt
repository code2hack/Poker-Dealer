package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.ThreadWorkState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadLifecycleTest {
    @Test
    fun `qualified preflight exhausts active and archived descendants with no unrelated filter`() = runTest {
        val peer = LifecycleFixturePeer(
            listOf(
                "thread-cascade-read-request.json" to "thread-cascade-read-response.json",
                "thread-cascade-active-page-1-request.json" to "thread-cascade-active-page-1-response.json",
                "thread-cascade-active-page-2-request.json" to "thread-cascade-active-page-2-response.json",
                "thread-cascade-archived-request.json" to "thread-cascade-archived-response.json",
            ),
        )
        val session = CodexAppServerSession(peer, experimentalApi = true)
        session.initialize()

        val preflight = HostThreadLifecycle(session, descendantFilterQualified = true)
            .preflight("spark", "thr_root")

        assertTrue(peer.experimentalApi)
        assertTrue(preflight.eligible)
        assertEquals(ThreadWorkState.READY, preflight.selected.workState)
        assertEquals(false, preflight.selected.ephemeral)
        assertEquals(
            listOf("thr_child", "thr_grandchild", "thr_archived_child"),
            preflight.descendants.map { it.locator.threadId },
        )
        assertEquals(listOf(false, false, true), preflight.descendants.map { it.archived })
        assertEquals(4, peer.checkedFixtures.size)
    }

    @Test
    fun `unqualified filter fails before sending ancestorThreadId`() = runTest {
        val peer = LifecycleFixturePeer(emptyList())
        val session = CodexAppServerSession(peer)
        session.initialize()

        assertThrows(IllegalStateException::class.java) {
            runTest {
                HostThreadLifecycle(session, descendantFilterQualified = false)
                    .preflight("u4090", "thr_root")
            }
        }
        assertFalse(peer.experimentalApi)
        assertTrue(peer.checkedFixtures.isEmpty())
    }

    @Test
    fun `archive restore and delete use only their stable thread methods`() = runTest {
        val peer = LifecycleFixturePeer(
            listOf(
                "thread-archive-request.json" to "thread-archive-response.json",
                "thread-unarchive-request.json" to "thread-unarchive-response.json",
                "thread-delete-request.json" to "thread-delete-response.json",
            ),
        )
        val session = CodexAppServerSession(peer)
        session.initialize()

        session.threadArchive("thr_root")
        val restored = session.threadUnarchive("thr_root")
        session.threadDelete("thr_root")

        assertEquals("thr_root", restored["thread"]?.jsonObject?.get("id")?.let {
            (it as JsonPrimitive).contentOrNull
        })
        assertEquals(
            listOf("thread-archive-request.json", "thread-unarchive-request.json", "thread-delete-request.json"),
            peer.checkedFixtures,
        )
    }

    @Test
    fun `descendant qualification is exact version only`() {
        assertTrue(descendantFilterQualified("0.146.0", setOf("0.146.0")))
        assertFalse(descendantFilterQualified("0.145.0", setOf("0.146.0")))
        assertFalse(descendantFilterQualified(null, setOf("0.146.0")))
    }

    @Test
    fun `lifecycle notification fixtures retain authoritative locator only`() {
        val methods = listOf(
            "thread-archived-notification.json" to "thread/archived",
            "thread-unarchived-notification.json" to "thread/unarchived",
            "thread-deleted-notification.json" to "thread/deleted",
        )

        methods.forEach { (name, method) ->
            val notification = lifecycleFixture(name).jsonObject
            assertEquals(method, (notification["method"] as JsonPrimitive).contentOrNull)
            assertTrue(
                (notification["params"]?.jsonObject?.get("threadId") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.isNotBlank() == true,
            )
        }
    }
}

private class LifecycleFixturePeer(
    fixtures: List<Pair<String, String>>,
) : JsonRpcPeer {
    private val fixtures = ArrayDeque(fixtures)
    val checkedFixtures = mutableListOf<String>()
    var experimentalApi = false

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        if (method == "initialize") {
            experimentalApi = params.jsonObject["capabilities"]
                ?.jsonObject
                ?.get("experimentalApi")
                ?.toString()
                ?.toBooleanStrictOrNull() == true
            return JsonObject(emptyMap())
        }
        val (requestFixture, responseFixture) = fixtures.removeFirst()
        val expected = lifecycleFixture(requestFixture).jsonObject
        assertEquals((expected["method"] as JsonPrimitive).contentOrNull, method)
        assertEquals(expected["params"], params)
        checkedFixtures += requestFixture
        return lifecycleFixture(responseFixture).jsonObject.getValue("result")
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = null
    override suspend fun close() = Unit
}

private fun lifecycleFixture(name: String): JsonElement {
    val text = object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")
    return AppServerJson.parseToJsonElement(text)
}
