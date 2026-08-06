package com.code2hack.poker

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.StatFs
import com.code2hack.pokerdealer.protocol.POKER_ASR_MAX_AUDIO_QUEUE_BYTES
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
    private val storageAvailable: () -> Boolean = { true },
    private val sourceAvailable: () -> Boolean = { true },
    private val onFailureReason: ((String) -> Unit)? = null,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        send: suspend (ByteArray) -> Boolean,
        onFailure: () -> Unit,
        onFailureReason: ((String) -> Unit)? = null,
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
        storageAvailable = { hasNativeStorage(context) },
        onFailureReason = onFailureReason,
    )

    private var recorder: PokerAsrRecorder? = null
    private var job: Job? = null
    private var failureReported = false

    fun start(): Boolean {
        if (job?.isActive == true) return true
        failureReported = false
        if (!permissionGranted()) {
            reportFailure("ASR unavailable")
            return false
        }
        if (!storageAvailable()) {
            reportFailure("ASR failed")
            return false
        }
        val minimum = runCatching { minimumBufferSize() }.getOrDefault(-1)
        if (minimum <= 0) {
            reportFailure("ASR unavailable")
            return false
        }
        val audio = recorderFactory(minimum)
        if (audio == null) {
            reportFailure("ASR unavailable")
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
            reportFailure("ASR unavailable")
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
                if (!permissionGranted()) {
                    reportFailure("ASR unavailable")
                    return
                }
                if (!storageAvailable() || !sourceAvailable()) {
                    reportFailure("ASR failed")
                    return
                }
                val count = audio.read(buffer, 0, buffer.size)
                if (count < 0) {
                    reportFailure("ASR failed")
                    return
                }
                if (count > 0) {
                    if (count % 2 != 0 || !send(buffer.copyOf(count))) {
                        reportFailure("ASR failed")
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

    private fun reportFailure(reason: String) {
        if (failureReported) return
        failureReported = true
        onFailureReason?.invoke(reason) ?: onFailure()
    }

    private companion object {
        fun hasNativeStorage(context: Context): Boolean = runCatching {
            val stats = StatFs(context.filesDir.path)
            stats.availableBytes >= POKER_ASR_MAX_AUDIO_QUEUE_BYTES
        }.getOrDefault(true)
    }
}
