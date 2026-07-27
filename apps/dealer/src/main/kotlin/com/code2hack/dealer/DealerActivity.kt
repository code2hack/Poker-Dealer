package com.code2hack.dealer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.testing.LoopbackPokerTransport
import com.code2hack.pokerdealer.testing.MockDeck

class DealerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DealerMockApp()
                }
            }
        }
    }
}

@Composable
private fun DealerMockApp() {
    val transport = remember { LoopbackPokerTransport() }
    val state by transport.state.collectAsState()
    LaunchedEffect(transport) { transport.connect() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18202A))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Dealer", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Poker: $state", color = Color(0xFF8EE7B2))
                Text("Host: ${MockDeck.host.displayName}", color = Color(0xFFBBC8D6))
                Text("Daemon: ${MockDeck.host.daemonState}", color = Color(0xFFBBC8D6))
            }
            Text(
                "${MockDeck.conversation.alias} · ${MockDeck.conversation.locator.threadId}",
                color = Color.White,
            )
            Text(
                "${MockDeck.conversation.state} · control ${MockDeck.conversation.intendedControlSurface}",
                color = Color(0xFFBBC8D6),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            "Codex agent-message projection · complete card (${MockDeck.longCard.fullText.length} chars)",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider()
        MockCardBody(card = MockDeck.longCard, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MockCardBody(card: Card, modifier: Modifier = Modifier) {
    val lines = remember(card.id, card.revision) { card.fullText.split('\n') }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
            Text(
                text = if (line.isEmpty()) " " else line,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
