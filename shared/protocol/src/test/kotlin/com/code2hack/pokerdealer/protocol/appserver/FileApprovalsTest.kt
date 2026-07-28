package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.FileChangeContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class FileApprovalsTest {
    @Test
    fun `request is actionable only with complete retained paths and diff`() {
        val parsed = accepted("file-approval-request.json", completeCard())

        assertEquals("Allow the reviewed changes", parsed.reason)
        assertEquals("/work/repo", parsed.grantRoot)
        assertEquals(listOf("src/File.kt"), parsed.fileChanges.map(FileChangeContent::path))
        assertEquals(true, parsed.reviewComplete)
    }

    @Test
    fun `incomplete diff remains non-actionable for bounded recovery`() {
        assertInstanceOf(
            FileApprovalParseResult.Incomplete::class.java,
            parse("file-approval-incomplete-request.json", null),
        )
        assertInstanceOf(
            FileApprovalParseResult.Incomplete::class.java,
            parse("file-approval-request.json", completeCard().copy(contentComplete = false)),
        )
    }

    @Test
    fun `every proven decision uses its exact response union`() {
        mapOf(
            FileApprovalDecision.ACCEPT to "file-approval-accept-response.json",
            FileApprovalDecision.ACCEPT_FOR_SESSION to "file-approval-accept-for-session-response.json",
            FileApprovalDecision.DECLINE to "file-approval-decline-response.json",
            FileApprovalDecision.CANCEL to "file-approval-cancel-response.json",
        ).forEach { (decision, fixture) ->
            assertEquals(
                resource(fixture),
                FileApprovalProtocol.response(decision),
            )
        }
    }

    @Test
    fun `qualified reconnect matches only the same reviewed scope`() {
        val raw = resource("file-approval-reissued-request.json")
        fun request(generation: Long, card: Card) = assertInstanceOf(
            FileApprovalParseResult.Accepted::class.java,
            FileApprovalProtocol.parse(
                "host",
                generation,
                AppServerRequest(
                    raw.getValue("id"),
                    (raw["method"] as JsonPrimitive).content,
                    raw.getValue("params"),
                    raw,
                ),
                card,
            ),
        ).request
        val old = request(1, completeCard())
        val current = request(2, completeCard())
        val changed = request(
            2,
            completeCard().copy(
                fileChanges = listOf(FileChangeContent("other.kt", "update", "+other")),
                fullText = "+other",
            ),
        )

        val reconciled = FileApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(current, sameIdReissueQualified = true)
        val differentScope = FileApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(changed, sameIdReissueQualified = true)

        assertEquals(setOf(current.locator), reconciled.requests.keys)
        assertEquals(2, differentScope.requests.size)
    }

    private fun accepted(name: String, card: Card) =
        assertInstanceOf(FileApprovalParseResult.Accepted::class.java, parse(name, card)).request

    private fun parse(name: String, card: Card?): FileApprovalParseResult {
        val raw = resource(name)
        return FileApprovalProtocol.parse(
            hostId = "host",
            appServerGeneration = 7,
            wire = AppServerRequest(
                id = raw.getValue("id"),
                method = (raw["method"] as JsonPrimitive).content,
                params = raw.getValue("params"),
                raw = raw,
            ),
            reviewCard = card,
        )
    }

    private fun completeCard() = Card(
        id = "file-item",
        conversationId = "host/thr-file",
        sequence = 1,
        revision = 1,
        role = CardRole.TOOL,
        state = CardState.OPEN,
        fullText = "-old\n+new\n",
        createdAtMs = 1,
        updatedAtMs = 1,
        source = CardSource.CODEX_FILE_CHANGE,
        turnId = "turn-file",
        status = "inProgress",
        fileChanges = listOf(FileChangeContent("src/File.kt", "update", "-old\n+new\n")),
        contentComplete = true,
    )

    private fun resource(name: String): JsonObject =
        AppServerJson.parseToJsonElement(
            requireNotNull(javaClass.getResource("/app-server/v2/$name")).readText(),
        ).jsonObject
}
