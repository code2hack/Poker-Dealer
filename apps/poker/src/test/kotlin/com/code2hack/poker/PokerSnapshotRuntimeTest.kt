package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PokerSnapshotRuntimeTest {
    @Test
    fun `runtime exposes only complete process-local state and clears on restart`() {
        PokerSnapshotRuntime.clearForRestart()
        val snapshot = PokerSnapshot(revision = 7)

        PokerSnapshotRuntime.install(snapshot)
        assertEquals(snapshot, PokerSnapshotRuntime.snapshot.value)

        PokerSnapshotRuntime.clearForRestart()
        assertNull(PokerSnapshotRuntime.snapshot.value)
    }
}
