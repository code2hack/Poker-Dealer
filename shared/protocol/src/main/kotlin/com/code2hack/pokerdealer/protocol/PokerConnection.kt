package com.code2hack.pokerdealer.protocol

import kotlin.math.roundToLong
import kotlin.random.Random

const val DEFAULT_POKER_HEARTBEAT_INTERVAL_MS = 30_000L
const val DEFAULT_POKER_HEARTBEAT_MAX_UNANSWERED_PONGS = 3
const val DEFAULT_POKER_RECONNECT_INITIAL_DELAY_MS = 1_000L
const val DEFAULT_POKER_RECONNECT_MAX_DELAY_MS = 30_000L

enum class PokerProtocolAccess {
    READ_WRITE,
    READ_ONLY,
}

data class PokerProtocolNegotiation(
    val access: PokerProtocolAccess,
    val majorCompatible: Boolean,
    val capabilities: Set<String>,
    val missingRequiredCapabilities: Set<String>,
) {
    fun supports(capability: String): Boolean = capability in capabilities
}

fun negotiatePokerProtocol(
    local: PokerProtocolOffer,
    peer: PokerProtocolOffer,
): PokerProtocolNegotiation {
    val missingRequired = buildSet {
        addAll(local.requiredCapabilities - peer.capabilities)
        addAll(peer.requiredCapabilities - local.capabilities)
    }
    val majorCompatible = local.major == peer.major
    return PokerProtocolNegotiation(
        access = if (majorCompatible && missingRequired.isEmpty()) {
            PokerProtocolAccess.READ_WRITE
        } else {
            PokerProtocolAccess.READ_ONLY
        },
        majorCompatible = majorCompatible,
        capabilities = local.capabilities.intersect(peer.capabilities),
        missingRequiredCapabilities = missingRequired,
    )
}

fun interface PokerEpochConnection {
    fun close()
}

data class PokerConnectionEpoch(val value: Long) {
    init {
        require(value > 0) { "Connection epoch must be positive" }
    }
}

enum class PokerSequenceDecision {
    ACCEPTED,
    STALE_EPOCH,
    INVALID_STREAM,
    INVALID_SEQUENCE,
    DUPLICATE,
    STALE_SEQUENCE,
    OUT_OF_ORDER,
}

sealed interface PokerMutationResult<out T> {
    data class Applied<T>(val value: T) : PokerMutationResult<T>
    data class Rejected(val reason: PokerMutationRejection) : PokerMutationResult<Nothing>
}

enum class PokerMutationRejection {
    NOT_CONNECTED,
    READ_ONLY,
    CAPABILITY_UNAVAILABLE,
    STALE_EPOCH,
    INVALID_STREAM,
    INVALID_SEQUENCE,
    DUPLICATE,
    STALE_SEQUENCE,
    OUT_OF_ORDER,
}

/** Fences every inbound stream to one live authenticated connection epoch. */
class PokerConnectionEpochFence {
    private data class ActiveConnection(
        val epoch: PokerConnectionEpoch,
        val connection: PokerEpochConnection,
    )

    private var nextEpoch = 0L
    private var active: ActiveConnection? = null
    private val inboundSequences = mutableMapOf<String, Long>()
    private val outboundSequences = mutableMapOf<String, Long>()

    @Synchronized
    fun replace(connection: PokerEpochConnection): PokerConnectionEpoch {
        checkNotNull(connection)
        val previous = active
        active = null
        inboundSequences.clear()
        outboundSequences.clear()
        runCatching { previous?.connection?.close() }

        val epoch = PokerConnectionEpoch(++nextEpoch)
        active = ActiveConnection(epoch, connection)
        return epoch
    }

    @Synchronized
    fun isCurrent(epoch: PokerConnectionEpoch): Boolean = active?.epoch == epoch

    @Synchronized
    fun activeEpoch(): PokerConnectionEpoch? = active?.epoch

    @Synchronized
    fun acceptInbound(
        epoch: PokerConnectionEpoch,
        stream: String,
        sequence: Long,
    ): PokerSequenceDecision {
        return acceptInboundLocked(epoch, stream, sequence)
    }

    @Synchronized
    fun <T> applyInbound(
        epoch: PokerConnectionEpoch,
        stream: String,
        sequence: Long,
        mutation: () -> T,
    ): PokerMutationResult<T> {
        val decision = acceptInboundLocked(epoch, stream, sequence)
        if (decision != PokerSequenceDecision.ACCEPTED) {
            return PokerMutationResult.Rejected(decision.rejection())
        }
        return PokerMutationResult.Applied(mutation())
    }

    @Synchronized
    fun nextOutboundSequence(
        epoch: PokerConnectionEpoch,
        stream: String,
    ): Long? {
        require(stream.isNotBlank()) { "Stream must not be blank" }
        if (active?.epoch != epoch) return null
        val next = (outboundSequences[stream] ?: 0L) + 1
        outboundSequences[stream] = next
        return next
    }

