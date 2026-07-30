package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class CorruptRetainedCardCacheException(cause: Throwable) :
    IllegalStateException("Discarded corrupt retained-card cache", cause)

class RetainedCardStore(
    private val root: File,
) {
    private val serializer = ListSerializer(Card.serializer())
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun read(locator: CodexThreadLocator): List<Card> = withContext(Dispatchers.IO) {
        val file = file(locator)
        if (!file.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (failure: SerializationException) {
                check(file.delete()) { "Unable to discard corrupt retained-card cache" }
                throw CorruptRetainedCardCacheException(failure)
            }
        }
    }

    suspend fun write(locator: CodexThreadLocator, cards: List<Card>) = withContext(Dispatchers.IO) {
        // ponytail: atomic whole-thread snapshots; add a journal only if retained-output profiling requires it.
        require(root.isDirectory || root.mkdirs()) { "Unable to create retained-card storage" }
        val target = file(locator)
        val temporary = File.createTempFile("${target.name}.", ".tmp", root)
        try {
            temporary.writeText(json.encodeToString(serializer, cards))
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    suspend fun delete(locator: CodexThreadLocator) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file(locator).toPath())
    }

    private fun file(locator: CodexThreadLocator): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${locator.hostId}\u0000${locator.threadId}".toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        return File(root, "$digest.json")
    }
}
