package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.UserInputRequestState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserInputRequestsTest {
    @Test
    fun `qualified fixture preserves wire order options free text other secret and timeout`() {
        val request = accepted("user-input-0.146.0-multiple-request.json")

        assertEquals(listOf("target", "note", "token"), request.questions.map { it.id })
        assertEquals(listOf("Spark", "Fold6"), request.questions[0].options?.map { it.label })
        assertTrue(request.questions[0].isOther)
        assertNull(request.questions[1].options)
        assertTrue(request.questions[2].isSecret)
        assertEquals(60_000L, request.autoResolutionMs)
        assertEquals(70_000L, request.deadlineAtMs)
    }

    @Test
    fun `Spark 0_146_0 live fixture preserves numeric identity and Other`() {
        val request = accepted("user-input-0.146.0-live-request.json")

        assertEquals("n:0", request.locator.requestId)
        assertEquals("thread_spark_live_0_146_0", request.thread.threadId)
        assertEquals(60_000L, request.autoResolutionMs)
        assertTrue(request.questions.single().isOther)
        assertEquals(
            listOf("Spark (Recommended)", "Fold6"),
            request.questions.single().options?.map { it.label },
        )
    }

    @Test
    fun `null timeout waits and numeric request id remains distinct`() {
        val request = accepted("user-input-null-timeout-request.json")

        assertNull(request.autoResolutionMs)
        assertNull(request.deadlineAtMs)
        assertEquals("n:42", request.locator.requestId)
    }

    @Test
    fun `duplicate ids and unrenderable options fail closed`() {
        assertInstanceOf(
            UserInputParseResult.Rejected::class.java,
            parse("user-input-duplicate-id-request.json"),
        )
        assertInstanceOf(
            UserInputParseResult.Rejected::class.java,
            parse("user-input-unrenderable-request.json"),
        )
    }

    @Test
    fun `answers use exact ids while no-answer is an empty map`() {
        val request = accepted("user-input-0.146.0-multiple-request.json")
        val response = UserInputProtocol.response(
            request,
            mapOf(
                "target" to listOf("A different host"),
                "note" to listOf("Keep it narrow"),
                "token" to listOf("secret-value"),
            ),
        )

        assertEquals(
            """{"answers":{"target":{"answers":["A different host"]},"note":{"answers":["Keep it narrow"]},"token":{"answers":["secret-value"]}}}""",
            AppServerJson.encodeToString(JsonElement.serializer(), response),
        )
        assertEquals(
            """{"answers":{}}""",
            AppServerJson.encodeToString(
                JsonElement.serializer(),
                UserInputProtocol.response(request, emptyMap()),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UserInputProtocol.response(request, mapOf("unknown" to listOf("value")))
        }
    }

    @Test
    fun `structured response accepts exactly one value per question`() {
        val request = accepted("user-input-0.146.0-multiple-request.json")
        val answers = mapOf(
            "target" to listOf("Spark", "Fold6"),
            "note" to listOf("Keep it narrow"),
            "token" to listOf("secret-value"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            UserInputProtocol.response(request, answers)
        }
    }

    @Test
    fun `secret answer never enters request state or fingerprint`() {
        val request = accepted("user-input-0.146.0-multiple-request.json")
        UserInputProtocol.response(
            request,
            mapOf(
                "target" to listOf("Spark"),
                "note" to listOf("note"),
                "token" to listOf("do-not-persist"),
            ),
        )
        val state = UserInputRequestState().receive(request, sameIdReissueQualified = false)

        assertFalse("do-not-persist" in request.fingerprint)
        assertFalse("do-not-persist" in state.toString())
    }

    @Test
    fun `reconnect replaces only a qualified matching request`() {
        val old = accepted("user-input-null-timeout-request.json", generation = 1)
        val current = accepted("user-input-null-timeout-request.json", generation = 2)
        val state = UserInputRequestState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(current, sameIdReissueQualified = true)

        assertEquals(setOf(current.locator), state.requests.keys)
    }

    @Test
    fun `unstable request is enabled only for its qualified app-server version`() {
        val qualification = mapOf(USER_INPUT_REQUEST_METHOD to setOf("0.146.0"))

        assertTrue(
            USER_INPUT_REQUEST_METHOD in supportedServerRequests("0.146.0", qualification),
        )
        assertFalse(
            USER_INPUT_REQUEST_METHOD in supportedServerRequests("0.145.0", qualification),
        )
        assertTrue(COMMAND_APPROVAL_METHOD in supportedServerRequests(null, emptyMap()))
    }

    private fun accepted(name: String, generation: Long = 7) =
        assertInstanceOf(UserInputParseResult.Accepted::class.java, parse(name, generation)).request

    private fun parse(name: String, generation: Long = 7): UserInputParseResult {
        val raw = resource(name)
        return UserInputProtocol.parse(
            hostId = "host",
            appServerGeneration = generation,
            wire = AppServerRequest(
                id = raw.getValue("id"),
                method = (raw["method"] as JsonPrimitive).content,
                params = raw.getValue("params"),
                raw = raw,
            ),
            receivedAtMs = 10_000,
        )
    }

    private fun resource(name: String): JsonObject =
        AppServerJson.parseToJsonElement(
            requireNotNull(javaClass.getResource("/app-server/v2/$name")).readText(),
        ).jsonObject
}
