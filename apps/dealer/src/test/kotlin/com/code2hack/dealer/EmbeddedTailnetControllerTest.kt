package com.code2hack.dealer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedTailnetControllerTest {
    @Test
    fun parsesConnectedAndDegradedStatusWithoutPromotingUnknownState() {
        val connected = """
            {"state":"connected","nodeName":"dealer","path":"direct"}
        """.trimIndent().toEmbeddedTailnetStatus()
        val degraded = """
            {"state":"degraded","nodeName":"dealer","path":"relayed","relay":"hkg","health":["relay only"]}
        """.trimIndent().toEmbeddedTailnetStatus()
        val unknown = """{"state":"future"}""".toEmbeddedTailnetStatus()

        assertEquals(EmbeddedTailnetConnectionState.CONNECTED, connected.state)
        assertEquals("direct", connected.path)
        assertNull(connected.error)
        assertEquals(EmbeddedTailnetConnectionState.DEGRADED, degraded.state)
        assertEquals("hkg", degraded.relay)
        assertEquals(listOf("relay only"), degraded.health)
        assertEquals(EmbeddedTailnetConnectionState.ERROR, unknown.state)
    }

    @Test
    fun malformedStatusFailsClosed() {
        val malformed = "{".toEmbeddedTailnetStatus()

        assertEquals(EmbeddedTailnetConnectionState.ERROR, malformed.state)
        assertEquals(true, malformed.error?.startsWith("Invalid embedded-tailnet status") == true)
    }
}
