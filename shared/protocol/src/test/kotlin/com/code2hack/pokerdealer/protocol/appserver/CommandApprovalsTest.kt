package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandApprovalsTest {
    @Test
    fun `available decisions are intersected and amendment needs exact proposal choice`() {
        val parsed = accepted("command-approval-available-decisions-request.json")

        assertEquals(
            setOf(CommandApprovalDecision.ACCEPT, CommandApprovalDecision.DECLINE),
            parsed.offeredDecisions,
        )
        assertEquals("git status --short", parsed.scope.command)
        assertEquals("/work/repo", parsed.scope.workingDirectory)
        assertFalse(CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT in parsed.offeredDecisions)
    }

    @Test
    fun `network-only scope is complete and never offers network amendments`() {
        val parsed = accepted("command-approval-network-only-request.json")

        assertEquals(null, parsed.scope.command)
        assertEquals("example.test", parsed.scope.networkHost)
        assertEquals("https", parsed.scope.networkProtocol)
        assertTrue(CommandApprovalDecision.ACCEPT in parsed.offeredDecisions)
        assertFalse(CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT in parsed.offeredDecisions)
    }

    @Test
    fun `approval id distinguishes callbacks sharing one item`() {
        val first = accepted("command-approval-callback-a-request.json")
        val second = accepted("command-approval-callback-b-request.json")

        assertEquals(first.itemId, second.itemId)
        assertTrue(first.fingerprint != second.fingerprint)
        assertTrue(first.locator != second.locator)
    }

    @Test
    fun `incomplete scope and no safe choice fail closed`() {
        assertInstanceOf(
            CommandApprovalParseResult.Rejected::class.java,
            parse("command-approval-incomplete-request.json"),
        )
        assertInstanceOf(
            CommandApprovalParseResult.Rejected::class.java,
            parse("command-approval-no-safe-choice-request.json"),
        )
    }

    @Test
    fun `response uses exact protocol union and proposal`() {
        val parsed = accepted("command-approval-callback-a-request.json").copy(
            proposedExecpolicyAmendment = listOf("printf", "a"),
            offeredDecisions = setOf(CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT),
        )
        val response = CommandApprovalProtocol.response(
            parsed,
            CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT,
        )

        assertEquals(
            """{"decision":{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["printf","a"]}}}""",
            AppServerJson.encodeToString(JsonElement.serializer(), response),
        )
    }

    @Test
    fun `reissue fixture only reconciles across a qualified generation`() {
        val raw = resource("command-approval-reissued-request.json")
        fun parse(generation: Long) = assertInstanceOf(
            CommandApprovalParseResult.Accepted::class.java,
            CommandApprovalProtocol.parse(
                "host",
                generation,
                AppServerRequest(
                    raw.getValue("id"),
                    (raw["method"] as JsonPrimitive).content,
                    raw.getValue("params"),
                    raw,
                ),
            ),
        ).request
        val old = parse(1)
        val current = parse(2)

        val state = CommandApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(current, sameIdReissueQualified = true)

        assertEquals(setOf(current.locator), state.requests.keys)
    }

    @Test
    fun `resolved notification retains string request identity`() {
        val raw = resource("server-request-resolved-notification.json")
        val notification = AppServerNotification(
            method = (raw["method"] as JsonPrimitive).content,
            params = raw.getValue("params"),
            raw = raw,
        )

        assertEquals(
            ResolvedServerRequest("s:approval-1", "thr-1"),
            CommandApprovalProtocol.resolved(notification),
        )
    }

    private fun accepted(name: String) =
        assertInstanceOf(CommandApprovalParseResult.Accepted::class.java, parse(name)).request

    private fun parse(name: String): CommandApprovalParseResult {
        val raw = resource(name)
        return CommandApprovalProtocol.parse(
            hostId = "host",
            appServerGeneration = 7,
            wire = AppServerRequest(
                id = raw.getValue("id"),
                method = (raw["method"] as JsonPrimitive).content,
                params = raw.getValue("params"),
                raw = raw,
            ),
        )
    }

    private fun resource(name: String): JsonObject =
        AppServerJson.parseToJsonElement(
            requireNotNull(javaClass.getResource("/app-server/v2/$name")).readText(),
        ).jsonObject
}
