package com.code2hack.poker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.code2hack.pokerdealer.testing.LoopbackPokerTransport
import com.code2hack.pokerdealer.testing.MockDeck

class PokerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PokerMockCardReader()
                }
            }
        }
    }
}

@Composable
private fun PokerMockCardReader() {
    val transport = remember { LoopbackPokerTransport() }
    val transportState by transport.state.collectAsState()
    val card = MockDeck.longCard
    val pileMetadata = remember { MockDeck.pileMetadata }
    val lines = remember(card.id, card.revision) { card.fullText.split('\n') }

    LaunchedEffect(transport) { transport.connect() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101820))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PokerPileLine(pileMetadata)
            Text(MockDeck.conversation.alias, color = Color.White)
            Text(
                "${MockDeck.host.displayName} · ${MockDeck.conversation.locator.threadId}",
                color = Color(0xFFAFC4D8),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "AGENT · COMMITTED · ${card.fullText.length} chars · $transportState",
                color = Color(0xFF83E6AD),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                Text(
                    text = if (line.isEmpty()) " " else line,
                    color = Color(0xFFE8EEF4),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            "Swipe/drag to scroll · full Codex text retained",
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101820))
                .padding(12.dp),
            color = Color(0xFFAFC4D8),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
