package com.code2hack.pokerdealer.protocol

import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.coroutineContext
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

    fun acceptInboundFrame(
        epoch: PokerConnectionEpoch,
        stream: String,
        sequence: Long,
    ): PokerSequenceDecision = epochFence.acceptInbound(epoch, stream, sequence)

    fun isCurrent(epoch: PokerConnectionEpoch): Boolean = epochFence.isCurrent(epoch)

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

/** A length-delimited JSON envelope is carried by the platform socket. */
object PokerFrameCodec {
    fun encode(envelope: ProtocolEnvelope): ByteArray =
        PokerProtocolJson.encodeToString(envelope).encodeToByteArray().also {
            require(it.size <= DEFAULT_MAX_FRAME_BYTES) { "Poker frame is too large" }
        }

    fun decode(frame: ByteArray): ProtocolEnvelope {
        require(frame.isNotEmpty()) { "Poker frame is empty" }
        require(frame.size <= DEFAULT_MAX_FRAME_BYTES) { "Poker frame is too large" }
        return PokerProtocolJson.decodeFromString(frame.decodeToString())
    }
}

interface PokerFrameSocket : PokerEpochConnection {
    suspend fun sendFrame(frame: ByteArray)

    suspend fun receiveFrame(): ByteArray?
}

/** Length-prefixes frames without coupling the protocol owner to a socket implementation. */
class LengthPrefixedPokerFrameSocket(
    private val stream: DuplexByteStream,
    private val closeAction: () -> Unit,
) : PokerFrameSocket {
    private val sendLock = Mutex()

    override suspend fun sendFrame(frame: ByteArray) {
        require(frame.isNotEmpty() && frame.size <= DEFAULT_MAX_FRAME_BYTES) {
            "Poker frame size is invalid"
        }
        val header = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(frame.size).array()
        sendLock.withLock {
            stream.write(header)
            stream.write(frame)
        }
    }

    override suspend fun receiveFrame(): ByteArray? {
        val header = ByteArray(Int.SIZE_BYTES)
        if (!readFully(header, allowEof = true)) return null
        val size = ByteBuffer.wrap(header).int
        require(size in 1..DEFAULT_MAX_FRAME_BYTES) { "Poker frame size is invalid" }
        return ByteArray(size).also { readFully(it) }
    }

    override fun close() = closeAction()

    private suspend fun readFully(buffer: ByteArray, allowEof: Boolean = false): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                if (allowEof && offset == 0) return false
                throw IllegalStateException("Poker socket closed mid-frame")
            }
            require(read > 0) { "Poker socket made no read progress" }
            offset += read
        }
        return true
    }
}

interface PokerListenerSocket : PokerEpochConnection {
    suspend fun accept(): PokerFrameSocket
}

fun interface PokerListenerFactory {
    fun open(): PokerListenerSocket
}

fun interface PokerConnectionConnector {
    suspend fun connect(): PokerFrameSocket
}

fun interface PokerScheduledTask {
    fun cancel()
}

interface PokerScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask
}

class CoroutinePokerScheduler(
    private val scope: CoroutineScope,
) : PokerScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): PokerScheduledTask {
        require(delayMs >= 0) { "Poker schedule delay must not be negative" }
        val job = scope.launch {
            delay(delayMs)
            task()
        }
        return PokerScheduledTask { job.cancel() }
    }
}

fun interface PokerClock {
    fun nowMs(): Long
}

/**
 * Owns the only production connection loop. Socket implementations authenticate before returning
 * a frame socket; this class owns framing, protocol negotiation, epochs, heartbeat, and retry.
 */
