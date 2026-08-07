package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.protocol.PokerAsrAudioFrame
import com.code2hack.pokerdealer.protocol.PokerAsrPackSelection
import com.code2hack.pokerdealer.protocol.PokerAsrProjection
import com.code2hack.pokerdealer.protocol.PokerAsrSource
import com.code2hack.pokerdealer.protocol.PokerAsrTarget

/** Owns one Dealer-recognized ASR session and its contiguous, uncommitted slices. */
internal class DealerAsrSliceSession(
    val sessionId: String,
    var target: PokerAsrTarget,
    val pack: PokerAsrPackSelection,
    private val recognizer: DealerAsrProcessSession,
    var source: PokerAsrSource = PokerAsrSource.GLASSES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    var sliceRevision: Long = 0
        private set
    var nextSampleOffset: Long = 0
        private set

    var sliceStartSampleOffset: Long = 0
        private set

    var lastCommittedSlice: DealerAsrCommittedSlice? = null
        private set

    val hasUncommittedSlice: Boolean
        get() = nextSampleOffset > sliceStartSampleOffset || recognizer.provisionalText().isNotBlank()

    private var lastProjectionAtMs: Long? = null
    private var closed = false

    fun accept(frame: PokerAsrAudioFrame): String? {
        check(!closed) { "ASR session is closed" }
        if (frame.sessionId != sessionId) return "audio-session-invalid"
        val pcm = try {
            frame.decodePcm16()
        } catch (_: Throwable) {
            return "audio-frame-invalid"
        }
        val samples = pcm.size / 2
        if (frame.firstSampleOffset != nextSampleOffset ||
            samples > Long.MAX_VALUE - nextSampleOffset
        ) {
            return "audio-sequence-invalid"
        }
        return try {
            recognizer.acceptPcm16(pcm)
            nextSampleOffset += samples
            null
        } catch (failure: DealerAsrOfflineFailure) {
            failure.reason
        } catch (_: Throwable) {
            "runtime-decode-failed"
        }
    }

    fun provisionalText(): String = recognizer.provisionalText()

    fun projection(immediate: Boolean): PokerAsrProjection? {
        check(!closed) { "ASR session is closed" }
        val now = nowMs()
        val previous = lastProjectionAtMs
        if (!immediate && previous != null && now - previous < 100L) return null
        lastProjectionAtMs = now
        return PokerAsrProjection(
            target = target,
            sessionId = sessionId,
            sliceRevision = sliceRevision,
            sliceText = recognizer.provisionalText(),
            sampleOffset = nextSampleOffset,
        )
    }

    fun commitSlice(fenceSampleOffset: Long): String {
        check(!closed) { "ASR session is closed" }
        check(fenceSampleOffset == nextSampleOffset) { "ASR commit fence is stale" }
        val text = recognizer.commitSlice()
        sliceRevision++
        sliceStartSampleOffset = nextSampleOffset
        return text
    }

    fun discardSlice(fenceSampleOffset: Long) {
        check(!closed) { "ASR session is closed" }
        check(fenceSampleOffset == nextSampleOffset) { "ASR discard fence is stale" }
        recognizer.discardSlice()
        sliceRevision++
        sliceStartSampleOffset = nextSampleOffset
    }

    fun rememberCommittedSlice(target: PokerAsrTarget, start: Int, endExclusive: Int, text: String) {
        if (text.isNotEmpty()) {
            lastCommittedSlice = DealerAsrCommittedSlice(target, start, endExclusive, text)
        }
    }

    fun clearLastCommittedSlice() {
        lastCommittedSlice = null
    }

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            recognizer.discardSlice()
        } finally {
            recognizer.close()
        }
    }
}

internal data class DealerAsrCommittedSlice(
    val target: PokerAsrTarget,
    val start: Int,
    val endExclusive: Int,
    val text: String,
)
