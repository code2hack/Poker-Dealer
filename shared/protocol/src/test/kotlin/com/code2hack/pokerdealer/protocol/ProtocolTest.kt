package com.code2hack.pokerdealer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun `envelope round trips without losing protocol fields`() {
        val envelope = ProtocolEnvelope(
            type = "card.append",
            messageId = "message-1",
            sessionId = "session-1",
            sentAtMs = 1_784_600_000_000,
            sequence = 184,
            conversationId = "conv-17",
            payload = buildJsonObject {
                put("card_id", "card-184")
                put("text", "Use tmux control mode.")
            },
        )

        val encoded = PokerProtocolJson.encodeToString(envelope)
        val decoded = PokerProtocolJson.decodeFromString<ProtocolEnvelope>(encoded)

        assertEquals(envelope, decoded)
        assertTrue(encoded.contains("\"protocol\":\"poker-dealer\""))
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
}
