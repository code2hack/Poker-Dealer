package com.code2hack.pokerdealer.protocol

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerPresentationTest {
    @Test
    fun `font updates use five percent steps and reject stale or equal conflicts`() {
        val controller = PokerFontScaleController()
        val next = controller.update(125)

        assertEquals(PokerFontScaleState(1, 125), next)
        assertEquals(PokerFontScaleInstallResult.DUPLICATE, controller.install(next))
        assertEquals(
            PokerFontScaleInstallResult.STALE,
            controller.install(PokerFontScaleState(0, 100)),
        )
        assertEquals(
            PokerFontScaleInstallResult.CONFLICT,
            controller.install(PokerFontScaleState(1, 130)),
        )
        assertThrows(IllegalArgumentException::class.java) { PokerFontScaleState(percent = 77) }
    }

    @Test
    fun `font persistence is atomic and corrupt state falls back to default`() {
        val root = Files.createTempDirectory("poker-font").toFile()
        val file = root.resolve("font.json")
        val store = FilePokerFontScaleStore(file)

        store.save(PokerFontScaleState(4, 150))
        assertEquals(PokerFontScaleState(4, 150), store.load())

        file.writeText("{")
        assertEquals(PokerFontScaleState(), store.load())
        assertFalse(file.exists())
    }

    @Test
    fun `notice slot replaces and fences expiry`() {
        val slot = PokerTransientNoticeSlot()
        val old = slot.show(PokerTransientNotice("old", 500))
        val newer = slot.show(PokerTransientNotice("new", 1_000))

        slot.expire(old.token)
        assertEquals(newer, slot.value)
        slot.expire(newer.token)
        assertEquals(null, slot.value)
        assertThrows(IllegalArgumentException::class.java) {
            PokerTransientNotice("too long", 750)
        }
    }

    @Test
    fun `diagnostics payload contains only content free fields`() {
        val payload = PokerDiagnosticsProtocol.payload(
            PokerClientDiagnostics(
                unreadCount = 2,
                wakeCapability = PokerWakeCapability.AVAILABLE,
                font = PokerFontScaleState(3, 110),
            ),
        )

        assertTrue("unreadCount" in payload || "unread_count" in payload)
        assertNotEquals("card text", payload.toString())
        assertFalse(payload.keys.any { it.contains("card", ignoreCase = true) })
    }
}
