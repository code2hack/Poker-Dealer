package com.code2hack.pokerdealer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerEditTarget
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.ComposerSurface
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputAnswerEdit
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun `envelope round trips without losing protocol fields`() {
        val envelope = ProtocolEnvelope(
            type = "card.append",
            messageId = "message-1",
            sessionId = "session-1",
            sentAtMs = 1_784_600_000_000,
            epoch = 7,
            stream = "cards",
            sequence = 184,
            conversationId = "conv-17",
            payload = buildJsonObject {
                put("card_id", "card-184")
                put("text", "Stream the Codex agent-message delta.")
            },
        )

        val encoded = PokerProtocolJson.encodeToString(envelope)
        val decoded = PokerProtocolJson.decodeFromString<ProtocolEnvelope>(encoded)

        assertEquals(envelope, decoded)
        assertTrue(encoded.contains("\"protocol\":\"poker-dealer\""))
    }

    @Test
    fun `protocol offer ignores unknown optional fields`() {
        val decoded = PokerProtocolJson.decodeFromString<PokerProtocolOffer>(
            """{"major":1,"capabilities":["snapshot"],"required_capabilities":[],"future":true}""",
        )

        assertEquals(PokerProtocolOffer(capabilities = setOf("snapshot")), decoded)
    }

    @Test
    fun `chunks reassemble in index order at UTF-8 boundaries`() {
        val source = "A♠️中🙂".repeat(2_000)
        val chunks = Utf8TextChunker.chunk(source, maxUtf8Bytes = 127)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.utf8Bytes <= 127 })
        assertEquals(source, Utf8TextChunker.reassemble(chunks.reversed()))
    }

    @Test
    fun `incomplete chunk set is rejected`() {
        val chunks = Utf8TextChunker.chunk("long text".repeat(100), maxUtf8Bytes = 32)

        assertThrows(IllegalArgumentException::class.java) {
            Utf8TextChunker.reassemble(chunks.dropLast(1))
        }
    }

    @Test
    fun `composer projection and exact mutation target round trip`() {
        val locator = CodexThreadLocator("spark", "thread")
        val request = ComposerMutationRequest(
            target = ComposerEditTarget(
                locator = locator,
                draftRevision = 7,
                cursorPosition = 2,
                controlGeneration = 3,
                connectionEpoch = 4,
                modeSession = "composer-1",
                operationId = "delete-1",
                surface = ComposerSurface.THREAD_COMPOSER,
            ),
            kind = ComposerMutationKind.DELETE_THROUGH_NEXT_WORD,
        )
        val projection = ComposerDraftProjection(
            locator = locator,
            draft = ComposerDraft(
                revision = 7,
                elements = listOf(ComposerElement.Text("hello"), ComposerElement.Photo("asset")),
            ),
            controlGeneration = 3,
            connectionEpoch = 4,
        )

        assertEquals(request, PokerProtocolJson.decodeFromString<ComposerMutationRequest>(PokerProtocolJson.encodeToString(request)))
        assertEquals(projection, PokerProtocolJson.decodeFromString<ComposerDraftProjection>(PokerProtocolJson.encodeToString(projection)))
    }

    @Test
    fun `user input projection and mutation preserve request locator and plaintext buffer`() {
        val request = UserInputRequest(
            locator = ServerRequestLocator("spark", 8, "request-1"),
            thread = CodexThreadLocator("spark", "thread"),
            turnId = "turn",
            itemId = "item",
            questions = listOf(UserInputQuestion("answer", "Answer", "Answer?", null, false, true)),
            autoResolutionMs = null,
            receivedAtMs = 1,
            fingerprint = "fingerprint",
        )
        val projection = UserInputRequestProjection(
            request = request,
            buffer = UserInputAnswerBuffer()
                .edit(request, "answer", UserInputAnswerEdit.SetText("visible secret")),
            cardId = "item",
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "request-mode",
        )
        val target = UserInputAnswerMutationTarget(
            locator = request.locator,
            questionId = "answer",
            answerRevision = 1,
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "request-mode",
            operationId = "operation-1",
        )
        val mutation = UserInputAnswerMutationRequest(
            target = target,
            kind = UserInputAnswerMutationKind.SET_TEXT,
            value = "visible secret",
        )

        assertEquals(
            projection,
            PokerProtocolJson.decodeFromString<UserInputRequestProjection>(
                PokerProtocolJson.encodeToString(projection),
            ),
        )
        assertEquals(
            mutation,
            PokerProtocolJson.decodeFromString<UserInputAnswerMutationRequest>(
                PokerProtocolJson.encodeToString(mutation),
            ),
        )
    }
}
