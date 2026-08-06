package com.code2hack.poker

import java.util.ArrayDeque
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PokerAsrCaptureTest {
    @Test
    fun `permission denial stops before recorder creation`() = runTest {
        var factoryCalls = 0
        var failures = 0
        val capture = PokerAsrCapture(
            scope = this,
            send = { true },
            onFailure = { failures++ },
            permissionGranted = { false },
            minimumBufferSize = { error("permission must gate buffer setup") },
            recorderFactory = {
                factoryCalls++
                error("permission must gate recorder creation")
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(capture.start())
        assertEquals(1, failures)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `capture emits whole signed pcm16 chunks in order`() = runTest {
        val recorder = FakeRecorder(
            byteArrayOf(1, 0, -2, 127),
            byteArrayOf(3, 0),
        )
        val frames = mutableListOf<ByteArray>()
        val minimums = mutableListOf<Int>()
        val capture = PokerAsrCapture(
            scope = this,
            send = {
                frames += it
                true
            },
            onFailure = { error("valid PCM must not fail") },
            permissionGranted = { true },
            minimumBufferSize = { POKER_ASR_FRAME_BYTES },
            recorderFactory = {
                minimums += it
                recorder
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(capture.start())
        runCurrent()

        assertEquals(listOf(POKER_ASR_FRAME_BYTES), minimums)
        assertEquals(
            listOf(
                byteArrayOf(1, 0, -2, 127).toList(),
                byteArrayOf(3, 0).toList(),
            ),
            frames.map(ByteArray::toList),
        )
        capture.stop()
        assertTrue(recorder.released)
    }

    @Test
    fun `odd pcm16 read terminates capture instead of guessing`() = runTest {
        val recorder = FakeRecorder(byteArrayOf(1, 0, 2))
        var failures = 0
        val capture = PokerAsrCapture(
            scope = this,
            send = { error("odd PCM must not be sent") },
            onFailure = { failures++ },
            permissionGranted = { true },
            minimumBufferSize = { POKER_ASR_FRAME_BYTES },
            recorderFactory = { recorder },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(capture.start())
        runCurrent()

        assertEquals(1, failures)
        assertTrue(recorder.released)
    }

    private class FakeRecorder(vararg chunks: ByteArray) : PokerAsrRecorder {
        private val chunks = ArrayDeque(chunks.toList())
        override var isRecording: Boolean = false
            private set
        var released = false
            private set

        override fun start() {
            isRecording = true
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            if (chunks.isEmpty()) {
                isRecording = false
                return 0
            }
            val chunk = chunks.removeFirst()
            check(chunk.size <= size)
            chunk.copyInto(buffer, offset)
            if (chunks.isEmpty()) isRecording = false
            return chunk.size
        }

        override fun stop() {
            isRecording = false
        }

        override fun release() {
            released = true
            isRecording = false
        }
    }
}
