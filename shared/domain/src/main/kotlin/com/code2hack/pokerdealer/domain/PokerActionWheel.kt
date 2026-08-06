package com.code2hack.pokerdealer.domain

import java.util.UUID
import kotlin.math.abs

@kotlinx.serialization.Serializable
enum class PokerWheelAction {
    PHOTO,
    PRIMARY,
    MORSE,
    ASR,
}

@kotlinx.serialization.Serializable
enum class PokerPrimaryAction(val label: String) {
    REQUEST("Submit request"),
    SEND("Send"),
    STEER("Steer"),
    INTERRUPT("Interrupt");

    companion object {
        /** Source-compatible name for callers that describe the action as request submission. */
        val SUBMIT_REQUEST: PokerPrimaryAction
            get() = REQUEST
    }
}

data class PokerPostureSample(
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val eventTimeMs: Long,
)

typealias PokerPosture = PokerPostureSample

data class PokerWheelContext(
    val targetId: String = "",
    val controlGeneration: Long = 0L,
    val connectionEpoch: Long = 0L,
    val modeSession: String = "",
    val photoAvailable: Boolean = false,
    val primaryAction: PokerPrimaryAction? = null,
    val morseAvailable: Boolean = false,
    val asrAvailable: Boolean = false,
) {
    fun isAvailable(action: PokerWheelAction): Boolean = when (action) {
        PokerWheelAction.PHOTO -> photoAvailable
        PokerWheelAction.PRIMARY -> primaryAction != null
        PokerWheelAction.MORSE -> morseAvailable
        PokerWheelAction.ASR -> asrAvailable
    }
}

