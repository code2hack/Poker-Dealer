package com.code2hack.dealer

import android.content.res.AssetManager
import com.code2hack.pokerdealer.domain.MorseCompletionEngine
import com.code2hack.pokerdealer.domain.MorseCompletionDictionary
import com.code2hack.pokerdealer.domain.MorseDictionaryEntry
import java.security.MessageDigest

internal const val MORSE_COMPLETION_ASSET = "morse/scowl-esdb-american-60.tsv"
internal const val MORSE_COMPLETION_ASSET_SHA256 =
    "0128ff7b08c7f068aaefe87095789fba47fcefae3c77850bf00dce08dad1c491"

internal object DealerMorseCompletionDictionary {
    fun load(assets: AssetManager): List<MorseDictionaryEntry> {
        val bytes = assets.open(MORSE_COMPLETION_ASSET).use { it.readBytes() }
        check(sha256(bytes) == MORSE_COMPLETION_ASSET_SHA256) {
            "Morse completion dictionary digest mismatch"
        }
        return MorseCompletionDictionary.parse(bytes.decodeToString().lineSequence())
    }

    fun suggest(
        prefix: String,
        dictionary: List<MorseDictionaryEntry>,
    ) = MorseCompletionEngine.suggest(prefix, dictionary)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
