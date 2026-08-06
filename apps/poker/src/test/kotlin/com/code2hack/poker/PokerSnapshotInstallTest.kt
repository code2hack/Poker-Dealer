package com.code2hack.poker

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerNavigationMode
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.protocol.PokerSnapshot
import com.code2hack.pokerdealer.protocol.PokerSnapshotPile
import com.code2hack.pokerdealer.protocol.PokerSnapshotPileMetadata
import com.code2hack.pokerdealer.protocol.PokerSnapshotProjection
import com.code2hack.pokerdealer.protocol.PokerFontScaleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerSnapshotInstallTest {
    @Test
    fun `font scale and metadata replacement preserve local presentation`() {
        val focused = CodexThreadLocator("spark", "focused")
        val other = CodexThreadLocator("u4090", "other")
        val navigation = PokerNavigationReducer(viewportLineCount = 2)
        navigation.attach(
            focused,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("focused-card", collapsedLineCount = 6)),
                composer = PokerComposerLayout(draft = ComposerDraft.fromText("hello world")),
            ),
        )
        navigation.attach(
            other,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = oneCard("other-card"),
        )
        navigation.view(focused)
        repeat(5) { navigation.apply(com.code2hack.pokerdealer.domain.PokerOperation.DOWN) }
        navigation.setComposerCursor(focused, 2)
        val before = checkNotNull(navigation.anchor(focused))

        navigation.installPokerSnapshot(
            snapshot(
                pile(other, "BUSY", "other update", order = 0),
                pile(
                    focused,
                    "READY",
                    "focused update\nwith more text\nline three\nline four\nline five\nline six\nline seven",
                    order = 1,
                ),
                fontScale = PokerFontScaleState(1, 150),
            ),
        )

        assertTrue(navigation.metadata().hudVisible)
        assertEquals(focused, navigation.metadata().focused)
        assertEquals(before, navigation.anchor(focused))
        assertEquals(PokerNavigationMode.COMPOSER, navigation.anchor(focused)?.mode)
        assertEquals(2, navigation.anchor(focused)?.cursorPosition)
        assertEquals("hello world", navigation.layout(focused)?.composer?.draft?.displayText)
    }

    @Test
    fun `removed focus selects new occupant then preceding survivor`() {
        val first = CodexThreadLocator("spark", "first")
        val removed = CodexThreadLocator("u4090", "removed")
        val third = CodexThreadLocator("fold6-termux", "third")
        val added = CodexThreadLocator("spark", "added")
        val navigation = PokerNavigationReducer().also {
            it.attach(first, ready(), atMs = 1, layout = oneCard("first-card"))
            it.attach(removed, ready(), atMs = 1, layout = oneCard("removed-card"))
            it.attach(third, ready(), atMs = 1, layout = oneCard("third-card"))
            it.view(removed)
        }

        navigation.installPokerSnapshot(
            snapshot(
                pile(first, "READY", "first", order = 0),
                pile(third, "READY", "third", order = 1),
                pile(added, "READY", "added", order = 2),
            ),
        )
        assertEquals(third, navigation.metadata().focused)
        assertTrue(navigation.metadata().hudVisible)

        navigation.installPokerSnapshot(
            snapshot(pile(first, "READY", "first again", order = 0)),
        )
        assertEquals(first, navigation.metadata().focused)
        assertTrue(navigation.metadata().hudVisible)
    }

    private fun ready() = ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0)

    private fun oneCard(id: String) = PokerPileLayout(listOf(PokerCardLayout(id, 1)))

    private fun snapshot(
        vararg piles: PokerSnapshotPile,
        fontScale: PokerFontScaleState = PokerFontScaleState(),
    ) = PokerSnapshot(
        revision = 1,
        projection = PokerSnapshotProjection(
            orderedPiles = piles.map(PokerSnapshotPile::metadata),
            fontScale = fontScale,
        ),
        piles = piles.toList(),
    )

    private fun pile(
        locator: CodexThreadLocator,
        workState: String,
        text: String,
        order: Long,
    ) = PokerSnapshotPile(
        metadata = PokerSnapshotPileMetadata(
            locator = locator,
            attachmentOrder = order,
            workState = workState,
            stateChangedAtMs = 2,
            available = true,
        ),
        cards = listOf(
            Card(
                id = "${locator.threadId}-card",
                conversationId = "${locator.hostId}/${locator.threadId}",
                sequence = 1,
                revision = 1,
                role = CardRole.AGENT,
                state = CardState.COMMITTED,
                fullText = text,
                createdAtMs = 1,
                updatedAtMs = 1,
                source = CardSource.CODEX_AGENT_MESSAGE,
            ),
        ),
    )
}
