package com.code2hack.dealer.asr

import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrCaptureTest {
    @Test
    fun `permission denial does not request focus or create a recorder`() = runBlocking {
        val focus = FakeFocus()
        var recorderCalls = 0
        var failure: String? = null
        val capture = DealerAsrCapture(
            scope = this,
            send = { _, _ -> true },
            onFailure = { failure = it },
            permissionGranted = { false },
            minimumBufferSize = { error("permission must gate buffer setup") },
            recorderFactory = {
                recorderCalls++
                error("permission must gate recorder creation")
            },
            audioFocus = focus,
            dispatcher = Dispatchers.Unconfined,
        )

        assertFalse(capture.start())
        assertEquals("dealer-microphone-permission-denied", failure)
        assertEquals(0, focus.requests)
        assertEquals(0, recorderCalls)
    }

    @Test
    fun `dealer source owns contiguous sample offsets and focus loss stops it`() = runBlocking {
        val focus = FakeFocus()
        val recorder = FakeRecorder(byteArrayOf(1, 0, 2, 0))
        val offsets = mutableListOf<Long>()
        var failure: String? = null
        val capture = DealerAsrCapture(
            scope = this,
            send = { offset, _ ->
                offsets += offset
                focus.lose()
                true
            },
            onFailure = { failure = it },
            permissionGranted = { true },
            minimumBufferSize = { DEALER_ASR_FRAME_BYTES },
            recorderFactory = { recorder },
            audioFocus = focus,
            dispatcher = Dispatchers.Unconfined,
        )

        assertTrue(capture.start())

        assertEquals(listOf(0L), offsets)
        assertEquals("dealer-audio-focus-lost", failure)
        assertTrue(recorder.released)
        capture.stop()
    }

    @Test
    fun `permission revocation after start never switches source`() = runBlocking {
        val focus = FakeFocus()
        val recorder = FakeRecorder(byteArrayOf(1, 0))
        var permission = true
        var failure: String? = null
        val capture = DealerAsrCapture(
            scope = this,
            send = { _, _ ->
                permission = false
                true
            },
            onFailure = { failure = it },
            permissionGranted = { permission },
            minimumBufferSize = { DEALER_ASR_FRAME_BYTES },
            recorderFactory = { recorder },
            audioFocus = focus,
            dispatcher = Dispatchers.Unconfined,
        )

        assertTrue(capture.start())

        assertEquals("dealer-microphone-permission-revoked", failure)
        assertTrue(recorder.released)
    }

    private class FakeFocus : DealerAsrAudioFocus {
        var requests = 0
        private var onLoss: (() -> Unit)? = null

        override fun request(onLoss: () -> Unit): Boolean {
            requests++
            this.onLoss = onLoss
            return true
        }

        override fun abandon() = Unit

        fun lose() = onLoss?.invoke()
    }

    private class FakeRecorder(vararg chunks: ByteArray) : DealerAsrRecorder {
        private val chunks = ArrayDeque(chunks.toList())
        override var isRecording: Boolean = false
            private set
        var released = false
            private set

        override fun start() {
            isRecording = true
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            if (chunks.isEmpty()) return 0
            val chunk = chunks.removeFirst()
            check(chunk.size <= size)
            chunk.copyInto(buffer, offset)
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
