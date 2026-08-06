package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerActionWheelTest {
    private val context = PokerWheelContext(
        targetId = "thread|composer|1|SEND",
        primaryAction = PokerPrimaryAction.SEND,
    )

    @Test
    fun `down primary requires long hold and stable posture`() {
        val wheel = PokerActionWheel(sessionId = { "wheel-1" })
        wheel.begin(context, PokerPostureSample(0f, 0f, 0L))

        assertEquals(PokerWheelPosture.DOWN, wheel.update(PokerPostureSample(-30f, 0f, 500L)).posture)
        assertTrue(wheel.update(PokerPostureSample(-30f, 0f, 600L)).stable)
        val selection = wheel.release(601L, context)

        assertEquals(PokerWheelAction.PRIMARY, selection?.action)
        assertEquals(PokerPrimaryAction.SEND, selection?.primaryAction)
    }

    @Test
    fun `diagonal and stale posture never choose a sector`() {
        val wheel = PokerActionWheel(sessionId = { "wheel-2" })
        wheel.begin(context, PokerPostureSample(0f, 0f, 0L))
        assertEquals(
            PokerWheelPosture.NONE,
            wheel.update(PokerPostureSample(-30f, -27f, 500L)).posture,
        )
        assertNull(wheel.release(751L, context))
    }

    @Test
    fun `changed target cancels the wheel instead of substituting action`() {
        val wheel = PokerActionWheel(sessionId = { "wheel-3" })
        wheel.begin(context, PokerPostureSample(0f, 0f, 0L))

        val changed = wheel.update(
            PokerPostureSample(-30f, 0f, 500L),
            context.copy(targetId = "new-target"),
        )

        assertTrue(changed.cancelled)
        assertNull(wheel.release(600L, context))
    }
}
