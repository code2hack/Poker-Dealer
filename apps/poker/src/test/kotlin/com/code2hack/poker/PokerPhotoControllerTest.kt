package com.code2hack.poker

import org.junit.Assert.assertEquals
import org.junit.Test

class PokerPhotoControllerTest {
    @Test
    fun `photo zoom steps stay within the camera range`() {
        assertEquals(1.25f, photoZoomStep(1f, increase = true), 0.001f)
        assertEquals(1f, photoZoomStep(1f, increase = false), 0.001f)
        assertEquals(8f, photoZoomStep(8f, increase = true), 0.001f)
        assertEquals(1f, photoZoomStep(1f, increase = false), 0.001f)
    }
}
