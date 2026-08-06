package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.domain.PokerBindingMap
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingInstallResult
import com.code2hack.pokerdealer.domain.PokerOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

const val POKER_BINDINGS_SNAPSHOT_TYPE = "bindings.snapshot"
const val POKER_BINDINGS_LEARN_TYPE = "bindings.learn"
const val POKER_BINDINGS_REMOTE_OBSERVED_TYPE = "bindings.remote_observed"
const val POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE = "bindings.remote_forgotten"
const val POKER_BINDINGS_LEARNING_TYPE = "bindings.learning"
const val POKER_BINDINGS_ACK_TYPE = "bindings.ack"

@Serializable
data class PokerBindingLearnRequest(
    val descriptor: String,
    val operation: PokerOperation,
) {
    init {
        require(descriptor.isNotBlank()) { "Remote descriptor must not be blank" }
    }
}

@Serializable
data class PokerBindingRemoteObserved(
    val descriptor: String,
) {
    init {
        require(descriptor.isNotBlank()) { "Remote descriptor must not be blank" }
    }
}

@Serializable
data class PokerBindingLearningState(
    val descriptor: String? = null,
    val operation: PokerOperation? = null,
) {
    init {
        require((descriptor == null) == (operation == null)) {
            "A binding learning state is either inactive or complete"
        }
        require(descriptor == null || descriptor.isNotBlank()) {
            "Remote descriptor must not be blank"
        }
    }

    val active: Boolean get() = descriptor != null
}

@Serializable
data class PokerBindingAck(
    val revision: Long,
    val result: PokerBindingInstallResult,
) {
    init {
        require(revision > 0) { "Binding revision must be positive" }
    }
}

/** Codec for the complete map message; transport framing remains the connection layer's job. */
object PokerBindingProtocol {
    fun snapshotPayload(map: PokerBindingMap) =
        PokerProtocolJson.encodeToJsonElement(PokerBindingMap.serializer(), map).jsonObject

    fun snapshotEnvelope(
        map: PokerBindingMap,
        messageId: String,
        sessionId: String,
        sentAtMs: Long,
        sequence: Long,
    ): ProtocolEnvelope = ProtocolEnvelope(
        type = POKER_BINDINGS_SNAPSHOT_TYPE,
        messageId = messageId,
        sessionId = sessionId,
        sentAtMs = sentAtMs,
        sequence = sequence,
        payload = snapshotPayload(map),
    )

    fun encodeSnapshot(
        map: PokerBindingMap,
        messageId: String,
        sessionId: String,
        sentAtMs: Long,
        sequence: Long,
    ): ByteArray = PokerProtocolJson.encodeToString(
        ProtocolEnvelope.serializer(),
        snapshotEnvelope(map, messageId, sessionId, sentAtMs, sequence),
    ).encodeToByteArray()

    fun decodeSnapshot(frame: ByteArray): PokerBindingMap = decodeSnapshot(
        PokerProtocolJson.decodeFromString<ProtocolEnvelope>(frame.decodeToString()),
    )

    fun decodeSnapshot(envelope: ProtocolEnvelope): PokerBindingMap {
        require(envelope.protocol == POKER_PROTOCOL_NAME) {
            "Expected $POKER_PROTOCOL_NAME, got ${envelope.protocol}"
        }
        require(envelope.version == POKER_PROTOCOL_VERSION) {
            "Expected protocol version $POKER_PROTOCOL_VERSION, got ${envelope.version}"
        }
        require(envelope.type == POKER_BINDINGS_SNAPSHOT_TYPE) {
            "Expected $POKER_BINDINGS_SNAPSHOT_TYPE, got ${envelope.type}"
        }
        return PokerProtocolJson.decodeFromJsonElement(PokerBindingMap.serializer(), envelope.payload)
    }

    fun learnPayload(descriptor: String, operation: PokerOperation) = PokerProtocolJson
        .encodeToJsonElement(PokerBindingLearnRequest.serializer(), PokerBindingLearnRequest(descriptor, operation))
        .jsonObject

