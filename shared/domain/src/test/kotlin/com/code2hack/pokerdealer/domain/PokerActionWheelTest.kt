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
    fun `posture boundaries and dominant axes use the fixed wheel layout`() {
        val available = context.copy(
            photoAvailable = true,
            morseAvailable = true,
            asrAvailable = true,
        )
        val cases = listOf(
            Triple(PokerPostureSample(10.1f, 0f, 500L), PokerWheelPosture.UP, PokerWheelAction.PHOTO),
            Triple(PokerPostureSample(-10.1f, 0f, 500L), PokerWheelPosture.DOWN, PokerWheelAction.PRIMARY),
            Triple(PokerPostureSample(0f, -10.1f, 500L), PokerWheelPosture.LEFT, PokerWheelAction.MORSE),
            Triple(PokerPostureSample(0f, 10.1f, 500L), PokerWheelPosture.RIGHT, PokerWheelAction.ASR),
        )

        cases.forEachIndexed { index, (sample, posture, action) ->
            val wheel = PokerActionWheel(sessionId = { "boundary-$index" })
            wheel.begin(available, PokerPostureSample(0f, 0f, 0L))
            assertEquals(posture, wheel.update(sample).posture)
            assertTrue(wheel.update(sample.copy(eventTimeMs = 600L)).stable)
            assertEquals(action, wheel.release(601L, available)?.action)
        }

        val deadzone = PokerActionWheel(sessionId = { "boundary-center" })
        deadzone.begin(available, PokerPostureSample(0f, 0f, 0L))
        assertEquals(
            PokerWheelPosture.NONE,
            deadzone.update(PokerPostureSample(-10f, 0f, 500L)).posture,
        )
        assertNull(deadzone.release(601L, available))
    }

    @Test
    fun `jitter inside hysteresis keeps the current stable sector`() {
        val wheel = PokerActionWheel(sessionId = { "wheel-jitter" })
        wheel.begin(context, PokerPostureSample(0f, 0f, 0L))

        assertEquals(PokerWheelPosture.DOWN, wheel.update(PokerPostureSample(-30f, 0f, 500L)).posture)
        assertEquals(PokerWheelPosture.DOWN, wheel.update(PokerPostureSample(-8f, 0f, 550L)).posture)
        assertTrue(wheel.update(PokerPostureSample(-8f, 0f, 600L)).stable)
        assertEquals(PokerPrimaryAction.SEND, wheel.release(601L, context)?.primaryAction)
    }

    @Test
    fun `diagonal posture and a stale sample never choose a sector`() {
        val diagonal = PokerActionWheel(sessionId = { "wheel-diagonal" })
        diagonal.begin(context, PokerPostureSample(0f, 0f, 0L))
        assertEquals(
            PokerWheelPosture.NONE,
            diagonal.update(PokerPostureSample(-30f, -27f, 500L)).posture,
        )
        assertNull(diagonal.release(601L, context))

        val stale = PokerActionWheel(sessionId = { "wheel-stale" })
        stale.begin(context, PokerPostureSample(0f, 0f, 0L))
        stale.update(PokerPostureSample(-30f, 0f, 500L))
        stale.update(PokerPostureSample(-30f, 0f, 600L))
        assertNull(stale.release(851L, context))
    }

    @Test
    fun `unavailable sectors remain disabled in place`() {
        val unavailable = context.copy(
            photoAvailable = false,
            morseAvailable = false,
            asrAvailable = false,
        )
        listOf(
            PokerPostureSample(30f, 0f, 500L),
            PokerPostureSample(0f, -30f, 500L),
            PokerPostureSample(0f, 30f, 500L),
        ).forEachIndexed { index, sample ->
            val wheel = PokerActionWheel(sessionId = { "disabled-$index" })
            wheel.begin(unavailable, PokerPostureSample(0f, 0f, 0L))
            val state = wheel.update(sample)
            assertEquals(PokerWheelPosture.NONE, state.posture)
            assertNull(state.highlightedAction)
            assertNull(wheel.release(601L, unavailable))
        }
    }

    @Test
    fun `takeover and every target fence cancel the wheel`() {
        val changedContexts = listOf(
            context.copy(targetId = "new-target"),
            context.copy(controlGeneration = context.controlGeneration + 1),
            context.copy(connectionEpoch = context.connectionEpoch + 1),
            context.copy(modeSession = "new-mode"),
        )

        changedContexts.forEachIndexed { index, changedContext ->
            val wheel = PokerActionWheel(sessionId = { "wheel-fence-$index" })
            wheel.begin(context, PokerPostureSample(0f, 0f, 0L))
            val changed = wheel.update(PokerPostureSample(-30f, 0f, 500L), changedContext)
            assertTrue(changed.cancelled)
            assertNull(wheel.release(600L, context))
        }
    }

    @Test
    fun `semantic Primary transition cannot release the old highlight`() {
        val wheel = PokerActionWheel(sessionId = { "wheel-semantic" })
        wheel.begin(context, PokerPostureSample(0f, 0f, 0L))

        val changed = wheel.update(
            PokerPostureSample(-30f, 0f, 500L),
            context.copy(primaryAction = PokerPrimaryAction.STEER),
        )

        assertTrue(changed.cancelled)
        assertNull(wheel.release(600L, context.copy(primaryAction = PokerPrimaryAction.STEER)))
    }
}
