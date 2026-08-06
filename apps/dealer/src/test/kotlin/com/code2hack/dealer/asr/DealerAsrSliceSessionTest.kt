package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.protocol.PokerAsrAudioFrame
import com.code2hack.pokerdealer.protocol.PokerAsrPackSelection
import com.code2hack.pokerdealer.protocol.PokerAsrTarget
import com.code2hack.pokerdealer.protocol.PokerAsrTargetField
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrSliceSessionTest {
    @Test
    fun `process session prefers native punctuation and closes the recognizer`() = runBlocking {
        val fake = FakeRecognizer("native?")
        val process = DealerAsrProcessSession(fake, profile("."))

        assertEquals("native?", process.commitSlice())
        fake.text = "fallback"
        assertEquals("fallback.", process.commitSlice())
        assertEquals("", "".withPausePunctuation("."))
        assertEquals("already!", "already!".withPausePunctuation("."))
        assertEquals("plain", "plain".withPausePunctuation(""))
        process.close()

        assertEquals(2, fake.commits)
        assertEquals(1, fake.closed)
    }

    @Test
    fun `slice session enforces PCM session offsets and coalesces provisional projections`() {
        var now = 0L
        val process = DealerAsrProcessSession(FakeRecognizer("draft"), profile("."))
        val session = DealerAsrSliceSession(
            sessionId = "session",
            target = target(PokerAsrTargetField.COMPOSER),
            pack = pack,
            recognizer = process,
            nowMs = { now },
        )

        assertNull(session.accept(frame("session", 0, byteArrayOf(1, 0, 2, 0))))
        assertEquals(2L, session.nextSampleOffset)
        assertEquals("audio-session-invalid", session.accept(frame("other", 2, byteArrayOf(3, 0))))
        assertEquals("audio-sequence-invalid", session.accept(frame("session", 4, byteArrayOf(3, 0))))
        assertEquals("audio-frame-invalid", session.accept(frame("session", 2, byteArrayOf(3))))
        assertThrows(IllegalStateException::class.java) { session.commitSlice(fenceSampleOffset = 1) }

        assertEquals(2L, checkNotNull(session.projection(immediate = true)).sampleOffset)
        assertNull(session.projection(immediate = false))
        now = 100L
        assertEquals(2L, checkNotNull(session.projection(immediate = false)).sampleOffset)
    }

    @Test
    fun `commit fence starts a new slice and deliberate close discards only its uncommitted text`() = runBlocking {
        val fake = FakeRecognizer("committed")
        val session = DealerAsrSliceSession(
            sessionId = "session",
            target = target(PokerAsrTargetField.REQUEST_TEXT),
            pack = pack,
            recognizer = DealerAsrProcessSession(fake, profile("")),
        )

        assertNull(session.accept(frame("session", 0, byteArrayOf(1, 0))))
        assertEquals("committed", session.commitSlice(fenceSampleOffset = 1))
        assertEquals(1L, session.sliceRevision)
        assertEquals(1L, session.nextSampleOffset)
        assertEquals(1, fake.commits)

        fake.text = "uncommitted"
        assertNull(session.accept(frame("session", 1, byteArrayOf(2, 0))))
        session.close()

        assertEquals(1, fake.discards)
        assertEquals(1, fake.closed)
        assertTrue(fake.committedTexts.single() == "committed")
    }

    private fun target(field: PokerAsrTargetField) = PokerAsrTarget(
        locator = CodexThreadLocator("spark", "thread"),
        field = field,
        requestLocator = if (field == PokerAsrTargetField.REQUEST_TEXT) {
            com.code2hack.pokerdealer.domain.ServerRequestLocator("spark", 1, "request")
        } else {
            null
        },
        questionId = if (field == PokerAsrTargetField.REQUEST_TEXT) "question" else null,
        targetRevision = 0,
        cursorPosition = 0,
        controlGeneration = 1,
        connectionEpoch = 2,
        modeSession = "mode",
    )

    private fun frame(sessionId: String, offset: Long, bytes: ByteArray) = PokerAsrAudioFrame(
        sessionId = sessionId,
        firstSampleOffset = offset,
        pcm16Base64 = Base64.getEncoder().encodeToString(bytes),
    )

    private fun profile(punctuation: String) = DealerAsrProfile(
        packId = pack.packId,
        revision = pack.revision,
        schemaVersion = 1,
        settings = JsonObject(
            mapOf("pausePunctuation" to JsonPrimitive(punctuation)),
        ),
    )

    private class FakeRecognizer(var text: String) : DealerAsrRecognizer {
        var commits = 0
        var discards = 0
        var closed = 0
        val committedTexts = mutableListOf<String>()

        override fun acceptPcm16(pcm: ByteArray) = Unit

        override fun provisionalText(): String = text

        override fun commitSlice(): String {
            commits++
            committedTexts += text
            val committed = text
            text = ""
            return committed
        }

        override fun discardSlice() {
            discards++
            text = ""
        }

        override fun close() {
            closed++
        }
    }

    private companion object {
        val pack = PokerAsrPackSelection(
            packId = "parakeet",
            revision = "r1",
            profile = JsonObject(emptyMap()),
        )
    }
}