    fun decodeLearn(envelope: ProtocolEnvelope): PokerBindingLearnRequest = decodePayload(
        envelope,
        POKER_BINDINGS_LEARN_TYPE,
        PokerBindingLearnRequest.serializer(),
    )

    fun remoteObservedPayload(descriptor: String) = PokerProtocolJson
        .encodeToJsonElement(PokerBindingRemoteObserved.serializer(), PokerBindingRemoteObserved(descriptor))
        .jsonObject

    fun decodeRemoteObserved(envelope: ProtocolEnvelope): PokerBindingRemoteObserved = decodePayload(
        envelope,
        POKER_BINDINGS_REMOTE_OBSERVED_TYPE,
        PokerBindingRemoteObserved.serializer(),
    )

    fun remoteForgottenPayload(descriptor: String) = PokerProtocolJson
        .encodeToJsonElement(PokerBindingRemoteObserved.serializer(), PokerBindingRemoteObserved(descriptor))
        .jsonObject

    fun decodeRemoteForgotten(envelope: ProtocolEnvelope): PokerBindingRemoteObserved = decodePayload(
        envelope,
        POKER_BINDINGS_REMOTE_FORGOTTEN_TYPE,
        PokerBindingRemoteObserved.serializer(),
    )

    fun learningPayload(state: PokerBindingLearningState) = PokerProtocolJson
        .encodeToJsonElement(PokerBindingLearningState.serializer(), state)
        .jsonObject

    fun decodeLearning(envelope: ProtocolEnvelope): PokerBindingLearningState = decodePayload(
        envelope,
        POKER_BINDINGS_LEARNING_TYPE,
        PokerBindingLearningState.serializer(),
    )

    fun ackPayload(revision: Long, result: PokerBindingInstallResult) = PokerProtocolJson
        .encodeToJsonElement(PokerBindingAck.serializer(), PokerBindingAck(revision, result))
        .jsonObject

    fun decodeAck(envelope: ProtocolEnvelope): PokerBindingAck = decodePayload(
        envelope,
        POKER_BINDINGS_ACK_TYPE,
        PokerBindingAck.serializer(),
    )

    fun installSnapshot(
        receiver: PokerBindingController,
        frame: ByteArray,
    ): PokerBindingInstallResult = runCatching {
        receiver.install(decodeSnapshot(frame))
    }.getOrElse { PokerBindingInstallResult.REJECTED }

    fun installSnapshot(
        receiver: PokerBindingController,
        envelope: ProtocolEnvelope,
        authoritative: Boolean = false,
    ): PokerBindingInstallResult = runCatching {
        decodeSnapshot(envelope).let { candidate ->
            if (authoritative) receiver.installAuthoritative(candidate) else receiver.install(candidate)
        }
    }.getOrElse { PokerBindingInstallResult.REJECTED }

    private fun <T> decodePayload(
        envelope: ProtocolEnvelope,
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        require(envelope.protocol == POKER_PROTOCOL_NAME) {
            "Expected $POKER_PROTOCOL_NAME, got ${envelope.protocol}"
        }
        require(envelope.version == POKER_PROTOCOL_VERSION) {
            "Expected protocol version $POKER_PROTOCOL_VERSION, got ${envelope.version}"
        }
        require(envelope.type == type) { "Expected $type, got ${envelope.type}" }
        return PokerProtocolJson.decodeFromJsonElement(serializer, envelope.payload)
    }
}

suspend fun PokerTransport.sendBindingSnapshot(
    map: PokerBindingMap,
    messageId: String,
    sessionId: String,
    sentAtMs: Long,
    sequence: Long,
) {
    send(PokerBindingProtocol.encodeSnapshot(map, messageId, sessionId, sentAtMs, sequence))
}

suspend fun <Snapshot> PokerConnectionOwner<Snapshot>.sendBindingSnapshot(map: PokerBindingMap): Boolean =
    send(
        type = POKER_BINDINGS_SNAPSHOT_TYPE,
        payload = PokerBindingProtocol.snapshotPayload(map),
        requireWritable = false,
    )
