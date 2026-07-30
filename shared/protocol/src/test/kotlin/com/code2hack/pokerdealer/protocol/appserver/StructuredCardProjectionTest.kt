package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.TurnOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StructuredCardProjectionTest {
    @Test
    fun `command lifecycle preserves wire-order output and completion is authoritative`() {
        var cards = emptyList<Card>()

        cards = apply(cards, "command-output-delta-1-notification.json").cards
        assertFalse(cards.single().contentComplete)
        cards = apply(cards, "command-item-started-notification.json").cards
        cards = apply(cards, "command-output-delta-2-notification.json").cards

        val live = cards.single()
        assertEquals("printf 'one\\ntwo\\n'", live.command)
        assertEquals("/work/poker-dealer", live.workingDirectory)
        assertEquals("inProgress", live.status)
        assertEquals("one\ntwo\n", live.fullText)
        assertEquals(CardState.OPEN, live.state)
        assertTrue(live.contentComplete)

        cards = apply(cards, "command-item-completed-notification.json").cards
        val completed = cards.single()
        assertEquals("one\ntwo\n", completed.fullText)
        assertEquals(0, completed.exitCode)
        assertEquals(CardState.COMMITTED, completed.state)
        assertTrue(completed.contentComplete)
    }

    @Test
    fun `file lifecycle refreshes paths and aggregate diff without truncation`() {
        var cards = apply(emptyList(), "file-item-started-notification.json").cards
        cards = apply(cards, "file-output-delta-notification.json").cards
        assertTrue(cards.single().fullText.endsWith("streamed patch fragment\n"))
        cards = apply(cards, "file-patch-updated-notification.json").cards

        assertEquals(
            listOf("shared/domain/Card.kt", "shared/protocol/CardProjection.kt"),
            cards.single().fileChanges.map { it.path },
        )

        cards = apply(cards, "turn-diff-updated-notification.json").cards
        assertTrue(cards.single().fullText.startsWith("diff --git"))

        cards = apply(cards, "file-item-completed-notification.json").cards
        val completed = cards.single()
        assertEquals(2, completed.fileChanges.size)
        assertEquals(completed.fileChanges.joinToString("") { it.diff }, completed.fullText)
        assertEquals(CardState.COMMITTED, completed.state)
    }

    @Test
    fun `turn completion records every supported outcome`() {
        val initial = apply(emptyList(), "command-item-completed-notification.json").cards
        val fixture = fixtureText("cards-turn-completed-notification.json")

        assertEquals(TurnOutcome.COMPLETED, applyRaw(initial, fixture).cards.single().turnOutcome)
        assertEquals(
            TurnOutcome.INTERRUPTED,
            applyRaw(initial, fixture.replace("\"completed\"", "\"interrupted\"")).cards.single().turnOutcome,
        )
        assertEquals(
            TurnOutcome.FAILED,
            applyRaw(initial, fixture.replace("\"completed\"", "\"failed\"")).cards.single().turnOutcome,
        )
    }

    @Test
    fun `large deltas and unknown fields remain complete`() {
        val large = buildString { repeat(700_000) { append(('a'.code + it % 26).toChar()) } }
        val started = apply(emptyList(), "command-item-started-notification.json").cards
        val notification = AppServerNotification(
            method = "item/commandExecution/outputDelta",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive("thr_cards"),
                    "turnId" to JsonPrimitive("turn_cards"),
                    "itemId" to JsonPrimitive("cmd_1"),
                    "delta" to JsonPrimitive(large),
                    "futureField" to JsonPrimitive(true),
                ),
            ),
        )

        val card = AppServerStructuredCardProjection.apply(
            started,
            notification,
            CONVERSATION,
        ).cards.single()

        assertEquals(large, card.fullText)
        assertTrue(card.contentComplete)
    }

    @Test
    fun `late transport chunks cannot replace authoritative completion`() {
        val completed = apply(emptyList(), "command-item-completed-notification.json").cards

        val afterLateDelta = apply(completed, "command-output-delta-1-notification.json").cards
        val afterLateStart = apply(afterLateDelta, "command-item-started-notification.json").cards

        assertEquals(completed, afterLateDelta)
        assertEquals(completed, afterLateStart)
        assertEquals("one\ntwo\n", afterLateStart.single().fullText)
        assertEquals(CardState.COMMITTED, afterLateStart.single().state)
    }

    @Test
    fun `malformed structured material fails closed and requests a bounded reread`() {
        val update = applyRaw(
            emptyList(),
            """
            {
              "method": "item/started",
              "params": {
                "threadId": "thr_cards",
                "turnId": "turn_cards",
                "startedAtMs": 1,
                "item": {"id": "file_bad", "type": "fileChange", "status": "inProgress", "changes": []}
              }
            }
            """.trimIndent(),
        )

        assertTrue(update.requiresReread)
        assertFalse(update.cards.single().contentComplete)
    }

    @Test
    fun `retained cards survive restart with exact large content`(@TempDir directory: Path) = runTest {
        val locator = CodexThreadLocator("u4090", "thr_cards")
        val content = "output\n".repeat(100_000)
        val card = commandCard(content)

        RetainedCardStore(directory.toFile()).write(locator, listOf(card))
        val restored = RetainedCardStore(directory.toFile()).read(locator)

        assertEquals(listOf(card), restored)
        assertEquals(content, restored.single().fullText)
    }

    @Test
    fun `delete removes only the selected host qualified retained projection`(@TempDir directory: Path) = runTest {
        val deleted = CodexThreadLocator("u4090", "same")
        val retained = CodexThreadLocator("spark", "same")
        val store = RetainedCardStore(directory.toFile())
        store.write(deleted, listOf(commandCard("delete")))
        store.write(retained, listOf(commandCard("keep")))

        store.delete(deleted)

        assertEquals(emptyList<Card>(), store.read(deleted))
        assertEquals("keep", store.read(retained).single().fullText)
    }

    @Test
    fun `retained card storage failure is explicit`(@TempDir directory: Path) = runTest {
        val notDirectory = directory.resolve("blocked")
        Files.writeString(notDirectory, "not a directory")

        val failure = runCatching {
            RetainedCardStore(notDirectory.toFile()).write(
                CodexThreadLocator("u4090", "thr_cards"),
                listOf(commandCard("complete")),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("retained-card storage"))
    }

    @Test
    fun `corrupt derived card cache is discarded for authoritative rebuild`(
        @TempDir directory: Path,
    ) = runTest {
        val locator = CodexThreadLocator("u4090", "thr_cards")
        val store = RetainedCardStore(directory.toFile())
        store.write(locator, listOf(commandCard("complete")))
        Files.writeString(Files.list(directory).use { it.findFirst().orElseThrow() }, "{")

        val failure = runCatching { store.read(locator) }.exceptionOrNull()

        assertTrue(failure is CorruptRetainedCardCacheException)
        assertEquals(0, Files.list(directory).use { it.count() })
    }

    private fun commandCard(content: String) = Card(
        id = "cmd_1",
        conversationId = CONVERSATION,
        sequence = 1,
        revision = 3,
        role = CardRole.TOOL,
        state = CardState.COMMITTED,
        fullText = content,
        createdAtMs = 1,
        updatedAtMs = 2,
        source = CardSource.CODEX_COMMAND,
        turnId = "turn_cards",
        command = "printf output",
        workingDirectory = "/work",
        status = "completed",
        exitCode = 0,
    )

    private fun apply(cards: List<Card>, fixture: String): StructuredCardUpdate =
        applyRaw(cards, fixtureText(fixture))

    private fun applyRaw(cards: List<Card>, text: String): StructuredCardUpdate {
        val raw = AppServerJson.parseToJsonElement(text).jsonObject
        return AppServerStructuredCardProjection.apply(
            cards,
            AppServerNotification(
                method = (raw.getValue("method") as JsonPrimitive).content,
                params = raw.getValue("params"),
                raw = raw,
            ),
            CONVERSATION,
        )
    }

    private fun fixtureText(name: String): String =
        javaClass.getResource("/app-server/v2/$name")?.readText()
            ?: error("Missing fixture $name")

    companion object {
        private const val CONVERSATION = "u4090/thr_cards"
    }
}
