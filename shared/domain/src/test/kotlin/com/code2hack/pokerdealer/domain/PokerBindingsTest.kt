package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerBindingsTest {
    @Test
    fun `glasses begin with one accepted control for every canonical operation`() {
        val map = PokerBindingMap.defaultGlasses()

        assertEquals(7, map.entries.size)
        assertEquals(setOf(PokerBindingDevice.GLASSES), map.devices)
        assertEquals(
            PokerGlassesGesture.DOUBLE_FINGER_SWIPE_FORWARD,
            map.controls(PokerBindingDevice.GLASSES, PokerOperation.RIGHT).single().gesture,
        )
    }

    @Test
    fun `learning a remote replaces one operation and removes physical collisions`() {
        val controller = PokerBindingController()
        val device = PokerBindingDevice.remote("remote-a")
        val control = PokerBindingControl.remote("remote-a", 42)

        controller.observeRemote(device.descriptor)
        controller.selectDevice(device)
        assertTrue(controller.beginLearning(PokerOperation.DOWN))
        assertEquals(PokerBindingCaptureResult.APPLIED, controller.capture(control))
        assertEquals(PokerOperation.DOWN, controller.map.operationFor(control))

        controller.beginLearning(PokerOperation.UP)
        assertEquals(PokerBindingCaptureResult.APPLIED, controller.capture(control))
        assertEquals(PokerOperation.UP, controller.map.operationFor(control))
        assertTrue(controller.map.controls(device, PokerOperation.DOWN).isEmpty())
        assertEquals(PokerBindingSyncStatus.PENDING, controller.state.syncStatus)
    }

    @Test
    fun `missing remote operations are valid and a canceled learn changes nothing`() {
        val control = PokerBindingControl.remote("remote-a", 42)
        val incomplete = PokerBindingMap(
            revision = 1,
            entries = listOf(PokerBindingEntry(PokerOperation.TAP, listOf(control))),
        )
        val controller = PokerBindingController(incomplete)
        controller.selectDevice(PokerBindingDevice.remote("remote-a"))
        val before = controller.map

        controller.beginLearning(PokerOperation.FN)
        controller.cancelLearning()

        assertEquals(before, controller.map)
        assertEquals(PokerBindingCaptureResult.IGNORED, controller.capture(control))
        assertFalse(controller.map.isManagedRemote("another-remote"))
    }

    @Test
    fun `temporary disconnect retains bindings but unbonding removes the exact descriptor`() {
        val controller = PokerBindingController()
        val device = PokerBindingDevice.remote("remote-a")
        val control = PokerBindingControl.remote("remote-a", 42)
        controller.observeRemote(device.descriptor)
        controller.selectDevice(device)
        controller.beginLearning(PokerOperation.TAP)
        controller.capture(control)
        val bound = controller.map

        controller.deviceDisconnected(device.descriptor)
        assertEquals(bound, controller.map)
        controller.forgetRemote(device.descriptor)
        assertFalse(controller.state.knownRemoteDescriptors.contains(device.descriptor))
        assertFalse(controller.map.isManagedRemote(device.descriptor))
    }

    @Test
    fun `one control cannot map to two operations`() {
        val control = PokerBindingControl.remote("remote-a", 42)

        assertThrows(IllegalArgumentException::class.java) {
            PokerBindingMap(
                revision = 1,
                entries = listOf(
                    PokerBindingEntry(PokerOperation.DOWN, listOf(control)),
                    PokerBindingEntry(PokerOperation.UP, listOf(control)),
                ),
            )
        }
    }

    @Test
    fun `a complete map and controller retain exactly one remote descriptor`() {
        assertThrows(IllegalArgumentException::class.java) {
            PokerBindingMap(
                revision = 1,
                entries = listOf(
                    PokerBindingEntry(
                        PokerOperation.DOWN,
                        listOf(PokerBindingControl.remote("remote-a", 1)),
                    ),
                    PokerBindingEntry(
                        PokerOperation.UP,
                        listOf(PokerBindingControl.remote("remote-b", 2)),
                    ),
                ),
            )
        }

        val controller = PokerBindingController()
        assertTrue(controller.observeRemote("remote-a"))
        assertFalse(controller.observeRemote("remote-b"))
        assertEquals(listOf("remote-a"), controller.state.knownRemoteDescriptors)
    }

    @Test
    fun `installer swaps complete maps and retains the last map on races`() {
        val receiver = PokerBindingController()
        val updated = receiver.map.bind(
            PokerBindingDevice.remote("remote-a"),
            PokerOperation.FN,
            PokerBindingControl.remote("remote-a", 7),
        )

        assertEquals(PokerBindingInstallResult.INSTALLED, receiver.install(updated))
        assertEquals(PokerBindingInstallResult.DUPLICATE, receiver.install(updated))
        assertEquals(
            PokerBindingInstallResult.STALE,
            receiver.install(PokerBindingMap.defaultGlasses()),
        )
        val beforeConflict = receiver.map
        val conflict = PokerBindingMap(
            revision = updated.revision,
            entries = listOf(
                PokerBindingEntry(
                    PokerOperation.FN,
                    listOf(PokerBindingControl.remote("remote-a", 8)),
                ),
            ),
        )
        assertEquals(PokerBindingInstallResult.CONFLICT, receiver.install(conflict))
        assertEquals(beforeConflict, receiver.map)
    }

    @Test
    fun `connection loss cancels learning without deleting the map`() {
        val controller = PokerBindingController()
        controller.observeRemote("remote-a")
        controller.selectDevice(PokerBindingDevice.remote("remote-a"))
        assertTrue(controller.beginLearning(PokerOperation.TAP))
        val before = controller.map

        controller.connectionLost()

        assertEquals(before, controller.map)
        assertEquals(null, controller.learningTarget)
    }

    @Test
    fun `authoritative snapshot replaces an unacknowledged same revision edit`() {
        val controller = PokerBindingController()
        controller.observeRemote("remote-a")
        controller.selectDevice(PokerBindingDevice.remote("remote-a"))
        assertTrue(controller.beginLearning(PokerOperation.TAP))
        assertEquals(
            PokerBindingCaptureResult.APPLIED,
            controller.capture(PokerBindingControl.remote("remote-a", 42)),
        )
        val localEdit = controller.map
        val authoritative = PokerBindingMap(
            revision = localEdit.revision,
            entries = listOf(
                PokerBindingEntry(
                    PokerOperation.FN,
                    listOf(PokerBindingControl.remote("remote-a", 7)),
                ),
            ),
        )

        assertEquals(
            PokerBindingInstallResult.INSTALLED,
            controller.installAuthoritative(authoritative),
        )
        assertEquals(authoritative, controller.map)
    }
}
