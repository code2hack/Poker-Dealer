package com.code2hack.poker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Captures transient 16 kHz mono PCM16 only after Dealer has authorized ASR. */
internal class PokerAsrCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val send: suspend (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
) {
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    fun start(): Boolean {
        if (job?.isActive == true) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onFailure()
            return false
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            onFailure()
            return false
        }
        val audio = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, FRAME_BYTES),
            )
        }.getOrNull()
        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
            audio?.release()
            onFailure()
            return false
        }
        recorder = audio
        return runCatching {
            audio.startRecording()
            job = scope.launch(Dispatchers.IO) { readLoop(audio) }
            true
        }.getOrElse {
            audio.release()
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
            audio.release()
        }
        recorder = null
    }

    private suspend fun readLoop(audio: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(FRAME_BYTES)
        try {
            while (scope.isActive && audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count < 0) {
                    onFailure()
                    return
                }
                if (count > 0 && !send(buffer.copyOf(count))) {
                    onFailure()
                    return
                }
            }
        } finally {
            if (job?.isActive != true) runCatching { audio.stop() }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 2_048
    }
}
