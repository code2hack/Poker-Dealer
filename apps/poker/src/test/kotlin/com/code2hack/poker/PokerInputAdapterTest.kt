package com.code2hack.poker

import android.view.KeyEvent
import android.view.MotionEvent
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerCancellationReason
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerNavigationEffect
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerPileAnchor
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerInputAdapterTest {
    @Test
    @Suppress("DEPRECATION")
    fun `Android built-in event actions map at the boundary`() {
        assertEquals(PokerTouchAction.DOWN, pokerTouchAction(MotionEvent.ACTION_DOWN))
        assertEquals(PokerTouchAction.POINTER_DOWN, pokerTouchAction(MotionEvent.ACTION_POINTER_DOWN))
        assertEquals(PokerTouchAction.MOVE, pokerTouchAction(MotionEvent.ACTION_MOVE))
        assertEquals(PokerTouchAction.POINTER_UP, pokerTouchAction(MotionEvent.ACTION_POINTER_UP))
        assertEquals(PokerTouchAction.UP, pokerTouchAction(MotionEvent.ACTION_UP))
        assertEquals(PokerTouchAction.CANCEL, pokerTouchAction(MotionEvent.ACTION_CANCEL))
        assertNull(pokerTouchAction(-1))

        assertEquals(PokerFunctionAction.DOWN, pokerFunctionAction(KeyEvent.ACTION_DOWN))
        assertEquals(PokerFunctionAction.UP, pokerFunctionAction(KeyEvent.ACTION_UP))
        assertEquals(PokerFunctionAction.CANCEL, pokerFunctionAction(KeyEvent.ACTION_MULTIPLE))
        assertNull(pokerFunctionAction(-1))

        assertEquals(
            PokerAndroidInputDeviceKind.BUILT_IN,
            pokerAndroidInputDeviceKind(isExternal = false, isVirtual = false),
        )
        assertEquals(
            PokerAndroidInputDeviceKind.EXTERNAL_HID,
            pokerAndroidInputDeviceKind(isExternal = true, isVirtual = false),
        )
        assertEquals(
            PokerAndroidInputDeviceKind.OTHER,
            pokerAndroidInputDeviceKind(isExternal = false, isVirtual = true),
        )
    }

    @Test
    fun `physical built-in function input stays glasses input and is never observed`() {
        val bindings = PokerBindingController()
        val builtInResults = mutableListOf<PokerInputController.Result>()
        val adapter = androidAdapter(bindings, builtInResults = builtInResults)

        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.BUILT_IN,
            descriptor = "glasses-runtime-descriptor",
            keyCode = KeyEvent.KEYCODE_FUNCTION,
            action = KeyEvent.ACTION_DOWN,
            time = 10,
        )))
        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.BUILT_IN,
            descriptor = "glasses-runtime-descriptor",
            keyCode = KeyEvent.KEYCODE_FUNCTION,
            action = KeyEvent.ACTION_UP,
            time = 20,
        )))

        assertEquals(listOf(PokerOperation.FN, PokerOperation.FN), builtInResults.map { it.interaction.operation })
        assertTrue(bindings.state.knownRemoteDescriptors.isEmpty())
    }

    @Test
    fun `external remote function key remains remote input`() {
        val bindings = PokerBindingController()
        val remote = PokerBindingDevice.remote("remote-a")
        bindings.observeRemote(remote.descriptor)
        bindings.selectDevice(remote)
        bindings.install(
            bindings.map
                .bind(remote, PokerOperation.RIGHT, PokerBindingControl.remote(remote.descriptor, KeyEvent.KEYCODE_FUNCTION))
                .bind(remote, PokerOperation.LEFT, PokerBindingControl.remote(remote.descriptor, 42)),
        )
        val navigation = navigation()
        val builtInResults = mutableListOf<PokerInputController.Result>()
        val adapter = androidAdapter(bindings, navigation, builtInResults)

        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = remote.descriptor,
            keyCode = KeyEvent.KEYCODE_FUNCTION,
            action = KeyEvent.ACTION_DOWN,
            time = 10,
        )))
        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = remote.descriptor,
            keyCode = KeyEvent.KEYCODE_FUNCTION,
            action = KeyEvent.ACTION_UP,
            time = 20,
        )))
        assertEquals(second, navigation.metadata().focused)
        assertTrue(builtInResults.isEmpty())

        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = remote.descriptor,
            keyCode = 42,
            action = KeyEvent.ACTION_DOWN,
            time = 30,
        )))
        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = remote.descriptor,
            keyCode = 42,
            action = KeyEvent.ACTION_UP,
            time = 40,
        )))
        assertEquals(first, navigation.metadata().focused)
        assertTrue(builtInResults.isEmpty())
    }

    @Test
    fun `only external physical descriptors are observed`() {
        val bindings = PokerBindingController()
        var changed = 0
        val adapter = androidAdapter(bindings, onBindingChanged = { changed++ })

        assertTrue(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = "remote-a",
            keyCode = 42,
            action = KeyEvent.ACTION_DOWN,
            time = 10,
        )))
        assertEquals(listOf("remote-a"), bindings.state.knownRemoteDescriptors)
        assertEquals(1, changed)

        assertFalse(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.BUILT_IN,
            descriptor = "glasses-runtime-descriptor",
            keyCode = 42,
            action = KeyEvent.ACTION_DOWN,
            time = 20,
        )))
        assertEquals(listOf("remote-a"), bindings.state.knownRemoteDescriptors)
        assertEquals(1, changed)
    }

    @Test
    fun `virtual or missing devices are not remote input`() {
        val bindings = PokerBindingController()
        val builtInResults = mutableListOf<PokerInputController.Result>()
        val adapter = androidAdapter(bindings, builtInResults = builtInResults)

        assertFalse(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.OTHER,
            descriptor = "virtual-keyboard",
            keyCode = KeyEvent.KEYCODE_FUNCTION,
            action = KeyEvent.ACTION_DOWN,
            time = 10,
        )))
        assertFalse(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.OTHER,
            descriptor = null,
            keyCode = 42,
            action = KeyEvent.ACTION_DOWN,
            time = 20,
        )))
        assertFalse(adapter.onKeyEvent(androidKey(
            kind = PokerAndroidInputDeviceKind.EXTERNAL_HID,
            descriptor = "",
            keyCode = 42,
            action = KeyEvent.ACTION_DOWN,
            time = 30,
        )))

        assertTrue(bindings.state.knownRemoteDescriptors.isEmpty())
        assertTrue(builtInResults.isEmpty())
    }

    @Test
    fun `touch swipes map to canonical operations with monotonic duration`() {
        val adapter = adapter()

        val begin = adapter.onTouchEvent(touch(PokerTouchAction.DOWN, y = 0f, time = 10)).single()
        val classify = adapter.onTouchEvent(touch(PokerTouchAction.MOVE, y = 30f, time = 20)).single()
        val update = adapter.onTouchEvent(touch(PokerTouchAction.MOVE, y = 45f, time = 30)).single()
        val release = adapter.onTouchEvent(touch(PokerTouchAction.UP, y = 45f, time = 50)).single()

        assertNull(begin.interaction.operation)
        assertEquals(PokerInteractionPhase.BEGIN, begin.interaction.phase)
        assertEquals(10L, begin.interaction.eventTimeMs)
        assertEquals(PokerOperation.DOWN, classify.interaction.operation)
        assertEquals(PokerInteractionPhase.UPDATE, classify.interaction.phase)
        assertEquals(PokerInteractionPhase.UPDATE, update.interaction.phase)
        assertEquals(20L, update.interaction.durationMs)
        assertEquals(PokerInteractionPhase.RELEASE, release.interaction.phase)
        assertEquals(40L, release.interaction.durationMs)
    }

    @Test
    fun `built-in interaction keeps the glasses map captured at begin`() {
        val bindings = PokerBindingController()
        val adapter = PokerBuiltInInputAdapter(
            controller = PokerInputController(navigation()),
            bindings = bindings,
        )

        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, y = 0f, time = 10))
        val classify = adapter.onTouchEvent(touch(PokerTouchAction.MOVE, y = 30f, time = 20)).single()
        bindings.install(bindings.map.remove(PokerBindingDevice.GLASSES, PokerOperation.DOWN))
        val release = adapter.onTouchEvent(touch(PokerTouchAction.UP, y = 30f, time = 30)).single()

        assertEquals(PokerOperation.DOWN, classify.interaction.operation)
        assertEquals(PokerOperation.DOWN, release.interaction.operation)
    }

    @Test
    fun `down then action cancel before slop emits one pending cancellation`() {
        var changed = 0
        val adapter = adapter(onNavigationChanged = { changed++ })

        val begin = adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10)).single()
        val cancel = adapter.onTouchEvent(touch(PokerTouchAction.CANCEL, time = 15)).single()

        assertNull(begin.interaction.operation)
        assertEquals(PokerInteractionPhase.BEGIN, begin.interaction.phase)
        assertEquals(PokerInteractionPhase.CANCEL, cancel.interaction.phase)
        assertNull(cancel.interaction.operation)
        assertEquals(PokerCancellationReason.ACTION_CANCEL, cancel.interaction.cancellationReason)
        assertEquals(5L, cancel.interaction.durationMs)
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.CANCEL, time = 16)).isEmpty())
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.UP, time = 17)).isEmpty())
        assertEquals(0, changed)
    }

    @Test
    fun `focus loss before slop cancels pending contact without action`() {
        var changed = 0
        val adapter = adapter(nowMs = 20, onNavigationChanged = { changed++ })

        val begin = adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10)).single()
        val cancel = adapter.onFocusLost().single()

        assertNull(begin.interaction.operation)
        assertEquals(PokerCancellationReason.FOCUS_LOST, cancel.interaction.cancellationReason)
        assertEquals(20L, cancel.interaction.eventTimeMs)
        assertEquals(10L, cancel.interaction.durationMs)
        assertTrue(adapter.onFocusLost().isEmpty())
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.UP, time = 30)).isEmpty())
        assertEquals(0, changed)
    }

    @Test
    fun `disconnect before slop cancels pending contact without action`() {
        var changed = 0
        val adapter = adapter(nowMs = 25, onNavigationChanged = { changed++ })

        val begin = adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10)).single()
        val cancel = adapter.onDisconnected().single()

        assertNull(begin.interaction.operation)
        assertEquals(PokerCancellationReason.DISCONNECTED, cancel.interaction.cancellationReason)
        assertEquals(15L, cancel.interaction.durationMs)
        assertTrue(adapter.onDisconnected().isEmpty())
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.UP, time = 30)).isEmpty())
        assertEquals(0, changed)
    }

    @Test
    fun `held single and dual taps begin at down and classify only on release`() {
        val single = adapter()
        val singleBegin = single.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10)).single()
        assertEquals(PokerInteractionPhase.BEGIN, singleBegin.interaction.phase)
        assertNull(singleBegin.interaction.operation)
        val singleRelease = single.onTouchEvent(touch(PokerTouchAction.UP, time = 80)).single()
        assertEquals(PokerOperation.TAP, singleRelease.interaction.operation)
        assertEquals(PokerInteractionPhase.RELEASE, singleRelease.interaction.phase)
        assertEquals(70L, singleRelease.interaction.durationMs)

        val dual = adapter()
        val dualBegin = dual.onTouchEvent(touch(PokerTouchAction.DOWN, time = 100)).single()
        dual.onTouchEvent(touch(PokerTouchAction.POINTER_DOWN, pointers = 2, time = 120))
        val dualRelease = dual.onTouchEvent(touch(PokerTouchAction.UP, pointers = 1, time = 180)).single()
        assertEquals(PokerInteractionPhase.BEGIN, dualBegin.interaction.phase)
        assertNull(dualBegin.interaction.operation)
        assertEquals(PokerOperation.TAPTAP, dualRelease.interaction.operation)
        assertEquals(PokerInteractionPhase.RELEASE, dualRelease.interaction.phase)
        assertEquals(80L, dualRelease.interaction.durationMs)
    }

    @Test
    fun `competing function input is ignored while touch classification is pending`() {
        val adapter = adapter(nowMs = 30)

        val begin = adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10)).single()
        assertNull(begin.interaction.operation)
        assertTrue(adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 11)).isEmpty())

        val cancel = adapter.onTouchEvent(touch(PokerTouchAction.CANCEL, time = 20)).single()
        assertEquals(PokerInteractionPhase.CANCEL, cancel.interaction.phase)
        val functionBegin = adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 21)).single()
        assertEquals(PokerOperation.FN, functionBegin.interaction.operation)
        assertEquals(PokerInteractionPhase.BEGIN, functionBegin.interaction.phase)
    }

    @Test
    fun `accepted touch directions and function button map at the Android boundary`() {
        assertEquals(PokerOperation.DOWN, swipe(fingers = 1, deltaY = 30f))
        assertEquals(PokerOperation.UP, swipe(fingers = 1, deltaY = -30f))
        assertEquals(PokerOperation.RIGHT, swipe(fingers = 2, deltaY = 30f))
        assertEquals(PokerOperation.LEFT, swipe(fingers = 2, deltaY = -30f))

        val adapter = adapter()
        val begin = adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 100)).single()
        val release = adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.UP, eventTimeMs = 140)).single()
        assertEquals(PokerOperation.FN, begin.interaction.operation)
        assertEquals(PokerInteractionPhase.RELEASE, release.interaction.phase)
        assertEquals(40L, release.interaction.durationMs)
    }

    @Test
    fun `two-finger tap is emitted once and repeat function downs are ignored`() {
        val adapter = adapter()
        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, pointers = 1, time = 10))
        adapter.onTouchEvent(touch(PokerTouchAction.POINTER_DOWN, pointers = 2, time = 20))
        adapter.onTouchEvent(touch(PokerTouchAction.POINTER_UP, pointers = 2, time = 30))
        val tap = adapter.onTouchEvent(touch(PokerTouchAction.UP, pointers = 1, time = 40))

        assertEquals(listOf(PokerInteractionPhase.RELEASE), tap.map { it.interaction.phase })
        assertEquals(PokerOperation.TAPTAP, tap.single().interaction.operation)

        val function = adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 50))
        assertEquals(PokerOperation.FN, function.single().interaction.operation)
        assertTrue(adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 55, repeatCount = 1)).isEmpty())
        assertEquals(PokerInteractionPhase.RELEASE, adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.UP, eventTimeMs = 60)).single().interaction.phase)
    }

    @Test
    fun `competing touch and function input is cancelled without a synthesized release`() {
        val adapter = adapter(nowMs = 55)
        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 10))
        adapter.onTouchEvent(touch(PokerTouchAction.MOVE, y = 30f, time = 20))

        assertTrue(adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 21)).isEmpty())
        val cancel = adapter.onTouchEvent(touch(PokerTouchAction.CANCEL, y = 30f, time = 30)).single()
        assertEquals(PokerInteractionPhase.CANCEL, cancel.interaction.phase)
        assertEquals(PokerCancellationReason.ACTION_CANCEL, cancel.interaction.cancellationReason)
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.UP, y = 30f, time = 40)).isEmpty())

        val function = adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.DOWN, eventTimeMs = 50)).single()
        assertEquals(PokerOperation.FN, function.interaction.operation)
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.DOWN, time = 51)).isEmpty())
        val focusCancel = adapter.onFocusLost().single()
        assertEquals(PokerCancellationReason.FOCUS_LOST, focusCancel.interaction.cancellationReason)
        assertTrue(adapter.onFunctionEvent(PokerFunctionEvent(PokerFunctionAction.UP, eventTimeMs = 60)).isEmpty())
    }

    @Test
    fun `disconnect cancels once and no action dispatches before release`() {
        val navigation = navigation()
        var changed = 0
        val adapter = PokerBuiltInInputAdapter(
            controller = PokerInputController(navigation),
            monotonicNowMs = { 25 },
            onNavigationChanged = { changed++ },
        )

        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, pointers = 2, time = 10))
        adapter.onTouchEvent(touch(PokerTouchAction.MOVE, pointers = 2, y = 30f, time = 20))
        assertEquals(0, changed)

        val cancel = adapter.onDisconnected().single()
        assertEquals(PokerCancellationReason.DISCONNECTED, cancel.interaction.cancellationReason)
        assertTrue(adapter.onTouchEvent(touch(PokerTouchAction.UP, pointers = 2, y = 30f, time = 30)).isEmpty())
        assertEquals(0, changed)
    }

    @Test
    fun `release updates visible pile and scroll anchor while begin does not`() {
        val navigation = navigation()
        var changed = 0
        val adapter = PokerBuiltInInputAdapter(
            controller = PokerInputController(navigation),
            onNavigationChanged = { changed++ },
        )

        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, pointers = 2, time = 10))
        val begin = adapter.onTouchEvent(touch(PokerTouchAction.MOVE, pointers = 2, y = 30f, time = 20)).single()
        assertEquals(PokerNavigationEffect.NONE, begin.navigationEffect)
        val release = adapter.onTouchEvent(touch(PokerTouchAction.UP, pointers = 2, y = 30f, time = 30)).single()
        assertEquals(PokerNavigationEffect.PILE_MOVED, release.navigationEffect)
        assertEquals(second, navigation.metadata().focused)
        assertEquals(1, changed)
        assertEquals(
            second,
            navigation.metadata().toPokerPileRenderProjection(
                cardTextByLocator = mapOf(first to "first", second to "second"),
                anchorByLocator = navigation.anchors(),
            ).visiblePage?.locator,
        )

        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, y = 30f, time = 40))
        adapter.onTouchEvent(touch(PokerTouchAction.MOVE, y = 0f, time = 50))
        val scroll = adapter.onTouchEvent(touch(PokerTouchAction.UP, y = 0f, time = 60)).single()
        assertEquals(PokerNavigationEffect.SCROLLED, scroll.navigationEffect)
        assertEquals(PokerPileAnchor("second-card", 2), navigation.anchor(second))
        assertEquals(2, changed)
        val visible = navigation.metadata().toPokerPileRenderProjection(
            cardTextByLocator = mapOf(first to "first", second to "second"),
            anchorByLocator = navigation.anchors(),
        ).visiblePage
        assertEquals(2, visible?.anchor?.scrollOffset)
    }

    private fun swipe(fingers: Int, deltaY: Float): PokerOperation {
        val adapter = adapter()
        adapter.onTouchEvent(touch(PokerTouchAction.DOWN, pointers = 1, time = 10))
        if (fingers == 2) adapter.onTouchEvent(touch(PokerTouchAction.POINTER_DOWN, pointers = 2, time = 11))
        adapter.onTouchEvent(touch(PokerTouchAction.MOVE, pointers = fingers, y = deltaY, time = 20))
        return adapter.onTouchEvent(touch(PokerTouchAction.UP, pointers = fingers, y = deltaY, time = 30)).last().interaction.operation!!
    }

    private fun adapter(
        nowMs: Long = 0,
        onNavigationChanged: () -> Unit = {},
    ) = PokerBuiltInInputAdapter(
        PokerInputController(navigation()),
        monotonicNowMs = { nowMs },
        onNavigationChanged = onNavigationChanged,
    )

    private fun androidAdapter(
        bindings: PokerBindingController,
        navigation: PokerNavigationReducer = navigation(),
        builtInResults: MutableList<PokerInputController.Result> = mutableListOf(),
        onBindingChanged: () -> Unit = {},
    ) = PokerAndroidInputAdapter(
        builtIn = PokerBuiltInInputAdapter(
            controller = PokerInputController(navigation),
            bindings = bindings,
            onResult = builtInResults::add,
        ),
        remote = PokerRemoteInputAdapter(
            controller = PokerInputController(navigation),
            bindings = bindings,
            onBindingChanged = onBindingChanged,
        ),
    )

    private fun androidKey(
        kind: PokerAndroidInputDeviceKind,
        descriptor: String?,
        keyCode: Int,
        action: Int,
        time: Long,
        repeatCount: Int = 0,
    ) = PokerAndroidKeyEvent(kind, descriptor, keyCode, action, time, repeatCount)

    private fun navigation(): PokerNavigationReducer = PokerNavigationReducer(viewportLineCount = 3).also { navigation ->
        navigation.attach(
            first,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("first-card", collapsedLineCount = 6))),
        )
        navigation.attach(
            second,
            ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 2,
            layout = PokerPileLayout(listOf(PokerCardLayout("second-card", collapsedLineCount = 6))),
        )
        navigation.view(first)
    }

    private fun touch(
        action: PokerTouchAction,
        pointers: Int = 1,
        y: Float = 0f,
        time: Long,
    ) = PokerTouchEvent(action, pointers, y = y, eventTimeMs = time)

    private companion object {
        val first = CodexThreadLocator("spark", "first")
        val second = CodexThreadLocator("u4090", "second")
    }
}
