package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadDiscoveryTest {
    @Test
    fun `discovery exhausts active and archived pages and enriches host-qualified rows`() = runTest {
        val peer = discoveryPeer()
        val session = CodexAppServerSession(peer)
        session.initialize()

        val rows = HostThreadDiscovery(session).discover("u4090") { locator ->
            if (locator.threadId == "thr_shared") {
                ThreadDiscoveryLocalState(
                    attached = true,
                    unreadCount = 2,
                    intendedControlSurface = ControlSurface.DEALER,
                )
            } else {
                ThreadDiscoveryLocalState()
            }
        }

        assertEquals(listOf("thr_shared", "thr_second", "thr_archived"), rows.map { it.locator.threadId })
        assertEquals(listOf(false, false, true), rows.map { it.archived })
        assertTrue(rows.first().loaded)
        assertTrue(rows.first().attached)
        assertEquals(2, rows.first().unreadCount)
        assertEquals(ControlSurface.DEALER, rows.first().intendedControlSurface)
        assertEquals("active", rows.first().status)
        assertEquals("Named thread", rows.first().name)
        assertEquals(
            listOf(
                "thread-loaded-list-request.json",
                "thread-discovery-active-page-1-request.json",
                "thread-discovery-active-page-2-request.json",
                "thread-discovery-archived-request.json",
            ),
            peer.checkedFixtures,
        )
    }

    @Test
    fun `loaded-list absence degrades without closing discovery connection`() = runTest {
        val peer = discoveryPeer(loadedListMissing = true)
        val session = CodexAppServerSession(peer)
        session.initialize()

        val rows = HostThreadDiscovery(session).discover("u4090")

        assertFalse(peer.closed)
        assertTrue(rows.none { it.loaded })
        assertEquals(3, rows.size)
    }

    @Test
    fun `equal thread IDs on different hosts remain distinct`() = runTest {
        suspend fun discover(hostId: String) = CodexAppServerSession(discoveryPeer()).let { session ->
            session.initialize()
            HostThreadDiscovery(session).discover(hostId)
        }

        val shared = (discover("spark") + discover("u4090"))
            .filter { it.locator.threadId == "thr_shared" }
            .map { it.locator }
            .toSet()

        assertEquals(
            setOf(
                CodexThreadLocator("spark", "thr_shared"),
                CodexThreadLocator("u4090", "thr_shared"),
            ),
            shared,
        )
    }
}

private fun discoveryPeer(loadedListMissing: Boolean = false): DiscoveryFixturePeer =
    DiscoveryFixturePeer(
        ArrayDeque(
            listOf(
                "thread-loaded-list-request.json" to "thread-loaded-list-response.json",
                "thread-discovery-active-page-1-request.json" to
                    "thread-discovery-active-page-1-response.json",
                "thread-discovery-active-page-2-request.json" to
                    "thread-discovery-active-page-2-response.json",
                "thread-discovery-archived-request.json" to
                    "thread-discovery-archived-response.json",
            ),
        ),
        loadedListMissing,
    )

private class DiscoveryFixturePeer(
    private val fixtures: ArrayDeque<Pair<String, String>>,
    private val loadedListMissing: Boolean,
) : JsonRpcPeer {
    val checkedFixtures = mutableListOf<String>()
    var closed = false

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        if (method == "initialize") return JsonObject(emptyMap())
        if (loadedListMissing && method == "thread/loaded/list") {
            fixtures.removeFirst()
            throw JsonRpcRemoteException(
                method,
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32601),
                        "message" to JsonPrimitive("Method not found"),
                    ),
                ),
            )
        }
        val (requestFixture, responseFixture) = fixtures.removeFirst()
        val expected = fixture(requestFixture).jsonObject
        assertEquals((expected["method"] as JsonPrimitive).contentOrNull, method)
        assertEquals(expected["params"], params)
        checkedFixtures += requestFixture
        return fixture(responseFixture).jsonObject.getValue("result")
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = null
    override suspend fun close() {
        closed = true
    }
}

private fun fixture(name: String): JsonElement {
    val text = object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")
    return AppServerJson.parseToJsonElement(text)
}
