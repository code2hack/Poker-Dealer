package com.code2hack.pokerdealer.testing

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.Conversation
import com.code2hack.pokerdealer.domain.ConversationState
import com.code2hack.pokerdealer.domain.PaneLocator

object MockDeck {
    val conversation = Conversation(
        id = "mock-conversation",
        locator = PaneLocator(
            hostId = "mock-host",
            tmuxServerId = "mock-default",
            paneId = "%17",
            sessionName = "codex",
            windowName = "implementation",
            paneIndex = 0,
            paneTitle = "Poker–Dealer M0",
            currentCommand = "codex",
        ),
        alias = "Mock · Codex",
        state = ConversationState.ONLINE,
        lastSequence = 1,
        unreadCount = 1,
    )

    val longCard = Card(
        id = "mock-card-1",
        conversationId = conversation.id,
        sequence = 1,
        revision = 1,
        role = CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = longCardText(),
        conclusion = "The mock transport preserves and displays the complete card.",
        createdAtMs = 1_784_600_000_000,
        updatedAtMs = 1_784_600_000_000,
        source = CardSource.STRUCTURED_EVENT,
    )

    private fun longCardText(): String = buildString {
        appendLine("Poker–Dealer mock vertical slice")
        appendLine()
        appendLine("This card deliberately exceeds 20,000 characters. It contains Unicode, indentation, and numbered output so scrolling and preservation are visible.")
        appendLine()
        var line = 1
        while (length < 20_000) {
            append(line.toString().padStart(4, '0'))
            append("  tmux %17  │  full text stays intact  │  ♠ ♥ ♦ ♣  │  中文  │  ")
            appendLine("val frame$line = \"chunk-$line\"")
            line += 1
        }
    }
}
