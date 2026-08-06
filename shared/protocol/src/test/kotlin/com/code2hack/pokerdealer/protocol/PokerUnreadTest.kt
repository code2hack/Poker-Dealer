package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerUnreadTest {
    private val locator = CodexThreadLocator("spark", "thread")

    @Test
    fun `baseline excludes human cards and later revisions`() {
        val tracker = PokerUnreadTracker()
        val baseline = snapshot(
            cards = listOf(
                card("human", CardSource.CODEX_USER_MESSAGE, "prompt"),
                card("agent", CardSource.CODEX_AGENT_MESSAGE, "reply"),
                card("plan", CardSource.CODEX_PLAN, "plan"),
                card("placeholder", CardSource.CODEX_COMMAND, "", complete = false),
            ),
        )

        assertEquals(0, tracker.installSnapshot(baseline).state.unreadCount)

        val next = snapshot(
            revision = 2,
            cards = listOf(
                card("human", CardSource.CODEX_USER_MESSAGE, "changed", sequence = 2),
                card("agent", CardSource.CODEX_AGENT_MESSAGE, "reply plus", revision = 2),
                card("plan", CardSource.CODEX_PLAN, "plan changed", revision = 2),
                card("placeholder", CardSource.CODEX_COMMAND, "command output", revision = 2),
                card("error", CardSource.SYSTEM, "visible error", sequence = 3),
            ),
        )
        val update = tracker.installSnapshot(next)

        assertTrue(update.shouldForeground)
        assertEquals(2, update.state.unreadCount)
        assertEquals(2, tracker.installSnapshot(next).state.unreadCount)
        assertFalse(tracker.installSnapshot(next).shouldForeground)
    }

    @Test
    fun `a card clears only after its final line is visible`() {
        val tracker = PokerUnreadTracker()
        tracker.installSnapshot(snapshot())
        tracker.installSnapshot(
            snapshot(
                revision = 2,
                cards = listOf(card("new", CardSource.CODEX_AGENT_MESSAGE, "answer")),
            ),
        )

        tracker.markCardRead(locator, "new", finalized = false, finalLineVisible = true)
        assertEquals(1, tracker.state.unreadCount)
        tracker.markCardRead(locator, "new", finalized = true, finalLineVisible = false)
        assertEquals(1, tracker.state.unreadCount)
        tracker.markCardRead(locator, "new", finalized = true, finalLineVisible = true)
        assertEquals(0, tracker.state.unreadCount)
    }

    @Test
    fun `one request key counts once regardless of repeated projections`() {
        val tracker = PokerUnreadTracker()
        assertFalse(tracker.observeRequest(locator, "user-input:before-snapshot").shouldForeground)
        assertFalse(tracker.state.baselineEstablished)
        tracker.installSnapshot(snapshot())

        val first = tracker.observeRequest(locator, "user-input:req-1")
        assertTrue(first.shouldForeground)
        assertEquals(1, first.state.unreadCount)
        assertFalse(tracker.observeRequest(locator, "user-input:req-1").shouldForeground)

        tracker.markRequestRead(
            locator,
            "user-input:req-1",
            finalized = true,
            finalLineVisible = false,
        )
        assertEquals(1, tracker.state.unreadCount)
        tracker.markRequestRead(locator, "user-input:req-1", finalized = true, finalLineVisible = true)
        assertEquals(0, tracker.state.unreadCount)
    }

    @Test
    fun `reconnect watermarks suppress stale new ids and detach clears pile unread`() {
        val tracker = PokerUnreadTracker()
        tracker.installSnapshot(snapshot(cards = listOf(card("old", CardSource.CODEX_AGENT_MESSAGE, "old"))))
        assertEquals(
            0,
            tracker.installSnapshot(
                snapshot(
                    revision = 2,
                    cards = listOf(
                        card("old", CardSource.CODEX_AGENT_MESSAGE, "old"),
                        card("stale", CardSource.CODEX_AGENT_MESSAGE, "stale"),
                    ),
                ),
            ).state.unreadCount,
        )
        assertEquals(
            1,
            tracker.installSnapshot(
                snapshot(
                    revision = 3,
                    cards = listOf(
                        card("old", CardSource.CODEX_AGENT_MESSAGE, "old"),
                        card("stale", CardSource.CODEX_AGENT_MESSAGE, "stale"),
                        card(
                            "new",
                            CardSource.CODEX_AGENT_MESSAGE,
                            "new",
                            sequence = 2,
                        ),
                    ),
                ),
            ).state.unreadCount,
        )

        val detached = tracker.installSnapshot(
            PokerSnapshot(
                revision = 4,
                projection = PokerSnapshotProjection(),
                piles = emptyList(),
            ),
        )
        assertEquals(0, detached.state.unreadCount)
    }

    @Test
    fun `corrupt unread state becomes a fresh file`() {
        val directory = Files.createTempDirectory("poker-unread").toFile()
        val file = directory.resolve("unread.json")
        file.writeText("not-json")
        val store = FilePokerUnreadStore(file)

        assertEquals(0, store.load("pairing-a").unreadCount)
        store.save(
            "pairing-a",
            PokerUnreadState(baselineEstablished = true),
        )
        assertTrue(file.exists())
        assertEquals(0, store.load("pairing-b").unreadCount)
    }

    private fun snapshot(
        revision: Long = 1,
        cards: List<Card> = emptyList(),
        requestCards: List<PokerSnapshotRequestCard> = emptyList(),
    ) = PokerSnapshot(
        revision = revision,
        projection = PokerSnapshotProjection(
            orderedPiles = listOf(
                PokerSnapshotPileMetadata(
                    locator = locator,
                    attachmentOrder = 0,
                    workState = "READY",
                    stateChangedAtMs = 1,
                    available = true,
                ),
            ),
        ),
        piles = listOf(
            PokerSnapshotPile(
                metadata = PokerSnapshotPileMetadata(
                    locator = locator,
                    attachmentOrder = 0,
                    workState = "READY",
                    stateChangedAtMs = 1,
                    available = true,
                ),
                cards = cards,
                requestCards = requestCards,
            ),
        ),
    )

    private fun card(
        id: String,
        source: CardSource,
        text: String,
        sequence: Long = 1,
        revision: Long = 1,
        complete: Boolean = true,
    ) = Card(
        id = id,
        conversationId = "${locator.hostId}/${locator.threadId}",
        sequence = sequence,
        revision = revision,
        role = when (source) {
            CardSource.CODEX_USER_MESSAGE -> CardRole.USER
            CardSource.SYSTEM -> CardRole.SYSTEM
            else -> CardRole.AGENT
        },
        state = if (complete) CardState.COMMITTED else CardState.OPEN,
        fullText = text,
        createdAtMs = sequence,
        updatedAtMs = sequence,
        source = source,
        contentComplete = complete,
    )
}
