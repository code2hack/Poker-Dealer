package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.MorseCompletionDictionary
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseCompletionDictionaryTest {
    @Test
    fun `pinned American level 60 artifact has the recorded digest and entries`() {
        val asset = listOf(
            Path.of("apps/dealer/src/main/assets").resolve(MORSE_COMPLETION_ASSET),
            Path.of("src/main/assets").resolve(MORSE_COMPLETION_ASSET),
        ).firstOrNull(Files::isRegularFile) ?: error("Morse completion asset is missing")
        val bytes = Files.readAllBytes(asset)

        assertEquals(MORSE_COMPLETION_ASSET_SHA256, sha256(bytes))
        val entries = MorseCompletionDictionary.parse(bytes.decodeToString().lineSequence())
        assertEquals(77_103, entries.size)
        assertEquals("a", entries.first().word)
        assertEquals("zymurgy", entries.last().word)
        assertTrue(entries.any { it.word == "zygotic" && it.commonness == 60 })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