enum class PokerWheelPosture {
    NONE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

data class PokerWheelState(
    val sessionId: String? = null,
    val opened: Boolean = false,
    val posture: PokerWheelPosture = PokerWheelPosture.NONE,
    val stable: Boolean = false,
    val highlightedAction: PokerWheelAction? = null,
    val context: PokerWheelContext = PokerWheelContext(),
    val cancelled: Boolean = false,
)

data class PokerWheelSelection(
    val sessionId: String,
    val action: PokerWheelAction,
    val primaryAction: PokerPrimaryAction?,
    val context: PokerWheelContext,
)

/** Fixed four-sector posture selection. A remote can start FN, but never supplies posture. */
class PokerActionWheel(
    private val longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_TIMEOUT_MS,
    private val stabilityMs: Long = DEFAULT_STABILITY_MS,
    private val stalePostureMs: Long = DEFAULT_STALE_POSTURE_MS,
    private val centralDeadzoneDegrees: Float = DEFAULT_CENTRAL_DEADZONE_DEGREES,
    private val diagonalRejectionDegrees: Float = DEFAULT_DIAGONAL_REJECTION_DEGREES,
    private val hysteresisDegrees: Float = DEFAULT_HYSTERESIS_DEGREES,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Active(
        val id: String,
        val startedAtMs: Long,
        val baseline: PokerPostureSample,
        var lastPosture: PokerPostureSample,
        var context: PokerWheelContext,
        val initialPrimaryAction: PokerPrimaryAction?,
        var opened: Boolean = false,
        var candidate: PokerWheelPosture = PokerWheelPosture.NONE,
        var candidateSinceMs: Long = 0L,
        var stable: Boolean = false,
    )

    private var active: Active? = null

    init {
        require(longPressTimeoutMs >= 0) { "Long-press timeout must not be negative" }
        require(stabilityMs >= 0) { "Wheel stability duration must not be negative" }
        require(stalePostureMs >= 0) { "Wheel stale-posture duration must not be negative" }
        require(centralDeadzoneDegrees >= 0) { "Wheel deadzone must not be negative" }
        require(diagonalRejectionDegrees >= 0) { "Wheel diagonal band must not be negative" }
        require(hysteresisDegrees >= 0) { "Wheel hysteresis must not be negative" }
    }

    fun begin(
        context: PokerWheelContext,
        baseline: PokerPostureSample,
        atMs: Long = baseline.eventTimeMs,
    ): PokerWheelState {
        require(atMs >= 0) { "Wheel begin time must not be negative" }
        val current = Active(
            id = sessionId().also { require(it.isNotBlank()) { "Wheel session ID must not be blank" } },
            startedAtMs = atMs,
            baseline = baseline,
            lastPosture = baseline,
            context = context,
            initialPrimaryAction = context.primaryAction,
            candidateSinceMs = atMs,
        )
        active = current
        return state(current)
    }

    fun update(
        sample: PokerPostureSample,
        context: PokerWheelContext = active?.context ?: PokerWheelContext(),
    ): PokerWheelState {
        val current = active ?: return PokerWheelState()
        if (!sameTarget(current.context, context) ||
            current.initialPrimaryAction != context.primaryAction ||
            sample.eventTimeMs < current.lastPosture.eventTimeMs
        ) {
            return cancelState()
        }
        current.context = context
        current.lastPosture = sample
        current.opened = sample.eventTimeMs - current.startedAtMs >= longPressTimeoutMs

        val candidate = if (context.isAvailable(classifyAction(current.candidate))) {
            classify(
                pitch = sample.pitchDegrees - current.baseline.pitchDegrees,
                roll = sample.rollDegrees - current.baseline.rollDegrees,
                previous = current.candidate,
            )
        } else {
            classify(
                pitch = sample.pitchDegrees - current.baseline.pitchDegrees,
                roll = sample.rollDegrees - current.baseline.rollDegrees,
                previous = PokerWheelPosture.NONE,
            )
        }.takeIf { posture -> posture.action()?.let(context::isAvailable) != false }
            ?: PokerWheelPosture.NONE

        if (candidate != current.candidate) {
            current.candidate = candidate
            current.candidateSinceMs = sample.eventTimeMs
            current.stable = false
        } else if (candidate != PokerWheelPosture.NONE) {
            current.stable = sample.eventTimeMs - current.candidateSinceMs >= stabilityMs
        } else {
            current.stable = false
        }
        return state(current)
    }

    fun release(
        atMs: Long,
        context: PokerWheelContext = active?.context ?: PokerWheelContext(),
    ): PokerWheelSelection? {
        val current = active ?: return null
        current.opened = atMs - current.startedAtMs >= longPressTimeoutMs
        if (current.candidate != PokerWheelPosture.NONE) {
            current.stable = atMs - current.candidateSinceMs >= stabilityMs
        }
        active = null
        if (!sameTarget(current.context, context) ||
            atMs - current.startedAtMs < longPressTimeoutMs ||
            atMs < current.lastPosture.eventTimeMs ||
            atMs - current.lastPosture.eventTimeMs > stalePostureMs ||
            !current.opened ||
            !current.stable ||
            context.primaryAction != current.initialPrimaryAction
        ) {
            return null
        }
        val posture = current.candidate
        val action = posture.action() ?: return null
        if (!context.isAvailable(action)) return null
        if (action == PokerWheelAction.PRIMARY &&
            context.primaryAction != current.context.primaryAction
        ) {
            return null
        }
        return PokerWheelSelection(
            sessionId = current.id,
            action = action,
            primaryAction = context.primaryAction.takeIf { action == PokerWheelAction.PRIMARY },
            context = current.context,
        )
    }

    fun cancel(): PokerWheelState = cancelState()

    fun state(): PokerWheelState = active?.let(::state) ?: PokerWheelState()

    private fun state(current: Active): PokerWheelState = PokerWheelState(
        sessionId = current.id,
        opened = current.opened,
        posture = current.candidate,
        stable = current.stable,
        highlightedAction = current.candidate.action()
            ?.takeIf { current.stable && current.context.isAvailable(it) },
        context = current.context,
    )

    private fun cancelState(): PokerWheelState {
        val current = active ?: return PokerWheelState(cancelled = true)
        active = null
        return state(current).copy(cancelled = true, opened = false, stable = false, highlightedAction = null)
    }

    private fun sameTarget(first: PokerWheelContext, second: PokerWheelContext): Boolean =
        first.targetId == second.targetId &&
            first.controlGeneration == second.controlGeneration &&
            first.connectionEpoch == second.connectionEpoch &&
            first.modeSession == second.modeSession

    private fun classify(pitch: Float, roll: Float, previous: PokerWheelPosture): PokerWheelPosture {
        val pitchAbs = abs(pitch)
        val rollAbs = abs(roll)
        val previousAxis = when (previous) {
            PokerWheelPosture.UP, PokerWheelPosture.DOWN -> pitchAbs
            PokerWheelPosture.LEFT, PokerWheelPosture.RIGHT -> rollAbs
            PokerWheelPosture.NONE -> 0f
        }
        val otherAxis = when (previous) {
            PokerWheelPosture.UP, PokerWheelPosture.DOWN -> rollAbs
            PokerWheelPosture.LEFT, PokerWheelPosture.RIGHT -> pitchAbs
            PokerWheelPosture.NONE -> 0f
        }
        if (previous != PokerWheelPosture.NONE &&
            previousAxis >= centralDeadzoneDegrees - hysteresisDegrees &&
            previousAxis + hysteresisDegrees >= otherAxis &&
            previousAxis > 0f
        ) {
            return previous
        }
        if (maxOf(pitchAbs, rollAbs) <= centralDeadzoneDegrees) return PokerWheelPosture.NONE
        if (abs(pitchAbs - rollAbs) <= diagonalRejectionDegrees) {
            return PokerWheelPosture.NONE
        }
        return if (pitchAbs > rollAbs) {
            if (pitch >= 0f) PokerWheelPosture.UP else PokerWheelPosture.DOWN
        } else if (roll >= 0f) {
            PokerWheelPosture.RIGHT
        } else {
            PokerWheelPosture.LEFT
        }
    }

    private fun PokerWheelPosture.action(): PokerWheelAction? = when (this) {
        PokerWheelPosture.UP -> PokerWheelAction.PHOTO
        PokerWheelPosture.DOWN -> PokerWheelAction.PRIMARY
        PokerWheelPosture.LEFT -> PokerWheelAction.MORSE
        PokerWheelPosture.RIGHT -> PokerWheelAction.ASR
        PokerWheelPosture.NONE -> null
    }

    private fun classifyAction(posture: PokerWheelPosture): PokerWheelAction =
        posture.action() ?: PokerWheelAction.PRIMARY

    companion object {
        const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L
        const val DEFAULT_STABILITY_MS = 100L
        const val DEFAULT_STALE_POSTURE_MS = 250L
        const val DEFAULT_CENTRAL_DEADZONE_DEGREES = 10f
        const val DEFAULT_DIAGONAL_REJECTION_DEGREES = 5f
        const val DEFAULT_HYSTERESIS_DEGREES = 3f
    }
}
