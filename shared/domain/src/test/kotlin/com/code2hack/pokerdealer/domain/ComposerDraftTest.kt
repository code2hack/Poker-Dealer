package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerDraftTest {
    private val locator = CodexThreadLocator("host", "thread")

    @Test
    fun `legacy strings round trip without losing text and photo tokens stay distinct from emoji`() {
        val legacy = ComposerDraftCodec.decodeOrLegacy("keep this draft")
        val photo = ComposerDraft(
            elements = listOf(
                ComposerElement.Text("keep "),
                ComposerElement.Photo("asset-1"),
                ComposerElement.Text(" draft"),
            ),
        )

        assertEquals("keep this draft", legacy.displayText)
        assertEquals(photo, ComposerDraftCodec.decode(ComposerDraftCodec.encode(photo)))
        assertNotEquals(
            ComposerDraft.fromText(COMPOSER_PHOTO_GLYPH),
            ComposerDraft(elements = listOf(ComposerElement.Photo("asset-1"))),
        )
    }

    @Test
    fun `unicode motion treats punctuation emoji and photo as standalone words`() {
        val draft = ComposerDraft(
            elements = listOf(
                ComposerElement.Text("hello, 😀 "),
                ComposerElement.Photo("asset-1"),
                ComposerElement.Text(" world"),
            ),
        )

        assertEquals(5, draft.nextWordStart(0))
        assertEquals(7, draft.nextWordStart(5))
        assertEquals(9, draft.nextWordStart(7))
        assertEquals(11, draft.nextWordStart(9))
        assertEquals(11, draft.previousWordStart(draft.cursorCount - 1))
        assertEquals(7, draft.previousWordStart(9))
        assertEquals(5, draft.previousWordStart(7))
    }

    @Test
    fun `delete through next boundary refuses to absorb a photo token`() {
        val draft = ComposerDraft(
            elements = listOf(
                ComposerElement.Text("hello "),
                ComposerElement.Photo("asset-1"),
                ComposerElement.Text(" world"),
            ),
        )

        assertFalse(draft.deleteThroughNextWord(0)!!.containsPhoto)
        assertTrue(draft.deleteThroughNextWord(6)!!.containsPhoto)
    }

    @Test
    fun `editor allows one optimistic text deletion and authoritative rejection reanchors at end`() {
        val original = ComposerDraft.fromText("hello, world")
        val editor = ComposerEditorState.atEnd(locator, original, 3, 8, "mode-1")
        val target = ComposerEditTarget(locator, original.revision, editor.cursorPosition, 3, 8, "mode-1", "op-1")

        val started = editor.copy(cursorPosition = 0).beginTextDeletion(target.copy(cursorPosition = 0))
        assertTrue(started is ComposerEditResult.Started)
        started as ComposerEditResult.Started
        assertEquals(", world", started.editor.draft.displayText)
        assertThrows(IllegalArgumentException::class.java) {
            started.editor.beginTextDeletion(target.copy(operationId = "op-2", cursorPosition = 0))
        }

        val authoritative = ComposerDraft(revision = 4, elements = listOf(ComposerElement.Text("remote draft")))
        val restored = started.editor.rejectOrUncertain(target.copy(cursorPosition = 0), authoritative)
        assertEquals(authoritative, restored.draft)
        assertEquals(authoritative.cursorCount - 1, restored.cursorPosition)
        assertEquals(null, restored.pendingMutation)
    }

    @Test
    fun `request panel target is rejected and stale generation cannot mutate composer`() {
        val draft = ComposerDraft.fromText("hello world")
        val editor = ComposerEditorState.atEnd(locator, draft, 2, 4, "mode-1").copy(cursorPosition = 0)
        val requestTarget = ComposerEditTarget(
            locator = locator,
            draftRevision = draft.revision,
            cursorPosition = 0,
            controlGeneration = 2,
            connectionEpoch = 4,
            modeSession = "mode-1",
            operationId = "op-request",
            surface = ComposerSurface.REQUEST_PANEL,
        )
        assertThrows(IllegalArgumentException::class.java) {
            editor.beginTextDeletion(requestTarget)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editor.beginTextDeletion(requestTarget.copy(surface = ComposerSurface.THREAD_COMPOSER, controlGeneration = 1))
        }
    }
}
