package com.code2hack.poker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerCamera2ControllerTest {
    @Test
    fun `Camera2 lifecycle fences open preview capture close and reconnect`() {
        val lifecycle = PhotoCamera2Lifecycle()

        assertEquals(PhotoCamera2LifecyclePhase.CLOSED, lifecycle.phase)
        assertTrue(lifecycle.beginOpening())
        assertEquals(PhotoCamera2LifecyclePhase.OPENING, lifecycle.phase)
        assertFalse(lifecycle.beginOpening())
        lifecycle.previewReady()
        assertEquals(PhotoCamera2LifecyclePhase.PREVIEW, lifecycle.phase)
        assertTrue(lifecycle.beginCapture())
        assertEquals(PhotoCamera2LifecyclePhase.CAPTURING, lifecycle.phase)
        assertFalse(lifecycle.beginCapture())
        lifecycle.captureFinished()
        assertEquals(PhotoCamera2LifecyclePhase.PREVIEW, lifecycle.phase)
        lifecycle.close()
        assertEquals(PhotoCamera2LifecyclePhase.CLOSED, lifecycle.phase)
        assertFalse(lifecycle.beginCapture())
        assertTrue(lifecycle.beginOpening())
        lifecycle.close()
        assertEquals(PhotoCamera2LifecyclePhase.CLOSED, lifecycle.phase)
    }

    @Test
    fun `Camera2 contract pins native preview JPEG orientation and zoom`() {
        assertEquals(480, PHOTO_CAMERA2_SPEC.previewWidth)
        assertEquals(640, PHOTO_CAMERA2_SPEC.previewHeight)
        assertEquals(4032, PHOTO_CAMERA2_SPEC.jpegWidth)
        assertEquals(3024, PHOTO_CAMERA2_SPEC.jpegHeight)
        assertEquals(270, PHOTO_CAMERA2_SPEC.sensorOrientation)
        assertEquals(1f, PHOTO_CAMERA2_SPEC.minZoom)
        assertEquals(8f, PHOTO_CAMERA2_SPEC.maxZoom)
    }
}
