package com.code2hack.dealer.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.code2hack.pokerdealer.protocol.POKER_ASR_MAX_AUDIO_BYTES
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DEALER_ASR_SAMPLE_RATE_HZ = 16_000
internal const val DEALER_ASR_FRAME_BYTES = POKER_ASR_MAX_AUDIO_BYTES

internal interface DealerAsrRecorder {
    val isRecording: Boolean

    fun start()

    fun read(buffer: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun release()
}

internal interface DealerAsrAudioFocus {
    fun request(onLoss: () -> Unit): Boolean

    fun abandon()
}

private class AndroidDealerAsrRecorder(
    private val audio: AudioRecord,
) : DealerAsrRecorder {
    override val isRecording: Boolean
        get() = audio.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun start() = audio.startRecording()

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int = audio.read(buffer, offset, size)

    override fun stop() = audio.stop()

    override fun release() = audio.release()
}

private class AndroidDealerAsrAudioFocus(
    context: Context,
) : DealerAsrAudioFocus {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) onLoss()
            }
            .build()
        request = focusRequest
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}

@SuppressLint("MissingPermission")
private fun createAndroidDealerAsrRecorder(minimum: Int): DealerAsrRecorder? = runCatching {
    AudioRecord(
        MediaRecorder.AudioSource.MIC,
        DEALER_ASR_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minimum, DEALER_ASR_FRAME_BYTES),
    )
}.getOrNull()?.let { audio ->
    if (audio.state == AudioRecord.STATE_INITIALIZED) {
        AndroidDealerAsrRecorder(audio)
    } else {
        audio.release()
        null
    }
}

/** Captures the Fold6 microphone only for one Dealer-authorized ASR session. */
internal class DealerAsrCapture internal constructor(
    private val scope: CoroutineScope,
    private val send: suspend (firstSampleOffset: Long, pcm16: ByteArray) -> Boolean,
    private val onFailure: (reason: String) -> Unit,
    private val permissionGranted: () -> Boolean,
    private val minimumBufferSize: () -> Int,
    private val recorderFactory: (Int) -> DealerAsrRecorder?,
    private val audioFocus: DealerAsrAudioFocus,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        send: suspend (firstSampleOffset: Long, pcm16: ByteArray) -> Boolean,
        onFailure: (reason: String) -> Unit,
    ) : this(
        scope = scope,
        send = send,
        onFailure = onFailure,
        permissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        minimumBufferSize = {
            AudioRecord.getMinBufferSize(
                DEALER_ASR_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        },
        recorderFactory = ::createAndroidDealerAsrRecorder,
        audioFocus = AndroidDealerAsrAudioFocus(context),
    )

    private var recorder: DealerAsrRecorder? = null
    private var job: Job? = null
    private var nextSampleOffset = 0L
    @Volatile
    private var stopping = false
    @Volatile
    private var failureReported = false

    fun start(): Boolean {
        if (job?.isActive == true) return true
        stopping = false
        failureReported = false
        nextSampleOffset = 0L
        if (!permissionGranted()) return fail("dealer-microphone-permission-denied")
        val minimum = runCatching { minimumBufferSize() }.getOrDefault(-1)
        if (minimum <= 0) return fail("dealer-microphone-buffer-unavailable")
        val focusGranted = runCatching {
            audioFocus.request { fail("dealer-audio-focus-lost") }
        }.getOrDefault(false)
        if (!focusGranted || stopping) {
            return fail("dealer-audio-focus-unavailable")
        }
        val audio = runCatching { recorderFactory(minimum) }.getOrNull()
        if (audio == null) return fail("dealer-microphone-unavailable")
        recorder = audio
        return runCatching {
            audio.start()
            job = scope.launch(dispatcher) { readLoop(audio) }
            true
        }.getOrElse {
            release(audio)
            audioFocus.abandon()
            fail("dealer-microphone-unavailable")
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        val audio = recorder
        recorder = null
        audio?.let(::release)
        audioFocus.abandon()
    }

    private suspend fun readLoop(audio: DealerAsrRecorder) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val buffer = ByteArray(DEALER_ASR_FRAME_BYTES)
        try {
            while (scope.isActive && !stopping) {
                if (!permissionGranted()) {
                    fail("dealer-microphone-permission-revoked")
                    return
                }
                if (!audio.isRecording) {
                    fail("dealer-microphone-lost")
                    return
                }
                val count = audio.read(buffer, 0, buffer.size)
                if (count < 0) {
                    fail("dealer-microphone-lost")
                    return
                }
                if (count == 0) {
                    if (!audio.isRecording) fail("dealer-microphone-lost")
                    continue
                }
                if (count % 2 != 0) {
                    fail("dealer-audio-frame-invalid")
                    return
                }
                val frame = buffer.copyOf(count)
                val sent = runCatching { send(nextSampleOffset, frame) }.getOrDefault(false)
                if (!sent) {
                    fail("dealer-audio-transport-failed")
                    return
                }
                nextSampleOffset += count / 2L
            }
        } finally {
            if (recorder === audio) {
                recorder = null
                release(audio)
                audioFocus.abandon()
            }
        }
    }

    private fun fail(reason: String): Boolean {
        if (stopping || failureReported) return false
        failureReported = true
        onFailure(reason)
        stop()
        return false
    }

    private fun release(audio: DealerAsrRecorder) {
        runCatching { audio.stop() }
        runCatching { audio.release() }
    }
}
