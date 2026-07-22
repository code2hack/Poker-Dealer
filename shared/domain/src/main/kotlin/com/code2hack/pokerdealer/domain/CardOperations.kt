package com.code2hack.pokerdealer.domain

enum class RevisionApplication { INSERTED, UPDATED, IGNORED_STALE }

class CardRevisionStore {
    private val cards = mutableMapOf<String, Card>()

    fun apply(card: Card): RevisionApplication {
        val current = cards[card.id]
        if (current != null && card.revision <= current.revision) {
            return RevisionApplication.IGNORED_STALE
        }
        cards[card.id] = card
        return if (current == null) RevisionApplication.INSERTED else RevisionApplication.UPDATED
    }

    fun get(cardId: String): Card? = cards[cardId]
}
fun splitCardTextAtNewlines(
    text: String,
    maxUtf8Bytes: Int = MAX_CARD_UTF8_BYTES,
): List<String> {
    require(maxUtf8Bytes > 0) { "maxUtf8Bytes must be positive" }
    if (text.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) return listOf(text)

    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var currentBytes = 0
    var cursor = 0

    while (cursor < text.length) {
        val newline = text.indexOf('\n', cursor)
        val endExclusive = if (newline == -1) text.length else newline + 1
        val line = text.substring(cursor, endExclusive)
        val lineBytes = line.toByteArray(Charsets.UTF_8).size

        if (lineBytes > maxUtf8Bytes) {
            if (current.isNotEmpty()) {
                parts += current.toString()
                current.clear()
                currentBytes = 0
            }
            parts += splitAtUtf8Boundary(line, maxUtf8Bytes)
        } else {
            if (currentBytes + lineBytes > maxUtf8Bytes) {
                parts += current.toString()
                current.clear()
                currentBytes = 0
            }
            current.append(line)
            currentBytes += lineBytes
        }
        cursor = endExclusive
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private fun splitAtUtf8Boundary(text: String, maxUtf8Bytes: Int): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var cursor = 0
    var bytes = 0
    while (cursor < text.length) {
        val chars = if (
            Character.isHighSurrogate(text[cursor]) &&
            cursor + 1 < text.length &&
            Character.isLowSurrogate(text[cursor + 1])
        ) 2 else 1
        val tokenBytes = text.substring(cursor, cursor + chars).toByteArray(Charsets.UTF_8).size
        require(tokenBytes <= maxUtf8Bytes) { "maxUtf8Bytes is too small for one code point" }
        if (bytes + tokenBytes > maxUtf8Bytes) {
            parts += text.substring(start, cursor)
            start = cursor
            bytes = 0
        }
        bytes += tokenBytes
        cursor += chars
    }
    if (start < text.length) parts += text.substring(start)
    return parts
}