    @Synchronized
    fun close(epoch: PokerConnectionEpoch): Boolean {
        val current = active ?: return false
        if (current.epoch != epoch) return false
        active = null
        inboundSequences.clear()
        outboundSequences.clear()
        runCatching { current.connection.close() }
        return true
    }

    @Synchronized
    fun close() {
        active?.connection?.let { runCatching { it.close() } }
        active = null
        inboundSequences.clear()
        outboundSequences.clear()
    }

    private fun acceptInboundLocked(
        epoch: PokerConnectionEpoch,
        stream: String,
        sequence: Long,
    ): PokerSequenceDecision {
        if (stream.isBlank()) return PokerSequenceDecision.INVALID_STREAM
        if (sequence <= 0) return PokerSequenceDecision.INVALID_SEQUENCE
        if (active?.epoch != epoch) return PokerSequenceDecision.STALE_EPOCH

        val last = inboundSequences[stream] ?: 0L
        return when {
            sequence == last -> PokerSequenceDecision.DUPLICATE
            sequence < last -> PokerSequenceDecision.STALE_SEQUENCE
            sequence != last + 1 -> PokerSequenceDecision.OUT_OF_ORDER
            else -> {
                inboundSequences[stream] = sequence
                PokerSequenceDecision.ACCEPTED
            }
        }
    }

    private fun PokerSequenceDecision.rejection(): PokerMutationRejection = when (this) {
        PokerSequenceDecision.ACCEPTED -> error("Accepted sequence is not a rejection")
        PokerSequenceDecision.STALE_EPOCH -> PokerMutationRejection.STALE_EPOCH
        PokerSequenceDecision.INVALID_STREAM -> PokerMutationRejection.INVALID_STREAM
        PokerSequenceDecision.INVALID_SEQUENCE -> PokerMutationRejection.INVALID_SEQUENCE
        PokerSequenceDecision.DUPLICATE -> PokerMutationRejection.DUPLICATE
        PokerSequenceDecision.STALE_SEQUENCE -> PokerMutationRejection.STALE_SEQUENCE
        PokerSequenceDecision.OUT_OF_ORDER -> PokerMutationRejection.OUT_OF_ORDER
    }
}

enum class PokerConnectionState {
    DISCONNECTED,
    NEGOTIATING,
    READ_ONLY,
    CONNECTED,
}

/** Keeps the last complete snapshot in memory while a replacement epoch negotiates. */
class PokerConnectionSession<Snapshot>(
    private val localOffer: PokerProtocolOffer = PokerProtocolOffer(),
    private val epochFence: PokerConnectionEpochFence = PokerConnectionEpochFence(),
) {
    var state: PokerConnectionState = PokerConnectionState.DISCONNECTED
        private set

    var negotiation: PokerProtocolNegotiation? = null
        private set

    private var completeSnapshot: Snapshot? = null

    fun replaceAuthenticatedConnection(connection: PokerEpochConnection): PokerConnectionEpoch {
        val epoch = epochFence.replace(connection)
        negotiation = null
        state = PokerConnectionState.NEGOTIATING
        return epoch
    }

    fun negotiate(
        epoch: PokerConnectionEpoch,
        peerOffer: PokerProtocolOffer,
    ): PokerProtocolNegotiation? {
        if (!epochFence.isCurrent(epoch)) return null
        return negotiatePokerProtocol(localOffer, peerOffer).also { result ->
            negotiation = result
            state = if (result.access == PokerProtocolAccess.READ_WRITE) {
                PokerConnectionState.CONNECTED
            } else {
                PokerConnectionState.READ_ONLY
            }
        }
    }

    fun retainCompleteSnapshot(snapshot: Snapshot) {
        completeSnapshot = snapshot
    }

    fun completeSnapshot(): Snapshot? = completeSnapshot

    fun canMutate(capability: String? = null): Boolean {
        val result = negotiation ?: return false
        return state == PokerConnectionState.CONNECTED &&
            (capability == null || result.supports(capability))
    }

    fun <T> applyMutation(
        epoch: PokerConnectionEpoch,
        stream: String,
        sequence: Long,
        capability: String? = null,
        mutation: () -> T,
    ): PokerMutationResult<T> {
        val result = negotiation
        if (result == null || state == PokerConnectionState.DISCONNECTED ||
            state == PokerConnectionState.NEGOTIATING
        ) {
            return PokerMutationResult.Rejected(PokerMutationRejection.NOT_CONNECTED)
        }
        if (state == PokerConnectionState.READ_ONLY) {
            return PokerMutationResult.Rejected(PokerMutationRejection.READ_ONLY)
        }
        if (capability != null && !result.supports(capability)) {
            return PokerMutationResult.Rejected(PokerMutationRejection.CAPABILITY_UNAVAILABLE)
        }
        return epochFence.applyInbound(epoch, stream, sequence, mutation)
    }

    fun nextOutboundSequence(epoch: PokerConnectionEpoch, stream: String): Long? =
        epochFence.nextOutboundSequence(epoch, stream)

    fun close(epoch: PokerConnectionEpoch): Boolean {
        val closed = epochFence.close(epoch)
        if (closed) {
            negotiation = null
            state = PokerConnectionState.DISCONNECTED
        }
        return closed
    }

    fun close() {
        epochFence.close()
        negotiation = null
        state = PokerConnectionState.DISCONNECTED
    }
}

