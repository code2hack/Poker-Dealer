package com.code2hack.pokerdealer.protocol

data class TextChunk(
    val index: Int,
    val count: Int,
    val text: String,
) {
    val utf8Bytes: Int get() = text.toByteArray(Charsets.UTF_8).size
}
object Utf8TextChunker {
    fun chunk(text: String, maxUtf8Bytes: Int = DEFAULT_TEXT_CHUNK_BYTES): List<TextChunk> {
        require(maxUtf8Bytes > 0) { "maxUtf8Bytes must be positive" }
        if (text.isEmpty()) return listOf(TextChunk(index = 0, count = 1, text = ""))

        val parts = mutableListOf<String>()
        var partStart = 0
        var index = 0
        var currentBytes = 0

        while (index < text.length) {
            val charCount = if (
                Character.isHighSurrogate(text[index]) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) {
                2
            } else {
                1
            }
            val tokenBytes = text.substring(index, index + charCount).toByteArray(Charsets.UTF_8).size
            require(tokenBytes <= maxUtf8Bytes) {
                "maxUtf8Bytes is too small for one UTF-8 code point"
            }

            if (currentBytes + tokenBytes > maxUtf8Bytes) {
                parts += text.substring(partStart, index)
                partStart = index
                currentBytes = 0
            }
            currentBytes += tokenBytes
            index += charCount
        }
        parts += text.substring(partStart)

        return parts.mapIndexed { chunkIndex, part ->
            TextChunk(index = chunkIndex, count = parts.size, text = part)
        }
    }

    fun reassemble(chunks: Collection<TextChunk>): String {
        require(chunks.isNotEmpty()) { "At least one chunk is required" }
        val expectedCount = chunks.first().count
        require(expectedCount > 0) { "Chunk count must be positive" }
        require(chunks.size == expectedCount) { "Incomplete chunk set" }
        require(chunks.all { it.count == expectedCount }) { "Mismatched chunk counts" }

        val ordered = chunks.sortedBy(TextChunk::index)
        require(ordered.map(TextChunk::index) == (0 until expectedCount).toList()) {
            "Chunk indexes must be contiguous"
        }
        return ordered.joinToString(separator = "", transform = TextChunk::text)
    }
}
