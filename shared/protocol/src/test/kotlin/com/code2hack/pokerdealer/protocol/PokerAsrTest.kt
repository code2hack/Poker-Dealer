package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerAsrTest {
    private val target = PokerAsrTarget(
        locator = CodexThreadLocator("spark", "thread"),
        field = PokerAsrTargetField.COMPOSER,
        targetRevision = 3,
        cursorPosition = 2,
        controlGeneration = 4,
        connectionEpoch = 5,
        modeSession = "mode",
    )

    @Test
    fun `audio decodes bounded complete pcm samples`() {
        val bytes = byteArrayOf(1, 0, -2, 127)
        val frame = PokerAsrAudioFrame(
            sessionId = "session",
            firstSampleOffset = 0,
            pcm16Base64 = Base64.getEncoder().encodeToString(bytes),
        )

        assertArrayEquals(bytes, frame.decodePcm16())
    }

    @Test
    fun `audio rejects odd and oversized payloads`() {
        val odd = PokerAsrAudioFrame("session", 0, Base64.getEncoder().encodeToString(byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { odd.decodePcm16() }

        val oversized = PokerAsrAudioFrame(
            "session",
            0,
            Base64.getEncoder().encodeToString(ByteArray(POKER_ASR_MAX_AUDIO_BYTES + 2)),
        )
        assertThrows(IllegalArgumentException::class.java) { oversized.decodePcm16() }
    }

    @Test
    fun `request target requires request and question`() {
        assertThrows(IllegalArgumentException::class.java) {
            PokerAsrTarget(
                locator = target.locator,
                field = PokerAsrTargetField.REQUEST_TEXT,
                targetRevision = 0,
                cursorPosition = 0,
                controlGeneration = 0,
                connectionEpoch = 0,
                modeSession = "mode",
            )
        }
        assertTrue(PokerProtocolJson.encodeToString(
            PokerAsrProjection.serializer(),
            PokerAsrProjection(target, "session", 0, "hello", 4),
        ).contains("slice_text"))
    }

    @Test
    fun `last committed discard carries an exact revision delete range`() {
        val request = PokerAsrDiscardRequest(
            target = target,
            sessionId = "session",
            operationId = "operation",
            fenceSampleOffset = 4,
            kind = PokerAsrDiscardKind.LAST_COMMITTED_SLICE,
            deleteStart = 2,
            deleteEndExclusive = 5,
            expectedText = "ok.",
        )
        val encoded = PokerProtocolJson.encodeToString(PokerAsrDiscardRequest.serializer(), request)
        assertTrue(encoded.contains("delete_start"))
        assertThrows(IllegalArgumentException::class.java) {
            PokerAsrDiscardRequest(
                target = target,
                sessionId = "session",
                operationId = "operation",
                kind = PokerAsrDiscardKind.CURRENT_SLICE,
                deleteStart = 2,
            )
        }
    }
}