data class PokerHeartbeatPolicy(
    val idlePingIntervalMs: Long = DEFAULT_POKER_HEARTBEAT_INTERVAL_MS,
    val maxUnansweredPongs: Int = DEFAULT_POKER_HEARTBEAT_MAX_UNANSWERED_PONGS,
) {
    init {
        require(idlePingIntervalMs > 0) { "Heartbeat interval must be positive" }
        require(maxUnansweredPongs > 0) { "Heartbeat pong limit must be positive" }
    }
}

enum class PokerHeartbeatAction {
    NONE,
    SEND_PING,
    CLOSE,
}

class PokerHeartbeatMonitor(
    private val policy: PokerHeartbeatPolicy = PokerHeartbeatPolicy(),
    startedAtMs: Long = 0,
) {
    private var lastTrafficAtMs = startedAtMs
    private var unansweredPongs = 0
    private var closed = false

    val pendingPongs: Int get() = unansweredPongs

    fun onTraffic(nowMs: Long) {
        check(nowMs >= lastTrafficAtMs) { "Heartbeat clock must be monotonic" }
        if (!closed) lastTrafficAtMs = nowMs
    }

    fun onPong(nowMs: Long) {
        check(nowMs >= lastTrafficAtMs) { "Heartbeat clock must be monotonic" }
        if (closed) return
        lastTrafficAtMs = nowMs
        unansweredPongs = 0
    }

    fun poll(nowMs: Long): PokerHeartbeatAction {
        check(nowMs >= lastTrafficAtMs) { "Heartbeat clock must be monotonic" }
        if (closed) return PokerHeartbeatAction.CLOSE
        if (nowMs - lastTrafficAtMs < policy.idlePingIntervalMs) {
            return PokerHeartbeatAction.NONE
        }
        if (unansweredPongs >= policy.maxUnansweredPongs) {
            closed = true
            return PokerHeartbeatAction.CLOSE
        }
        unansweredPongs++
        lastTrafficAtMs = nowMs
        return PokerHeartbeatAction.SEND_PING
    }
}

enum class PokerReconnectTrigger {
    FAILURE,
    NETWORK_CHANGE,
    MANUAL_RETRY,
}

data class PokerReconnectPolicy(
    val initialDelayMs: Long = DEFAULT_POKER_RECONNECT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_POKER_RECONNECT_MAX_DELAY_MS,
    val jitterFraction: Double = 0.2,
) {
    init {
        require(initialDelayMs > 0) { "Reconnect initial delay must be positive" }
        require(maxDelayMs >= initialDelayMs) { "Reconnect maximum must cover the initial delay" }
        require(jitterFraction in 0.0..1.0) { "Reconnect jitter must be between zero and one" }
    }

    fun delayMs(failedAttempt: Int, jitterUnit: Double = Random.nextDouble()): Long {
        require(failedAttempt > 0) { "Failed attempt must be positive" }
        require(jitterUnit in 0.0..1.0) { "Jitter unit must be between zero and one" }
        var base = initialDelayMs
        repeat((failedAttempt - 1).coerceAtMost(62)) {
            base = if (base >= maxDelayMs / 2) maxDelayMs else (base * 2).coerceAtMost(maxDelayMs)
        }
        val jitter = (jitterUnit * 2 - 1) * jitterFraction
        return (base * (1 + jitter)).roundToLong().coerceIn(initialDelayMs, maxDelayMs)
    }
}

class PokerReconnectController(
    private val policy: PokerReconnectPolicy = PokerReconnectPolicy(),
) {
    private var enabled = true

    var failedAttempts: Int = 0
        private set

    fun request(trigger: PokerReconnectTrigger, jitterUnit: Double = Random.nextDouble()): Long? {
        if (!enabled) return null
        return when (trigger) {
            PokerReconnectTrigger.NETWORK_CHANGE,
            PokerReconnectTrigger.MANUAL_RETRY,
            -> 0

            PokerReconnectTrigger.FAILURE -> {
                failedAttempts++
                policy.delayMs(failedAttempts, jitterUnit)
            }
        }
    }

    fun markStable() {
        failedAttempts = 0
    }

    fun cancel() {
        enabled = false
    }

    fun enable() {
        enabled = true
    }
}
