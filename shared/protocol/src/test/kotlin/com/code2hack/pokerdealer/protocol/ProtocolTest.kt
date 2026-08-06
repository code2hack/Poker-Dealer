package com.code2hack.pokerdealer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerEditTarget
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.ComposerSurface
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalScope
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.MorseMutationKind
import com.code2hack.pokerdealer.domain.MorseMutationOutcome
import com.code2hack.pokerdealer.domain.MorseMutationTarget
import com.code2hack.pokerdealer.domain.MorseModeTarget
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
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

    @Test
    fun `primary action target preserves semantic and stale fences`() {
        val target = PokerPrimaryActionTarget(
            locator = CodexThreadLocator("spark", "thread"),
            action = PokerPrimaryAction.STEER,
            wheelSession = "wheel-1",
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "composer-mode",
            draftRevision = 7,
            cursorPosition = 4,
            expectedTurnId = "turn-1",
            operationId = "primary-1",
        )
        val result = PokerPrimaryActionResult(target, PokerPrimaryActionOutcome.ACCEPTED)

        assertEquals(
            result,
            PokerProtocolJson.decodeFromString<PokerPrimaryActionResult>(
                PokerProtocolJson.encodeToString(result),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(expectedTurnId = null)
        }
    }

    @Test
    fun `photo transfer preserves exact bytes and target fences`() {
        val bytes = ByteArray(4_001) { (it * 31).toByte() }
        val locator = CodexThreadLocator("spark", "thread")
        val target = PhotoAssetTarget(
            locator = locator,
            sessionId = "photo-session",
            assetId = "asset-1",
            draftRevision = 4,
            cursorPosition = 2,
            controlGeneration = 8,
            connectionEpoch = 9,
            modeSession = "composer-session",
            operationId = "capture-1",
        )
        val encoded = PhotoAssetCodec.encode(bytes)
        val chunks = PhotoAssetCodec.chunks(bytes, 1_800)

        assertEquals(bytes.toList(), PhotoAssetCodec.decode(encoded).toList())
        assertEquals(bytes.toList(), chunks.flatMap(ByteArray::asList))
        assertEquals(
            "1d3007bab04fab695f6cf3c8d712ac043459e17044d8c127065fd81135e3f3e0",
            PhotoAssetCodec.sha256(bytes),
        )
        assertEquals(
            target,
            PokerProtocolJson.decodeFromString<PhotoAssetTarget>(
                PokerProtocolJson.encodeToString(target),
            ),
        )
        assertTrue(PhotoAssetCodec.dataUrl("image/jpeg", bytes).startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `Morse mutation carries only exact field target and authoritative result`() {
        val locator = CodexThreadLocator("spark", "thread")
        val target = MorseMutationTarget(
            mode = MorseModeTarget(
                locator = locator,
                surface = ComposerSurface.THREAD_COMPOSER,
                revision = 3,
                cursorPosition = 2,
                controlGeneration = 4,
                connectionEpoch = 5,
                bindingModeSession = "binding",
                modeSession = "morse",
            ),
            operationId = "morse-1",
        )
        val request = MorseMutationRequest(
            target = target,
            kind = MorseMutationKind.COMMIT_WORD,
            text = "hello ",
        )
        val result = MorseMutationResult(
            target = target,
            outcome = MorseMutationOutcome.ACKNOWLEDGED,
            composerDraft = ComposerDraft.fromText("say hello ", revision = 4),
            fieldRevision = 4,
            cursorPosition = 8,
        )

        val encodedRequest = PokerProtocolJson.encodeToString(request)
        assertTrue("dotDashBuffer" !in encodedRequest)
        assertEquals(request, PokerProtocolJson.decodeFromString<MorseMutationRequest>(encodedRequest))
        assertEquals(
            result,
            PokerProtocolJson.decodeFromString<MorseMutationResult>(
                PokerProtocolJson.encodeToString(result),
            ),
        )
    }

    @Test
    fun `Morse completion preserves target prefix and optional suffix`() {
        val target = MorseModeTarget(
            locator = CodexThreadLocator("spark", "thread"),
            surface = ComposerSurface.THREAD_COMPOSER,
            revision = 3,
            cursorPosition = 2,
            controlGeneration = 4,
            connectionEpoch = 5,
            bindingModeSession = "binding",
            modeSession = "morse",
        )
        val request = MorseCompletionRequest(target, "CA")
        val projection = MorseCompletionProjection(target, "CA", "t")

        assertEquals(
            request,
            PokerProtocolJson.decodeFromString<MorseCompletionRequest>(
                PokerProtocolJson.encodeToString(request),
            ),
        )
        assertEquals(
            projection,
            PokerProtocolJson.decodeFromString<MorseCompletionProjection>(
                PokerProtocolJson.encodeToString(projection),
            ),
        )
        assertEquals(
            projection.copy(suffix = null),
            MorseCompletionProjection(target, "CA"),
        )
    }

    @Test
    fun `approval projection preserves safe choice order and exact primary decision`() {
        val thread = CodexThreadLocator("spark", "thread")
        val request = CommandApprovalRequest(
            locator = ServerRequestLocator("spark", 4, "approval"),
            thread = thread,
            turnId = "turn",
            itemId = "item",
            approvalId = "approval-id",
            scope = CommandApprovalScope("echo hi", "/work", null, null),
            proposedExecpolicyAmendment = listOf("echo", "hi"),
            offeredDecisions = setOf(CommandApprovalDecision.ACCEPT, CommandApprovalDecision.DECLINE),
            offeredDecisionOrder = listOf(CommandApprovalDecision.DECLINE, CommandApprovalDecision.ACCEPT),
            fingerprint = "fingerprint",
            createdAtMs = 1,
        )
        val projection = request.toPokerApprovalProjection(
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "approval-mode",
            hasDealerClaim = true,
        )
        val target = PokerPrimaryActionTarget(
            locator = thread,
            action = PokerPrimaryAction.REQUEST,
            wheelSession = "wheel",
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "approval-mode",
            requestLocator = request.locator,
            approvalDecision = PokerApprovalDecision.DECLINE,
            requestFingerprint = request.fingerprint,
            operationId = "primary",
        )

        assertEquals(
            listOf(PokerApprovalDecision.DECLINE, PokerApprovalDecision.ACCEPT),
            projection.choices,
        )
        assertTrue(projection.complete)
        assertTrue(projection.actionable)
        assertEquals(
            target,
            PokerProtocolJson.decodeFromString<PokerPrimaryActionTarget>(
                PokerProtocolJson.encodeToString(target),
            ),
        )
    }

    @Test
    fun `approval projection fails closed for incomplete and destructive scope`() {
        val base = CommandApprovalRequest(
            locator = ServerRequestLocator("spark", 4, "approval"),
            thread = CodexThreadLocator("spark", "thread"),
            turnId = "turn",
            itemId = "item",
            approvalId = null,
            scope = CommandApprovalScope(null, null, "*", "https"),
            proposedExecpolicyAmendment = null,
            offeredDecisions = setOf(CommandApprovalDecision.ACCEPT),
            fingerprint = "fingerprint",
            createdAtMs = 1,
        )
        val incomplete = base.toPokerApprovalProjection(1, 1, "mode", true)
        assertTrue(incomplete.complete)
        assertTrue(!incomplete.actionable)
        assertTrue(
            !base.copy(scope = CommandApprovalScope("rm -rf /", "/work", null, null))
                .toPokerApprovalProjection(1, 1, "mode", true)
                .actionable,
        )
    }
}
