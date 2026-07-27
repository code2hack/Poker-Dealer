package com.code2hack.pokerdealer.testing

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.Conversation
import com.code2hack.pokerdealer.domain.ConversationState
import com.code2hack.pokerdealer.domain.DaemonState
import com.code2hack.pokerdealer.domain.HostArchitecture
import com.code2hack.pokerdealer.domain.HostConnectionState

object MockDeck {
    val host = CodexHost(
        id = "mock-spark",
        displayName = "DGX Spark",
        architecture = HostArchitecture.LINUX_ARM64,
        connectionState = HostConnectionState.CONNECTED,
        daemonState = DaemonState.READY,
        codexVersion = "mock-current",
        appServerVersion = "mock-current",
    )

    val conversation = Conversation(
        id = "mock-conversation",
        locator = CodexThreadLocator(
            hostId = host.id,
            threadId = "thr_mock_poker_dealer",
        ),
        alias = "Poker–Dealer implementation",
        state = ConversationState.ACTIVE,
        intendedControlSurface = ControlSurface.DEALER,
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
        conclusion = "The mock projection preserves and displays the complete Codex agent message.",
        createdAtMs = 1_784_600_000_000,
        updatedAtMs = 1_784_600_000_000,
        source = CardSource.CODEX_AGENT_MESSAGE,
    )

    private fun longCardText(): String = buildString {
        appendLine("Poker–Dealer Codex-thread mock vertical slice")
        appendLine()
        appendLine("This card deliberately exceeds 20,000 characters. It represents a structured agent-message projection from a daemon-backed Codex thread and preserves Unicode, indentation, and complete text.")
        appendLine()
        var line = 1
        while (length < 20_000) {
            append(line.toString().padStart(4, '0'))
            append("  DGX Spark / thr_mock_poker_dealer  │  full text stays intact  │  ♠ ♥ ♦ ♣  │  中文  │  ")
            appendLine("val delta$line = \"agent-message-$line\"")
            line += 1
        }
    }
}
