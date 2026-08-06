package com.code2hack.dealer.asr

import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrDownloadsTest {
    @Test
    fun queueInstallsAtomicallyAndFirstReadyPackBecomesDefault() = runBlocking {
        val fixture = fixture()
        val transport = FakeTransport(mapOf(fixture.entry.artifacts.single().canonicalUrl to fixture.bytes))
        val manager = manager(fixture.root, transport)
        try {
            val queued = manager.queue(fixture.entry)
            await { manager.stateFlow.value.jobs.singleOrNull()?.state == DealerAsrDownloadState.READY }

            val state = manager.stateFlow.value
            assertEquals(DealerAsrDownloadState.READY, state.jobs.single().state)
            assertEquals(queued.key, state.defaultPack)
            assertTrue(
                fixture.root.resolve("installed/${fixture.entry.id}/${fixture.entry.revision}/.ready.json").isFile,
            )
            assertEquals(
                fixture.bytes.toList(),
                fixture.root.resolve("installed/${fixture.entry.id}/${fixture.entry.revision}/model.onnx")
                    .readBytes().toList(),
            )
        } finally {
            manager.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun mirrorWrongBytesAreVisibleAndFallBackToCanonical() = runBlocking {
        val fixture = fixture()
        val mirror = "https://mirror.example/models/"
        val mirrorUrl = "$mirror${fixture.entry.artifacts.single().path}"
        val canonical = fixture.entry.artifacts.single().canonicalUrl
        val transport = FakeTransport(
            data = mapOf(mirrorUrl to "wrong".toByteArray(), canonical to fixture.bytes),
        )
        val manager = manager(fixture.root, transport)
        try {
            manager.queue(fixture.entry, mirror)
            await { manager.stateFlow.value.jobs.singleOrNull()?.state == DealerAsrDownloadState.READY }

            val job = manager.stateFlow.value.jobs.single()
            assertTrue(job.warning.orEmpty().startsWith("mirror-fallback:"))
            assertEquals(listOf(mirrorUrl, canonical), transport.requests.map(Request::url))
        } finally {
            manager.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun activePauseYieldsToQueuedWorkAndResumeUsesRangeValidator() = runBlocking {
        val fixture = fixture()
        val second = fixture.entry.copy(id = "second-pack", displayName = "Second")
        val transport = BlockingFirstTransport(fixture.entry.artifacts.single().canonicalUrl, fixture.bytes)
        val manager = manager(fixture.root, transport)
        try {
            val first = manager.queue(fixture.entry)
            val secondJob = manager.queue(second)
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == first.key }?.downloadedBytes ?: 0 > 0
            }
            manager.pause(first.key)
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == first.key }?.state ==
                    DealerAsrDownloadState.PAUSED
            }
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == secondJob.key }?.state ==
                    DealerAsrDownloadState.READY
            }
            manager.close()

            val resumedTransport = FakeTransport(
                data = mapOf(fixture.entry.artifacts.single().canonicalUrl to fixture.bytes),
            )
            val resumed = manager(fixture.root, resumedTransport)
            try {
                resumed.start()
                resumed.resume(first.key)
                await { resumed.stateFlow.value.jobs.firstOrNull { it.key == first.key }?.state == DealerAsrDownloadState.READY }
                val rangeRequest = resumedTransport.requests.first { it.url == fixture.entry.artifacts.single().canonicalUrl }
                assertTrue(rangeRequest.offset > 0)
                assertEquals("\"v1\"", rangeRequest.validator)
            } finally {
                resumed.close()
            }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun pausingQueuedPackDoesNotInterruptActivePack() = runBlocking {
        val fixture = fixture()
        val second = fixture.entry.copy(id = "second-pack", displayName = "Second")
        val transport = BlockingFirstTransport(fixture.entry.artifacts.single().canonicalUrl, fixture.bytes)
        val manager = manager(fixture.root, transport)
        try {
            val first = manager.queue(fixture.entry)
            val secondJob = manager.queue(second)
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == first.key }?.state ==
                    DealerAsrDownloadState.DOWNLOADING &&
                    manager.stateFlow.value.jobs.firstOrNull { it.key == secondJob.key }?.state ==
                    DealerAsrDownloadState.QUEUED
            }

            manager.pause(secondJob.key)
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == secondJob.key }?.state ==
                    DealerAsrDownloadState.PAUSED
            }
            delay(100)

            assertEquals(
                DealerAsrDownloadState.DOWNLOADING,
                manager.stateFlow.value.jobs.first { it.key == first.key }.state,
            )
            assertFalse(
                fixture.root.resolve("installed/${fixture.entry.id}/${fixture.entry.revision}/.ready.json").isFile,
            )
            manager.cancel(first.key)
        } finally {
            manager.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun cancellingQueuedPackDoesNotInterruptActivePackOrCreatePartialReadyInstall() = runBlocking {
        val fixture = fixture()
        val second = fixture.entry.copy(id = "second-pack", displayName = "Second")
        val transport = BlockingFirstTransport(fixture.entry.artifacts.single().canonicalUrl, fixture.bytes)
        val manager = manager(fixture.root, transport)
        try {
            val first = manager.queue(fixture.entry)
            val secondJob = manager.queue(second)
            await {
                manager.stateFlow.value.jobs.firstOrNull { it.key == first.key }?.state ==
                    DealerAsrDownloadState.DOWNLOADING &&
                    manager.stateFlow.value.jobs.firstOrNull { it.key == secondJob.key }?.state ==
                    DealerAsrDownloadState.QUEUED
            }

            manager.cancel(secondJob.key)
            await { manager.stateFlow.value.jobs.none { it.key == secondJob.key } }
            delay(100)

            assertEquals(
                DealerAsrDownloadState.DOWNLOADING,
                manager.stateFlow.value.jobs.first { it.key == first.key }.state,
            )
            assertFalse(
                fixture.root.resolve("installed/${fixture.entry.id}/${fixture.entry.revision}/.ready.json").isFile,
            )
            manager.cancel(first.key)
        } finally {
            manager.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun lowStorageFailsBeforeTransferAndCancellationRemovesPartials() = runBlocking {
        val fixture = fixture()
        val transport = FakeTransport(mapOf(fixture.entry.artifacts.single().canonicalUrl to fixture.bytes))
        val lowStorage = DealerAsrStorageSpace(availableBytes = { 1L }, lowStorageBytes = { 0L })
        val manager = manager(fixture.root, transport, lowStorage)
        try {
            manager.queue(fixture.entry)
            await { manager.stateFlow.value.jobs.singleOrNull()?.state == DealerAsrDownloadState.FAILED }
            assertEquals("insufficient-storage", manager.stateFlow.value.jobs.single().error)
            assertTrue(transport.requests.isEmpty())
        } finally {
            manager.close()
            fixture.root.deleteRecursively()
        }

        val blocking = BlockingFirstTransport(fixture.entry.artifacts.single().canonicalUrl, fixture.bytes)
        val cancellable = manager(fixture.root, blocking)
        try {
            val job = cancellable.queue(fixture.entry)
            await { cancellable.stateFlow.value.jobs.singleOrNull()?.state == DealerAsrDownloadState.DOWNLOADING }
            cancellable.cancel(job.key)
            await { cancellable.stateFlow.value.jobs.none { it.key == job.key } }
            assertFalse(fixture.root.resolve("partials/${fixture.entry.id}/${fixture.entry.revision}").exists())
        } finally {
            cancellable.close()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun mirrorUrlMustBeCredentialFreeHttps() {
        assertEquals("https://mirror.example/base/", normalizeDealerAsrMirrorUrl(" https://mirror.example/base "))
        listOf(
            "http://mirror.example/",
            "https://user:secret@mirror.example/",
            "https://mirror.example/?token=secret",
            "https://mirror.example/#fragment",
        ).forEach { value ->
            val failure = runCatching { normalizeDealerAsrMirrorUrl(value) }.exceptionOrNull()
            assertNotNull(failure)
        }
    }

    private fun manager(
        root: java.io.File,
        transport: DealerAsrDownloadTransport,
        storage: DealerAsrStorageSpace = DealerAsrStorageSpace(
            availableBytes = { Long.MAX_VALUE },
            lowStorageBytes = { 0L },
        ),
    ) = DealerAsrDownloadManager(
        stateFile = root.resolve("state.json"),
        partialRoot = root.resolve("partials"),
        installedRoot = root.resolve("installed"),
        transport = transport,
        storage = storage,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("dealer-asr-download").toFile()
        val bytes = "verified-model".toByteArray()
        val revision = "a".repeat(40)
        val digest = sha256(bytes)
        val entry = DealerAsrCatalog.parse(
            """
            {
              "schemaVersion": 1,
              "runtime": {"backend": "cpu", "engine": "onnxruntime", "adapters": ["PARAKEET_UNIFIED_STREAMING"]},
              "entries": [{
                "id": "parakeet-unified-en-0.6b-int8-streaming-560ms",
                "revision": "$revision",
                "displayName": "Parakeet",
                "family": "Parakeet",
                "adapter": "PARAKEET_UNIFIED_STREAMING",
                "source": {"repository": "test/repo", "revision": "$revision"},
                "artifacts": [{"path": "model.onnx", "bytes": ${bytes.size}, "sha256": "$digest"}],
                "downloadBytes": ${bytes.size},
                "temporaryBytes": ${bytes.size},
                "installedBytes": ${bytes.size},
                "languages": ["en"],
                "licenses": ["Test"],
                "backend": "cpu",
                "mode": "STREAMING",
                "defaultProfile": {"packId": "parakeet-unified-en-0.6b-int8-streaming-560ms", "revision": "$revision", "schemaVersion": 1, "settings": {"warmRetentionSeconds": 300}},
                "profileSchema": {"packId": "parakeet-unified-en-0.6b-int8-streaming-560ms", "revision": "$revision", "schemaVersion": 1, "fields": [{"name": "warmRetentionSeconds", "type": "integer", "default": 300}]}
              }]
            }
            """.trimIndent(),
            supportedAdapters = setOf(DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING),
        ).entries.single()
        return Fixture(root, bytes, entry)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class Fixture(
        val root: java.io.File,
        val bytes: ByteArray,
        val entry: DealerAsrCatalogEntry,
    )

    private data class Request(val url: String, val offset: Long, val validator: String?)

    private open class FakeTransport(
        private val data: Map<String, ByteArray>,
    ) : DealerAsrDownloadTransport {
        val requests = mutableListOf<Request>()

        override fun open(url: String, offset: Long, validator: String?): DealerAsrDownloadResponse {
            requests += Request(url, offset, validator)
            val bytes = data.getValue(url)
            val body = if (offset > 0) bytes.copyOfRange(offset.toInt(), bytes.size) else bytes
            return DealerAsrDownloadResponse(
                statusCode = if (offset > 0) 206 else 200,
                validator = "\"v1\"",
                contentRange = if (offset > 0) "bytes $offset-${bytes.lastIndex}/${bytes.size}" else null,
                contentLength = body.size.toLong(),
                body = body.inputStream(),
                disconnect = {},
            )
        }
    }

    private class BlockingFirstTransport(
        private val url: String,
        private val bytes: ByteArray,
    ) : FakeTransport(emptyMap()) {
        private var first = true

        override fun open(url: String, offset: Long, validator: String?): DealerAsrDownloadResponse {
            requests += Request(url, offset, validator)
            if (offset > 0) {
                val body = bytes.copyOfRange(offset.toInt(), bytes.size)
                return DealerAsrDownloadResponse(
                    statusCode = 206,
                    validator = "\"v1\"",
                    contentRange = "bytes $offset-${bytes.lastIndex}/${bytes.size}",
                    contentLength = body.size.toLong(),
                    body = body.inputStream(),
                    disconnect = {},
                )
            }
            val body = if (first) {
                first = false
                ChunkThenWaitInputStream(bytes)
            } else {
                bytes.inputStream()
            }
            return DealerAsrDownloadResponse(
                statusCode = 200,
                validator = "\"v1\"",
                contentRange = null,
                contentLength = bytes.size.toLong(),
                body = body,
                disconnect = {},
            )
        }
    }

    private class ChunkThenWaitInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        @Volatile private var closed = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index == 0) {
                val count = minOf(3, bytes.size)
                bytes.copyInto(buffer, offset, 0, count)
                index = count
                return count
            }
            while (!closed) Thread.sleep(5)
            return -1
        }

        override fun close() {
            closed = true
        }
    }
}
