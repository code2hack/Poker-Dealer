package com.code2hack.dealer.asr

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.abs

internal class DealerAsrOfflineSpoolStore(
    private val root: File,
    private val hasRoomFor: (Long) -> Boolean = { true },
) {
    fun purge() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun open(prefix: String): DealerAsrOfflineSpool {
        require(prefix.matches(SESSION_PREFIX)) { "invalid ASR spool session" }
        val directory = root.resolve("$prefix-${UUID.randomUUID()}")
        if (!directory.mkdirs()) throw DealerAsrOfflineFailure("spool-open-failed")
        return try {
            DealerAsrOfflineSpool(directory, hasRoomFor)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private companion object {
        val SESSION_PREFIX = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

internal class DealerAsrOfflineSpool internal constructor(
    private val directory: File,
    private val hasRoomFor: (Long) -> Boolean,
) {
    private val lock = Any()
    private var active = newActiveFile()
    private var output = FileOutputStream(active)
    private var bytes = 0L
    private var closed = false

    val isEmpty: Boolean
        get() = synchronized(lock) { bytes == 0L }

    fun append(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        require(pcm16.size % 2 == 0) { "PCM16 data must contain complete samples" }
        synchronized(lock) {
            check(!closed) { "ASR spool is closed" }
            val hasRoom = runCatching { hasRoomFor(pcm16.size.toLong()) }
                .getOrElse { failLocked("spool-space-check-failed", it) }
            if (!hasRoom) failLocked("insufficient-storage")
            try {
                output.write(pcm16)
                bytes += pcm16.size
            } catch (failure: Throwable) {
                failLocked("spool-write-failed", failure)
            }
        }
    }

    /** Closes the current file and opens the next one before its caller decodes the returned file. */
    fun rotate(): File? = synchronized(lock) {
        check(!closed) { "ASR spool is closed" }
        if (bytes == 0L) return@synchronized null
        try {
            closeOutputLocked()
        } catch (failure: Throwable) {
            failLocked("spool-rotate-failed", failure)
        }
        val settled = active
        try {
            active = newActiveFile()
            output = FileOutputStream(active)
            bytes = 0L
            settled
        } catch (failure: Throwable) {
            failLocked("spool-rotate-failed", failure)
        }
    }

    fun clearActive() = synchronized(lock) {
        check(!closed) { "ASR spool is closed" }
        try {
            closeOutputLocked()
        } catch (failure: Throwable) {
            failLocked("spool-reset-failed", failure)
        }
        active.delete()
        try {
            active = newActiveFile()
            output = FileOutputStream(active)
            bytes = 0L
        } catch (failure: Throwable) {
            failLocked("spool-reset-failed", failure)
        }
    }

    fun deleteSettled(file: File) {
        if (!file.toPath().normalize().startsWith(directory.toPath().normalize())) return
        file.delete()
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        runCatching { output.close() }
        directory.deleteRecursively()
    }

    private fun newActiveFile(): File = File.createTempFile("capture-", ".pcm16", directory)

    private fun closeOutputLocked() {
        output.flush()
        output.close()
    }

    private fun failLocked(reason: String, cause: Throwable? = null): Nothing {
        runCatching { output.close() }
        closed = true
        directory.deleteRecursively()
        throw DealerAsrOfflineFailure(reason, cause)
    }
}

internal class DealerAsrOfflineFailure(
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException(reason, cause)

internal class DealerAsrOfflineSession(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    private val spool: DealerAsrOfflineSpool,
) : DealerAsrRecognizer {
    private val state = Any()
    private var closed = false
    private var settling = false
    private var resourcesClosed = false

    override fun acceptPcm16(pcm: ByteArray) {
        synchronized(state) { check(!closed) { "ASR session is closed" } }
        spool.append(pcm)
    }

    override fun provisionalText(): String {
        synchronized(state) { check(!closed) { "ASR session is closed" } }
        return ""
    }

    override fun commitSlice(): String {
        val settled = synchronized(state) {
            check(!closed) { "ASR session is closed" }
            check(!settling) { "ASR slice settlement is already in progress" }
            settling = true
            spool.rotate()
        } ?: run {
            synchronized(state) { settling = false }
            return ""
        }

        return try {
            decode(settled)
        } catch (failure: Throwable) {
            synchronized(state) { closed = true }
            spool.close()
            throw failure
        } finally {
            spool.deleteSettled(settled)
            synchronized(state) { settling = false }
        }
    }

    override fun discardSlice() {
        synchronized(state) {
            check(!closed) { "ASR session is closed" }
            check(!settling) { "ASR slice settlement is already in progress" }
        }
        spool.clearActive()
    }

    override fun close() {
        synchronized(state) {
            if (resourcesClosed) return
            closed = true
            resourcesClosed = true
        }
        try {
            spool.close()
        } finally {
            try {
                vad.release()
            } finally {
                recognizer.release()
            }
        }
    }

    private fun decode(file: File): String {
        vad.reset()
        return try {
            DealerAsrOfflineSegmenter(vad::compute)
                .segments(file)
                .map { samples ->
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        recognizer.decode(stream)
                        recognizer.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                }
                .filter(String::isNotBlank)
                .joinToString(" ")
        } finally {
            vad.reset()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Streams a PCM16 spool through a bounded 15-second window. Silence and then
 * low-energy blocks before the limit are preferred over an exact hard cut.
 */
internal class DealerAsrOfflineSegmenter(
    private val speechProbability: (FloatArray) -> Float,
    private val maxSegmentSamples: Int = 15 * 16_000,
    private val blockSamples: Int = 512,
    private val searchRadiusSamples: Int = 16_000,
    private val speechThreshold: Float = 0.2f,
) {
    init {
        require(maxSegmentSamples > blockSamples)
        require(blockSamples > 0)
    }

    fun segments(file: File): Sequence<FloatArray> = sequence {
        FileInputStream(file).use { input ->
            val buffer = FloatArray(maxSegmentSamples + blockSamples)
            val blocks = mutableListOf<SpeechBlock>()
            val pcm = ByteArray(blockSamples * 2)
            var size = 0
            while (true) {
                val byteCount = readBlock(input, pcm)
                if (byteCount == 0) break
                if (byteCount % 2 != 0) throw DealerAsrOfflineFailure("spool-format-invalid")
                val samples = byteCount / 2
                val chunk = FloatArray(samples) { index ->
                    val low = pcm[index * 2].toInt() and 0xff
                    val high = pcm[index * 2 + 1].toInt()
                    ((high shl 8) or low).toShort() / 32768.0f
                }
                chunk.copyInto(buffer, size)
                blocks += SpeechBlock(
                    start = size,
                    length = samples,
                    speech = samples == blockSamples && speechProbability(chunk) >= speechThreshold,
                    energy = chunk.fold(0.0) { total, sample -> total + sample * sample }.toFloat(),
                )
                size += samples
                if (size >= maxSegmentSamples) {
                    val boundary = chooseBoundary(blocks)
                    yield(buffer.copyOf(boundary))
                    buffer.copyInto(buffer, 0, boundary, size)
                    size -= boundary
                    blocks.removeAll { it.start + it.length <= boundary }
                    blocks.replaceAll { block ->
                        block.copy(start = (block.start - boundary).coerceAtLeast(0))
                    }
                }
            }
            if (size > 0) yield(buffer.copyOf(size))
        }
    }

    private fun chooseBoundary(blocks: List<SpeechBlock>): Int {
        val limit = maxSegmentSamples
        val lower = (limit - searchRadiusSamples).coerceAtLeast(1)
        blocks.asSequence()
            .filter { !it.speech && it.start in lower until limit }
            .minWithOrNull(compareBy<SpeechBlock>({ abs(limit - it.start) }, { it.start }))
            ?.let { return it.start.coerceAtLeast(1) }
        blocks.asSequence()
            .filter { it.start in lower until limit }
            .minWithOrNull(compareBy<SpeechBlock>({ it.energy }, { abs(limit - it.start) }))
            ?.let { return it.start.coerceAtLeast(1) }
        return limit
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return count
    }

    private data class SpeechBlock(
        val start: Int,
        val length: Int,
        val speech: Boolean,
        val energy: Float,
    )
}
