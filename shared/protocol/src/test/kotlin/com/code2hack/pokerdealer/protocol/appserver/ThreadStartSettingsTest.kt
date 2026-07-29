package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.PermissionPreset
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ThreadStartSettingsTest {
    @Test
    fun `settings sanitize providers and exhaust exact model wire values`() = runTest {
        val peer = StartFixturePeer(
            "config-read-thread-start-request.json" to "config-read-thread-start-response.json",
            "model-list-page-1-request.json" to "model-list-page-1-response.json",
            "model-list-page-2-request.json" to "model-list-page-2-response.json",
            "config-requirements-read-request.json" to "config-requirements-read-response.json",
            "thread-start-reviewed-request.json" to "thread-start-reviewed-response.json",
        )
        val session = CodexAppServerSession(peer).also { it.initialize() }

        val catalog = HostThreadStartSettings(session).read("/work/repo")

        assertEquals("host-default", catalog.defaultProviderId)
        assertEquals(
            listOf("custom-id" to "Custom Provider", "unnamed-id" to "unnamed-id"),
            catalog.providers.map { it.id to it.label },
        )
        assertEquals(listOf("exact-wire-model", "second-wire-model"), catalog.models.map { it.model })
        assertEquals(listOf("low", "high"), catalog.models.first().reasoningEfforts)
        assertFalse(catalog.toString().contains("private.invalid"))
        assertFalse(catalog.toString().contains("DO_NOT_RETAIN"))

        session.threadStart(
            ThreadStartSelection(
                workingDirectory = "/work/repo",
                providerOverride = "custom-id",
                modelOverride = "exact-wire-model",
                reasoningEffort = "high",
                permissionPreset = PermissionPreset.ASK_ON_PHONE,
            ).validated(catalog),
        )

        assertEquals(5, peer.checkedFixtures.size)
    }

    @Test
    fun `inherited settings are omitted from thread start`() = runTest {
        val peer = StartFixturePeer(
            "thread-start-inherited-request.json" to "thread-start-reviewed-response.json",
        )
        val session = CodexAppServerSession(peer).also { it.initialize() }

        session.threadStart(ThreadStartSelection("/work/repo"))

        assertEquals(listOf("thread-start-inherited-request.json"), peer.checkedFixtures)
    }

    @Test
    fun `selected reasoning effort is sent only on the next turn start`() = runTest {
        val peer = StartFixturePeer(
            "turn-start-reasoning-request.json" to "turn-start-response.json",
        )
        val session = CodexAppServerSession(peer).also { it.initialize() }

        session.turnStart("thr_new", "first prompt", "client-new", effort = "high")

        assertEquals(listOf("turn-start-reasoning-request.json"), peer.checkedFixtures)
    }

    @Test
    fun `rename and fork use stable methods and reviewed settings`() = runTest {
        val peer = StartFixturePeer(
            "thread-name-set-request.json" to "thread-name-set-response.json",
            "thread-fork-reviewed-request.json" to "thread-fork-reviewed-response.json",
        )
        val session = CodexAppServerSession(peer).also { it.initialize() }
        val selection = ThreadStartSelection(
            workingDirectory = "/work/repo",
            providerOverride = "custom-id",
            modelOverride = "exact-wire-model",
            reasoningEffort = "high",
            permissionPreset = PermissionPreset.ASK_ON_PHONE,
        )

        session.threadNameSet("thr_source", "Shared name")
        val response = session.threadFork("thr_source", selection)

        assertEquals("thr_fork", response["thread"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("thread-name-set-request.json", "thread-fork-reviewed-request.json"),
            peer.checkedFixtures,
        )
    }

    @Test
    fun `fork rejects silent server fallback`() {
        val peer = StartFixturePeer(
            "thread-fork-reviewed-request.json" to "thread-start-mismatched-response.json",
        )
        val session = CodexAppServerSession(peer)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                session.initialize()
                session.threadFork(
                    "thr_source",
                    ThreadStartSelection(
                        "/work/repo",
                        "custom-id",
                        "exact-wire-model",
                        permissionPreset = PermissionPreset.ASK_ON_PHONE,
                    ),
                )
            }
        }
    }

    @Test
    fun `explicit settings never accept silent server fallback`() {
        val peer = StartFixturePeer(
            "thread-start-reviewed-request.json" to "thread-start-mismatched-response.json",
        )
        val session = CodexAppServerSession(peer)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                session.initialize()
                session.threadStart(
                    ThreadStartSelection(
                        "/work/repo",
                        "custom-id",
                        "exact-wire-model",
                        permissionPreset = PermissionPreset.ASK_ON_PHONE,
                    ),
                )
            }
        }
    }
}

private class StartFixturePeer(
    vararg fixtures: Pair<String, String>,
) : JsonRpcPeer {
    private val fixtures = ArrayDeque(fixtures.toList())
    val checkedFixtures = mutableListOf<String>()

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        if (method == "initialize") return JsonObject(emptyMap())
        val (requestFixture, responseFixture) = fixtures.removeFirst()
        val expected = startFixture(requestFixture).jsonObject
        assertEquals((expected["method"] as JsonPrimitive).contentOrNull, method)
        assertEquals(expected["params"], params)
        checkedFixtures += requestFixture
        return startFixture(responseFixture).jsonObject.getValue("result")
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = null
    override suspend fun close() = Unit
}

private fun startFixture(name: String): JsonElement {
    val text = object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")
    return AppServerJson.parseToJsonElement(text)
}
