package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerRemoteInputAdapterTest {
    @Test
    fun `learning captures one descriptor and key code without confirmation`() {
        val bindings = PokerBindingController()
        val device = PokerBindingDevice.remote("remote-a")
        bindings.observeRemote(device.descriptor)
        bindings.selectDevice(device)
        bindings.beginLearning(PokerOperation.RIGHT)
        val notices = mutableListOf<String>()
        val adapter = adapter(bindings, notices = notices)

        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 10)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 42, 20)))
        assertNull(bindings.learningTarget)
        assertEquals(PokerOperation.RIGHT, bindings.map.operationFor(PokerBindingControl.remote("remote-a", 42)))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `repeat, chord, and unsupported learning input never synthesize a control`() {
        val bindings = PokerBindingController()
        val device = PokerBindingDevice.remote("remote-a")
        bindings.observeRemote(device.descriptor)
        bindings.selectDevice(device)
        bindings.beginLearning(PokerOperation.TAP)
        val notices = mutableListOf<String>()
        val adapter = adapter(bindings, notices = notices)

        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 0, 10)))
        assertEquals("Cannot bind", notices.single())
        assertTrue(bindings.learningTarget != null)
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 20)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 43, 21)))
        assertEquals("Cannot bind", notices.last())
        assertTrue(bindings.learningTarget != null)
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 30, repeatCount = 1)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.MULTIPLE, 42, 31)))
        assertTrue(bindings.learningTarget != null)
        assertTrue(bindings.map.controls(device, PokerOperation.TAP).isEmpty())
    }

    @Test
    fun `foreground mapped keys are consumed and invoke the operation while unknown descriptors are ignored`() {
        val bindings = boundRight()
        var foreground = true
        val navigation = navigation()
        val adapter = PokerRemoteInputAdapter(
            controller = PokerInputController(navigation),
            bindings = bindings,
            isForeground = { foreground },
        )

        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 99, 10)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 99, 20)))
        assertEquals(first, navigation.metadata().focused)
        assertFalse(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 30, descriptor = "remote-b")))

        foreground = false
        assertFalse(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 40)))
        assertEquals(first, navigation.metadata().focused)

        foreground = true
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 50)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 42, 60)))
        assertEquals(second, navigation.metadata().focused)
    }

    @Test
    fun `newly observed remote with no bindings consumes foreground keys as no-ops`() {
        val bindings = PokerBindingController()
        val navigation = navigation()
        val adapter = PokerRemoteInputAdapter(PokerInputController(navigation), bindings)

        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 10)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 42, 20)))

        assertEquals(first, navigation.metadata().focused)
        assertEquals(listOf("remote-a"), bindings.state.knownRemoteDescriptors)
    }

    @Test
    fun `cleared remote remains managed while unknown and background keys are ignored`() {
        val bindings = boundRight()
        bindings.clearSelectedRemote()
        assertFalse(bindings.map.isManagedRemote("remote-a"))
        var foreground = true
        val navigation = navigation()
        val adapter = PokerRemoteInputAdapter(
            controller = PokerInputController(navigation),
            bindings = bindings,
            isForeground = { foreground },
        )

        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 10)))
        assertTrue(adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 42, 20)))
        assertEquals(first, navigation.metadata().focused)

        assertFalse(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 30, descriptor = "remote-b")))
        foreground = false
        assertFalse(adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 40)))
        assertEquals(listOf("remote-a"), bindings.state.knownRemoteDescriptors)
    }

    @Test
    fun `a held key keeps the operation selected at begin across a map revision`() {
        val bindings = boundRight()
        val navigation = navigation()
        val adapter = PokerRemoteInputAdapter(PokerInputController(navigation), bindings)

        adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 42, 10))
        bindings.install(
            bindings.map.bind(
                PokerBindingDevice.remote("remote-a"),
                PokerOperation.LEFT,
                PokerBindingControl.remote("remote-a", 43),
            ),
        )
        adapter.onKeyEvent(event(PokerRemoteKeyAction.UP, 42, 20))

        assertEquals(second, navigation.metadata().focused)
    }

    @Test
    fun `focus loss cancels learning and does not change the map`() {
        val bindings = PokerBindingController()
        val device = PokerBindingDevice.remote("remote-a")
        bindings.observeRemote(device.descriptor)
        bindings.selectDevice(device)
        bindings.beginLearning(PokerOperation.FN)
        val adapter = adapter(bindings)
        val before = bindings.map

        adapter.onKeyEvent(event(PokerRemoteKeyAction.DOWN, 7, 10))
        adapter.onFocusLost(20)

        assertNull(bindings.learningTarget)
        assertEquals(before, bindings.map)
    }

    private fun boundRight(): PokerBindingController = PokerBindingController().also { bindings ->
        val device = PokerBindingDevice.remote("remote-a")
        bindings.observeRemote(device.descriptor)
        bindings.selectDevice(device)
        bindings.beginLearning(PokerOperation.RIGHT)
        bindings.capture(PokerBindingControl.remote("remote-a", 42))
    }

    private fun adapter(
        bindings: PokerBindingController,
        notices: MutableList<String> = mutableListOf(),
    ) = PokerRemoteInputAdapter(
        controller = PokerInputController(navigation()),
        bindings = bindings,
        onNotice = notices::add,
    )

    private fun navigation() = PokerNavigationReducer(viewportLineCount = 3).also {
        it.attach(first, ThreadWorkEvidence(false, 0), 1, layout = PokerPileLayout(listOf(PokerCardLayout("first", 1))))
        it.attach(second, ThreadWorkEvidence(false, 0), 2, layout = PokerPileLayout(listOf(PokerCardLayout("second", 1))))
        it.view(first)
    }

    private fun event(
        action: PokerRemoteKeyAction,
        keyCode: Int,
        time: Long,
        descriptor: String = "remote-a",
        repeatCount: Int = 0,
    ) = PokerRemoteKeyEvent(descriptor, keyCode, action, time, repeatCount)

    private companion object {
        val first = CodexThreadLocator("spark", "first")
        val second = CodexThreadLocator("spark", "second")
    }
}
