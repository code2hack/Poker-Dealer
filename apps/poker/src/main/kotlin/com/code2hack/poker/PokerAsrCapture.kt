package com.code2hack.poker

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val POKER_ASR_SAMPLE_RATE_HZ = 16_000
internal const val POKER_ASR_FRAME_BYTES = 2_048

internal interface PokerAsrRecorder {
    val isRecording: Boolean

    fun start()

    fun read(buffer: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun release()
}

private class AndroidPokerAsrRecorder(
    private val audio: AudioRecord,
) : PokerAsrRecorder {
    override val isRecording: Boolean
        get() = audio.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun start() = audio.startRecording()

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int = audio.read(buffer, offset, size)

    override fun stop() = audio.stop()

    override fun release() = audio.release()
}

@SuppressLint("MissingPermission")
private fun createAndroidPokerAsrRecorder(minimum: Int): PokerAsrRecorder? = runCatching {
    AudioRecord(
        MediaRecorder.AudioSource.DEFAULT,
        POKER_ASR_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minimum, POKER_ASR_FRAME_BYTES),
    )
}.getOrNull()?.let { audio ->
    if (audio.state == AudioRecord.STATE_INITIALIZED) {
        AndroidPokerAsrRecorder(audio)
    } else {
        audio.release()
        null
    }
}

/** Captures transient 16 kHz mono PCM16 only after Dealer has authorized ASR. */
internal class PokerAsrCapture internal constructor(
    private val scope: CoroutineScope,
    private val send: suspend (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val permissionGranted: () -> Boolean,
    private val minimumBufferSize: () -> Int,
    private val recorderFactory: (Int) -> PokerAsrRecorder?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        send: suspend (ByteArray) -> Boolean,
        onFailure: () -> Unit,
    ) : this(
        scope = scope,
        send = send,
        onFailure = onFailure,
        permissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        minimumBufferSize = {
            AudioRecord.getMinBufferSize(
                POKER_ASR_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        },
        recorderFactory = ::createAndroidPokerAsrRecorder,
        dispatcher = Dispatchers.IO,
    )

    private var recorder: PokerAsrRecorder? = null
    private var job: Job? = null

    fun start(): Boolean {
        if (job?.isActive == true) return true
        if (!permissionGranted()) {
            onFailure()
            return false
        }
        val minimum = runCatching { minimumBufferSize() }.getOrDefault(-1)
        if (minimum <= 0) {
            onFailure()
            return false
        }
        val audio = recorderFactory(minimum)
        if (audio == null) {
            onFailure()
            return false
        }
        recorder = audio
        return runCatching {
            audio.start()
            job = scope.launch(dispatcher) { readLoop(audio) }
            true
        }.getOrElse {
            runCatching { audio.release() }
            recorder = null
            onFailure()
            false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        recorder?.let { audio ->
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
        recorder = null
    }

    private suspend fun readLoop(audio: PokerAsrRecorder) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val buffer = ByteArray(POKER_ASR_FRAME_BYTES)
        try {
            while (scope.isActive && audio.isRecording) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count < 0) {
                    onFailure()
                    return
                }
                if (count > 0) {
                    if (count % 2 != 0 || !send(buffer.copyOf(count))) {
                        onFailure()
                        return
                    }
                }
            }
        } finally {
            if (recorder === audio) {
                runCatching { audio.stop() }
                runCatching { audio.release() }
                recorder = null
            }
        }
    }
}
