package com.code2hack.dealer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.pokerdealer.protocol.PokerConnectionState
import com.code2hack.pokerdealer.protocol.PokerPairingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DealerPokerPairingHardwareTest {
    @Test
    fun `code only enrollment discovers Poker and reaches authenticated WiFi`() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val code = arguments.getString(ARG_POKER_CODE)
        assumeTrue("Opt-in real-hardware test", code?.matches(Regex("[0-9]{6}")) == true)

        val context = instrumentation.targetContext
        val connected = CompletableDeferred<DealerConnectionService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete((binder as DealerConnectionService.LocalBinder).service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) connected.cancel()
            }
        }
        assertTrue(
            context.bindService(
                Intent(context, DealerConnectionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )

        try {
            val service = withTimeout(SERVICE_BIND_TIMEOUT_MS) { connected.await() }
            assertTrue(service.beginPokerPairing(checkNotNull(code)))
            val paired = withTimeout(PAIRING_TIMEOUT_MS) {
                service.state.first { state ->
                    state.pokerDiagnostics.pairing == PokerPairingState.PAIRED &&
                        state.pokerDiagnostics.connection == PokerConnectionState.CONNECTED
                }
            }
            assertTrue(paired.pokerConnected)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val ARG_POKER_CODE = "pokerCode"
        const val SERVICE_BIND_TIMEOUT_MS = 10_000L
        const val PAIRING_TIMEOUT_MS = 30_000L
    }
}
