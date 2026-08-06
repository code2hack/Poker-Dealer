package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.POKER_ASR_AUDIO_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_AVAILABILITY_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_COMMIT_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_COMMIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_DISCARD_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_DISCARD_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_EXIT_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_EXIT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_START_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_ASR_START_TYPE
import com.code2hack.pokerdealer.protocol.PokerAsrAudioFrame
import com.code2hack.pokerdealer.protocol.PokerAsrAvailability
import com.code2hack.pokerdealer.protocol.PokerAsrCommitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrCommitResult
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardRequest
import com.code2hack.pokerdealer.protocol.PokerAsrDiscardResult
import com.code2hack.pokerdealer.protocol.PokerAsrExitRequest
import com.code2hack.pokerdealer.protocol.PokerAsrExitResult
import com.code2hack.pokerdealer.protocol.PokerAsrProjection
import com.code2hack.pokerdealer.protocol.PokerAsrStartRequest
import com.code2hack.pokerdealer.protocol.PokerAsrStartResult
import com.code2hack.pokerdealer.protocol.PokerProtocolJson
import com.code2hack.pokerdealer.protocol.ProtocolEnvelope
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object PokerAsrBridge {
    private val availabilityState = MutableStateFlow(PokerAsrAvailability(false, reason = "not-connected"))
    private val projectionState = MutableStateFlow<PokerAsrProjection?>(null)
    private val startResultState = MutableStateFlow<Map<String, PokerAsrStartResult>>(emptyMap())
    private val commitResultState = MutableStateFlow<Map<String, PokerAsrCommitResult>>(emptyMap())
    private val discardResultState = MutableStateFlow<Map<String, PokerAsrDiscardResult>>(emptyMap())
    private val exitResultState = MutableStateFlow<Map<String, PokerAsrExitResult>>(emptyMap())
    private val connectionLossState = MutableStateFlow(0L)
    private var sender: (suspend (String, JsonObject, Boolean) -> Boolean)? = null

    val availability: StateFlow<PokerAsrAvailability> = availabilityState
    val projection: StateFlow<PokerAsrProjection?> = projectionState
    val startResults: StateFlow<Map<String, PokerAsrStartResult>> = startResultState
    val commitResults: StateFlow<Map<String, PokerAsrCommitResult>> = commitResultState
    val discardResults: StateFlow<Map<String, PokerAsrDiscardResult>> = discardResultState
    val exitResults: StateFlow<Map<String, PokerAsrExitResult>> = exitResultState
    val connectionLosses: StateFlow<Long> = connectionLossState

    fun attach(sender: suspend (String, JsonObject, Boolean) -> Boolean) {
        this.sender = sender
        clear()
        availabilityState.value = PokerAsrAvailability(false, reason = "not-ready")
    }

    fun detach() {
        sender = null
        clear()
        availabilityState.value = PokerAsrAvailability(false, reason = "not-connected")
    }

    fun connectionLost() {
        clear()
        availabilityState.value = PokerAsrAvailability(false, reason = "not-connected")
        connectionLossState.value++
    }

    fun receive(envelope: ProtocolEnvelope): Boolean = try {
        when (envelope.type) {
            POKER_ASR_AVAILABILITY_TYPE -> availabilityState.value = decode(envelope, PokerAsrAvailability.serializer())
            POKER_ASR_PROJECTION_TYPE -> projectionState.value = decode(envelope, PokerAsrProjection.serializer())
            POKER_ASR_START_RESULT_TYPE -> {
                val value = decode(envelope, PokerAsrStartResult.serializer())
                startResultState.value += value.sessionId to value
            }
            POKER_ASR_COMMIT_RESULT_TYPE -> {
                val value = decode(envelope, PokerAsrCommitResult.serializer())
                commitResultState.value += value.operationId to value
            }
            POKER_ASR_DISCARD_RESULT_TYPE -> {
                val value = decode(envelope, PokerAsrDiscardResult.serializer())
                discardResultState.value += value.operationId to value
            }
            POKER_ASR_EXIT_RESULT_TYPE -> {
                val value = decode(envelope, PokerAsrExitResult.serializer())
                exitResultState.value += value.operationId to value
                if (projectionState.value?.sessionId == value.sessionId) {
                    projectionState.value = projectionState.value?.copy(sliceText = "")
                }
            }
            else -> return false
        }
        true
    } catch (_: Throwable) {
        false
    }

    suspend fun sendStart(request: PokerAsrStartRequest): Boolean = send(
        POKER_ASR_START_TYPE,
        PokerAsrStartRequest.serializer(),
        request,
    )

    suspend fun sendAudio(sessionId: String, offset: Long, pcm16: ByteArray): Boolean = send(
        POKER_ASR_AUDIO_TYPE,
        PokerAsrAudioFrame.serializer(),
        PokerAsrAudioFrame(
            sessionId = sessionId,
            firstSampleOffset = offset,
            pcm16Base64 = Base64.getEncoder().encodeToString(pcm16),
        ),
    )

    suspend fun sendCommit(request: PokerAsrCommitRequest): Boolean = send(
        POKER_ASR_COMMIT_TYPE,
        PokerAsrCommitRequest.serializer(),
        request,
    )

    suspend fun sendDiscard(request: PokerAsrDiscardRequest): Boolean = send(
        POKER_ASR_DISCARD_TYPE,
        PokerAsrDiscardRequest.serializer(),
        request,
    )

    suspend fun sendExit(request: PokerAsrExitRequest): Boolean = send(
        POKER_ASR_EXIT_TYPE,
        PokerAsrExitRequest.serializer(),
        request,
    )

    private suspend fun <T> send(type: String, serializer: KSerializer<T>, value: T): Boolean {
        val send = sender ?: return false
        return send(
            type,
            PokerProtocolJson.encodeToJsonElement(serializer, value).jsonObject,
            true,
        )
    }

    private fun <T> decode(envelope: ProtocolEnvelope, serializer: KSerializer<T>): T =
        PokerProtocolJson.decodeFromJsonElement(serializer, envelope.payload)

    private fun clear() {
        projectionState.value = null
        startResultState.value = emptyMap()
        commitResultState.value = emptyMap()
        discardResultState.value = emptyMap()
        exitResultState.value = emptyMap()
    }
}
