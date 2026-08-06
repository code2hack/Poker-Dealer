package com.code2hack.dealer.asr

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class DealerAsrOfflineSpoolTest {
    @Test
    fun segmentationPrefersSilenceAndBoundsLongRecordings() {
        val root = Files.createTempDirectory("dealer-asr-segments").toFile()
        try {
            val shortFile = root.resolve("short.pcm16")
            Files.write(shortFile.toPath(), pcm16(ShortArray(24) { index ->
                if (index in 12 until 16) 0 else 1_000
            }))
            val shortSegments = DealerAsrOfflineSegmenter(
                speechProbability = { chunk -> if (chunk[0] == 0f) 0f else 1f },
                maxSegmentSamples = 16,
                blockSamples = 4,
                searchRadiusSamples = 8,
            ).segments(shortFile).toList()
            assertEquals(listOf(12, 12), shortSegments.map(FloatArray::size))

            val longFile = root.resolve("long.pcm16")
            Files.write(longFile.toPath(), pcm16(ShortArray(15 * 16_000 + 123) { 1_000 }))
            val longSegments = DealerAsrOfflineSegmenter(speechProbability = { 1f })
                .segments(longFile).toList()
            assertTrue(longSegments.all { it.size <= 15 * 16_000 })
            assertEquals(15 * 16_000 + 123, longSegments.sumOf(FloatArray::size))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rotationOpensCaptureFileBeforeSettlementReadsTheOldFile() {
        val root = Files.createTempDirectory("dealer-asr-rotation").toFile()
        try {
            val spool = DealerAsrOfflineSpoolStore(root).open("session")
            val first = pcm16(shortArrayOf(1, 2))
            val second = pcm16(shortArrayOf(3, 4, 5))
            spool.append(first)
            val settled = spool.rotate()!!
            val decodeStarted = CountDownLatch(1)
            val releaseDecode = CountDownLatch(1)
            var decodedBytes = -1L
            val decoder = Thread {
                decodeStarted.countDown()
                releaseDecode.await(5, TimeUnit.SECONDS)
                decodedBytes = Files.size(settled.toPath())
            }
            decoder.start()
            assertTrue(decodeStarted.await(5, TimeUnit.SECONDS))
            spool.append(second)
            releaseDecode.countDown()
            decoder.join(5_000)

            assertEquals(first.size.toLong(), decodedBytes)
            val next = spool.rotate()!!
            assertEquals(second.size.toLong(), Files.size(next.toPath()))
            spool.deleteSettled(next)
            spool.deleteSettled(settled)
            spool.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lowStorageFailureRemovesTemporarySpool() {
        val root = Files.createTempDirectory("dealer-asr-low-storage").toFile()
        try {
            val spool = DealerAsrOfflineSpoolStore(root, hasRoomFor = { false }).open("session")
            assertThrows(DealerAsrOfflineFailure::class.java) {
                spool.append(pcm16(shortArrayOf(1)))
            }
            assertFalse(root.listFiles()?.any() == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupPurgeDeletesAbandonedSpoolsWithoutReadingThem() {
        val root = Files.createTempDirectory("dealer-asr-purge").toFile()
        try {
            root.resolve("orphan").apply {
                mkdirs()
                resolve("capture.pcm16").writeBytes(pcm16(shortArrayOf(9)))
            }
            DealerAsrOfflineSpoolStore(root).purge()
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedSegmentSettlementDoesNotPublishPartialText() {
        val root = Files.createTempDirectory("dealer-asr-atomic").toFile()
        try {
            val file = root.resolve("slice.pcm16")
            Files.write(file.toPath(), pcm16(ShortArray(24) { 1_000 }))
            var segment = 0
            var published: String? = null
            val failure = runCatching {
                val text = DealerAsrOfflineSegmenter(
                    speechProbability = { 1f },
                    maxSegmentSamples = 16,
                    blockSamples = 4,
                    searchRadiusSamples = 4,
                ).segments(file).map {
                    if (segment++ == 1) throw DealerAsrOfflineFailure("decode-failed")
                    "segment"
                }.joinToString(" ")
                published = text
            }
            assertTrue(failure.isFailure)
            assertEquals(2, segment)
            assertEquals(null, published)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pcm16(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
}
