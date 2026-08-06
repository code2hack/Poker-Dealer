package com.code2hack.poker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    LaunchedEffect(transport) { transport.connect() }

    PokerPilePages(
        metadata = MockDeck.pileMetadata,
        cardTextByLocator = MockDeck.cardTextByLocator,
        modifier = Modifier.fillMaxSize(),
    )
}