class PokerConnectionOwner<Snapshot>(
    private val factory: PokerListenerFactory?,
    private val scope: CoroutineScope,
    private val localOffer: PokerProtocolOffer = PokerProtocolOffer(),
    private val session: PokerConnectionSession<Snapshot> = PokerConnectionSession(localOffer),
    private val scheduler: PokerScheduler,
    private val clock: PokerClock,
    private val heartbeatPolicy: PokerHeartbeatPolicy = PokerHeartbeatPolicy(),
    private val reconnect: PokerReconnectController = PokerReconnectController(),
    private val sessionId: String = UUID.randomUUID().toString(),
    private val messageId: () -> String = { UUID.randomUUID().toString() },
    private val connector: PokerConnectionConnector? = null,
) {
    init {
        require((factory == null) xor (connector == null)) {
            "Exactly one Poker connection source is required"
        }
    }

    private val lock = Any()
    private var running = false
    private var generation = 0L
    private var listener: PokerListenerSocket? = null
    private var listenerJob: Job? = null
    private var retryTask: PokerScheduledTask? = null
    private var active: ActiveConnection? = null
    private var nextAcceptOrdinal = 0L
    private var newestAcceptOrdinal = 0L

    val isRunning: Boolean
        get() = synchronized(lock) { running }

    val isListening: Boolean
        get() = synchronized(lock) { listener != null }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            reconnect.enable()
            scheduleOpenLocked(0L, generation)
        }
    }

    /** Returns the scheduled delay so callers cannot accidentally discard reconnect work. */
    fun retry(trigger: PokerReconnectTrigger, jitterUnit: Double = Random.nextDouble()): Long? {
        synchronized(lock) {
            if (!running) return null
            generation++
            retryTask?.cancel()
            retryTask = null
            listenerJob?.cancel()
            listenerJob = null
            listener?.close()
            listener = null
            active?.cancel()
            active = null
            session.close()
            val delay = reconnect.request(trigger, jitterUnit)
            if (delay != null) scheduleOpenLocked(delay, generation)
            return delay
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            retryTask?.cancel()
            retryTask = null
            listenerJob?.cancel()
            listenerJob = null
            listener?.close()
            listener = null
            active?.cancel()
            active = null
            session.close()
            reconnect.cancel()
        }
    }

    private fun scheduleOpenLocked(delayMs: Long, expectedGeneration: Long) {
        retryTask?.cancel()
        retryTask = scheduler.schedule(delayMs) {
            synchronized(lock) {
                if (!running || generation != expectedGeneration) return@schedule
                retryTask = null
                openListenerLocked(expectedGeneration)
            }
        }
    }

    private fun openListenerLocked(expectedGeneration: Long) {
        if (!running || generation != expectedGeneration || listenerJob?.isActive == true) return
        val job = scope.launch {
            var opened: PokerListenerSocket? = null
            try {
                if (connector != null) {
                    val socket = connector.connect()
                    val ordinal = assignAcceptOrdinal(socket, expectedGeneration)
                    if (ordinal != null) {
                        handleConnection(socket, expectedGeneration, ordinal)
                    }
                } else {
                    val bound = checkNotNull(factory).open()
                    opened = bound
                    synchronized(lock) {
                        if (!running || generation != expectedGeneration) {
                            bound.close()
                            return@launch
                        }
                        listener = bound
                        coroutineContext[Job]?.invokeOnCompletion { bound.close() }
                    }
                    while (true) {
                        val socket = bound.accept()
                        val ordinal = assignAcceptOrdinal(socket, expectedGeneration)
                        if (ordinal != null) {
                            scope.launch { handleConnection(socket, expectedGeneration, ordinal) }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (running && generation == expectedGeneration) {
                        active?.let {
                            session.close(it.epoch)
                            it.cancel()
                        }
                        active = null
                    }
                }
                scheduleFailure(expectedGeneration)
            } finally {
                opened?.close()
                synchronized(lock) {
                    if (listener === opened) listener = null
                    if (listenerJob === coroutineContext[Job]) listenerJob = null
                }
            }
        }
        listenerJob = job
    }

    private fun assignAcceptOrdinal(
        socket: PokerFrameSocket,
        expectedGeneration: Long,
    ): Long? = synchronized(lock) {
        if (!running || generation != expectedGeneration) {
            socket.close()
            return@synchronized null
        }
        val ordinal = ++nextAcceptOrdinal
        newestAcceptOrdinal = ordinal
        ordinal
    }

    private fun scheduleFailure(expectedGeneration: Long) {
        synchronized(lock) {
            if (!running || generation != expectedGeneration || retryTask != null) return
            val delay = reconnect.request(PokerReconnectTrigger.FAILURE) ?: return
            scheduleOpenLocked(delay, expectedGeneration)
        }
    }

    private suspend fun handleConnection(
        socket: PokerFrameSocket,
        expectedGeneration: Long,
        acceptOrdinal: Long,
    ) {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]!!
        val runtime = synchronized(lock) {
            if (!running || generation != expectedGeneration || acceptOrdinal != newestAcceptOrdinal) {
                socket.close()
                null
            } else {
                active?.let {
                    session.close(it.epoch)
                    it.cancel()
                }
                val epoch = session.replaceAuthenticatedConnection(socket)
                ActiveConnection(
                    epoch = epoch,
                    socket = socket,
                    job = job,
                    heartbeat = PokerHeartbeatMonitor(heartbeatPolicy, clock.nowMs()),
                ).also { active = it }
            }
        } ?: return
        val epoch = runtime.epoch
        try {
            send(epoch, POKER_PROTOCOL_OFFER_TYPE, buildOfferPayload())
            val offer = receiveEnvelope(runtime) ?: return
            require(offer.type == POKER_PROTOCOL_OFFER_TYPE) { "Poker offer required" }
            val peerOffer = PokerProtocolJson.decodeFromJsonElement(
                PokerProtocolOffer.serializer(),
                offer.payload,
            )
            val negotiation = session.negotiate(epoch, peerOffer)
                ?: throw IllegalStateException("Stale Poker negotiation")
            runtime.peerEnvelopeVersion = if (
                negotiation.access == PokerProtocolAccess.READ_ONLY &&
                !negotiation.majorCompatible
            ) {
                offer.version
            } else {
                null
            }
            send(
                epoch,
                POKER_PROTOCOL_NEGOTIATED_TYPE,
                PokerProtocolJson.encodeToJsonElement(
                    PokerProtocolNegotiationMessage.serializer(),
                    PokerProtocolNegotiationMessage(
                        major = localOffer.major,
                        capabilities = negotiation.capabilities,
                        readOnly = negotiation.access == PokerProtocolAccess.READ_ONLY,
                    ),
                ).jsonObject,
            )
            reconnect.markStable()
            scheduleHeartbeat(runtime)
            while (true) {
                val envelope = receiveEnvelope(runtime) ?: return
                when (envelope.type) {
                    POKER_HEARTBEAT_PING_TYPE -> {
                        runtime.heartbeat.onTraffic(clock.nowMs())
                        send(
                            epoch,
                            POKER_HEARTBEAT_PONG_TYPE,
                            buildJsonObject { },
                            replyTo = envelope.messageId,
                        )
                    }

                    POKER_HEARTBEAT_PONG_TYPE -> {
                        runtime.heartbeat.onPong(clock.nowMs())
                    }

                    else -> runtime.heartbeat.onTraffic(clock.nowMs())
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException("Poker connection cancelled")
        } catch (_: Throwable) {
            runtime.cancel()
        } finally {
            runtime.heartbeatTask?.cancel()
            socket.close()
            val wasCurrent = session.close(epoch)
            synchronized(lock) {
                if (active === runtime) active = null
                if (wasCurrent && running && generation == expectedGeneration) {
                    scheduleFailure(expectedGeneration)
                }
            }
        }
    }

    private suspend fun receiveEnvelope(runtime: ActiveConnection): ProtocolEnvelope? {
        val envelope = runtime.socket.receiveFrame()?.let(PokerFrameCodec::decode) ?: return null
        require(envelope.protocol == POKER_PROTOCOL_NAME) { "Unexpected Poker protocol" }
        if (envelope.type != POKER_PROTOCOL_OFFER_TYPE) {
            require(
                envelope.version == POKER_PROTOCOL_VERSION ||
                    envelope.version == runtime.peerEnvelopeVersion,
            ) {
                "Unexpected Poker protocol version"
            }
        }
        require(envelope.epoch == 0L || envelope.epoch == runtime.epoch.value) {
            "Unexpected Poker epoch"
        }
        require(envelope.stream == POKER_CONTROL_STREAM) { "Unexpected Poker stream" }
        require(
            session.acceptInboundFrame(runtime.epoch, envelope.stream, envelope.sequence) ==
                PokerSequenceDecision.ACCEPTED,
        ) { "Invalid Poker sequence" }
        return envelope
    }

    private suspend fun send(
        epoch: PokerConnectionEpoch,
        type: String,
        payload: JsonObject,
        replyTo: String? = null,
    ) {
        val sequence = session.nextOutboundSequence(epoch, POKER_CONTROL_STREAM)
            ?: throw IllegalStateException("Poker epoch is no longer current")
        val envelope = ProtocolEnvelope(
            type = type,
            messageId = messageId(),
            sessionId = sessionId,
            sentAtMs = clock.nowMs(),
            epoch = epoch.value,
            stream = POKER_CONTROL_STREAM,
            sequence = sequence,
            replyTo = replyTo,
            payload = payload,
        )
        runtimeSocket(epoch).sendFrame(PokerFrameCodec.encode(envelope))
    }

    private suspend fun runtimeSocket(epoch: PokerConnectionEpoch): PokerFrameSocket =
        synchronized(lock) {
            active?.takeIf { it.epoch == epoch }?.socket
        } ?: throw IllegalStateException("Poker epoch is no longer current")

    private fun buildOfferPayload(): JsonObject = PokerProtocolJson.encodeToJsonElement(
        PokerProtocolOffer.serializer(),
        localOffer,
    ).jsonObject

    private fun scheduleHeartbeat(runtime: ActiveConnection) {
        runtime.heartbeatTask?.cancel()
        runtime.heartbeatTask = scheduler.schedule(heartbeatPolicy.idlePingIntervalMs) {
            scope.launch {
                if (!isCurrent(runtime)) return@launch
                when (runtime.heartbeat.poll(clock.nowMs())) {
                    PokerHeartbeatAction.SEND_PING -> runCatching {
                        send(runtime.epoch, POKER_HEARTBEAT_PING_TYPE, buildJsonObject { })
                    }.onFailure { runtime.cancel() }

                    PokerHeartbeatAction.CLOSE -> runtime.cancel()
                    PokerHeartbeatAction.NONE -> Unit
                }
                if (isCurrent(runtime)) scheduleHeartbeat(runtime)
            }
        }
    }

    private fun isCurrent(runtime: ActiveConnection): Boolean = synchronized(lock) {
        running && active === runtime && session.isCurrent(runtime.epoch)
    }

    private class ActiveConnection(
        val epoch: PokerConnectionEpoch,
        val socket: PokerFrameSocket,
        val job: Job,
        val heartbeat: PokerHeartbeatMonitor,
        var heartbeatTask: PokerScheduledTask? = null,
        var peerEnvelopeVersion: Int? = null,
    ) : PokerEpochConnection {
        override fun close() = cancel()

        fun cancel() {
            heartbeatTask?.cancel()
            job.cancel()
            socket.close()
        }
    }
}
