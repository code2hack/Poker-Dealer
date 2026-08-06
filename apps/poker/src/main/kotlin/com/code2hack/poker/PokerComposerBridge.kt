package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.ComposerMutationRequest
import com.code2hack.pokerdealer.protocol.ComposerMutationResult
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_DRAFT_PROJECTION_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_RESULT_TYPE
import com.code2hack.pokerdealer.protocol.POKER_COMPOSER_MUTATION_TYPE
import com.code2hack.pokerdealer.protocol.PokerProtocolJson
import com.code2hack.pokerdealer.protocol.ProtocolEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Process-local handoff between the listener service and Poker's visible composer surface. */
internal object PokerComposerBridge {
    private val projectionState = MutableStateFlow<Map<com.code2hack.pokerdealer.domain.CodexThreadLocator, ComposerDraftProjection>>(emptyMap())
    private val resultState = MutableStateFlow<Map<String, ComposerMutationResult>>(emptyMap())
    private var sender: (suspend (String, JsonObject, Boolean) -> Boolean)? = null

    val projections: StateFlow<Map<com.code2hack.pokerdealer.domain.CodexThreadLocator, ComposerDraftProjection>> =
        projectionState
    val results: StateFlow<Map<String, ComposerMutationResult>> = resultState

    fun attach(sender: suspend (String, JsonObject, Boolean) -> Boolean) {
        this.sender = sender
        projectionState.value = emptyMap()
        resultState.value = emptyMap()
    }

    fun detach() {
        sender = null
        projectionState.value = emptyMap()
        resultState.value = emptyMap()
    }

    fun receive(envelope: ProtocolEnvelope): Boolean = try {
        when (envelope.type) {
            POKER_COMPOSER_DRAFT_PROJECTION_TYPE -> {
                val projection = PokerProtocolJson.decodeFromJsonElement(
                    ComposerDraftProjection.serializer(),
                    envelope.payload,
                )
                projectionState.value = projectionState.value + (projection.locator to projection)
            }
            POKER_COMPOSER_MUTATION_RESULT_TYPE -> {
                val result = PokerProtocolJson.decodeFromJsonElement(
                    ComposerMutationResult.serializer(),
                    envelope.payload,
                )
                resultState.value = resultState.value + (result.target.operationId to result)
            }
            else -> return false
        }
        true
    } catch (_: Throwable) {
        false
    }

    suspend fun sendMutation(request: ComposerMutationRequest): Boolean {
        val send = sender ?: return false
        val payload = PokerProtocolJson.encodeToJsonElement(
            ComposerMutationRequest.serializer(),
            request,
        ).jsonObject
        return send(POKER_COMPOSER_MUTATION_TYPE, payload, true)
    }
}
