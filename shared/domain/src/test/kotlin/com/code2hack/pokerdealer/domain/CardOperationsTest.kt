package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardOperationsTest {
    @Test
    fun `stale card revisions never replace newer content`() {
        val store = CardRevisionStore()
        val revisionTwo = card(revision = 2, text = "complete answer")
        val revisionOne = card(revision = 1, text = "partial")

        assertEquals(RevisionApplication.INSERTED, store.apply(revisionTwo))
        assertEquals(RevisionApplication.IGNORED_STALE, store.apply(revisionOne))
        assertEquals("complete answer", store.get(revisionTwo.id)?.fullText)
    }

    @Test
    fun `oversized cards split without data loss`() {
        val text = buildString {
            repeat(6_000) { index -> append("line-").append(index).append(" ♠ 中🙂\n") }
        }

        val parts = splitCardTextAtNewlines(text, maxUtf8Bytes = 4_096)

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray().size <= 4_096 })
        assertEquals(text, parts.joinToString(separator = ""))
    }

    private fun card(revision: Long, text: String) = Card(
        id = "card-1",
        conversationId = "conv-1",
        sequence = 1,
        revision = revision,
        role = CardRole.AGENT,
        state = CardState.OPEN,
        fullText = text,
        createdAtMs = 1,
        updatedAtMs = revision,
        source = CardSource.TMUX_OUTPUT,
    )
}
