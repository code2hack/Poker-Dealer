package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerTransientNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PokerNoticeRuntimeTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `runtime expires notices and fences replacement expiry`() = runTest {
        val runtime = PokerNoticeRuntime.forTest(this)
        val short = PokerTransientNotice("short", 500L)

        runtime.show(short)
        assertEquals(short, runtime.notice.value)
        advanceTimeBy(500L)
        runCurrent()
        assertNull(runtime.notice.value)

        runtime.show(PokerTransientNotice("old", 500L))
        val newer = PokerTransientNotice("new", 1_000L)
        runtime.show(newer)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(newer, runtime.notice.value)
        advanceTimeBy(500L)
        runCurrent()
        assertNull(runtime.notice.value)
    }
}
