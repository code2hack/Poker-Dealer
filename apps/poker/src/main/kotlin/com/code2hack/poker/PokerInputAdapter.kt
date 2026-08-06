package com.code2hack.poker

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.code2hack.pokerdealer.domain.PokerCancellationReason
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerInputSource
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerOperation
import kotlin.math.abs

internal enum class PokerTouchAction {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
}

internal fun pokerTouchAction(actionMasked: Int): PokerTouchAction? = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> PokerTouchAction.DOWN
    MotionEvent.ACTION_POINTER_DOWN -> PokerTouchAction.POINTER_DOWN
    MotionEvent.ACTION_MOVE -> PokerTouchAction.MOVE
    MotionEvent.ACTION_POINTER_UP -> PokerTouchAction.POINTER_UP
    MotionEvent.ACTION_UP -> PokerTouchAction.UP
    MotionEvent.ACTION_CANCEL -> PokerTouchAction.CANCEL
    else -> null
}

internal data class PokerTouchEvent(
    val action: PokerTouchAction,
    val pointerCount: Int,
    val y: Float,
    val eventTimeMs: Long,
) {
    init {
        require(pointerCount >= 0) { "Pointer count must not be negative" }
        require(eventTimeMs >= 0) { "Touch event time must be non-negative" }
    }
}

internal enum class PokerFunctionAction {
    DOWN,
    UP,
    CANCEL,
}

@Suppress("DEPRECATION")
internal fun pokerFunctionAction(action: Int): PokerFunctionAction? = when (action) {
    KeyEvent.ACTION_DOWN -> PokerFunctionAction.DOWN
    KeyEvent.ACTION_UP -> PokerFunctionAction.UP
    KeyEvent.ACTION_MULTIPLE -> PokerFunctionAction.CANCEL
    else -> null
}

internal data class PokerFunctionEvent(
    val action: PokerFunctionAction,
    val eventTimeMs: Long,
    val repeatCount: Int = 0,
) {
    init {
        require(eventTimeMs >= 0) { "Function event time must be non-negative" }
        require(repeatCount >= 0) { "Function repeat count must not be negative" }
    }
}

/** Converts Android input into domain events; raw Android events stop at this class. */
internal class PokerBuiltInInputAdapter(
    private val controller: PokerInputController,
    private val touchSlopPx: Float = 24f,
    private val monotonicNowMs: () -> Long = { SystemClock.uptimeMillis() },
    private val onNavigationChanged: () -> Unit = {},
) {
    private enum class Owner {
        TOUCH,
        FUNCTION,
    }

    private data class TouchState(
        val downY: Float,
        var fingerCount: Int,
        var lastEventTimeMs: Long,
        var operation: PokerOperation? = null,
        var domainStarted: Boolean = false,
    )

    private data class FunctionState(
        var lastEventTimeMs: Long,
        var domainStarted: Boolean,
    )

    private var owner: Owner? = null
    private var touch: TouchState? = null
    private var function: FunctionState? = null

    init {
        require(touchSlopPx > 0) { "Touch slop must be positive" }
    }

    fun onTouchEvent(event: PokerTouchEvent): List<PokerInputController.Result> {
        if (owner == null && event.action == PokerTouchAction.DOWN) {
            owner = Owner.TOUCH
            val state = TouchState(
                downY = event.y,
                fingerCount = event.pointerCount.coerceAtLeast(1),
                lastEventTimeMs = event.eventTimeMs,
            )
            touch = state
            val result = dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = null,
                    phase = PokerInteractionPhase.BEGIN,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
            state.domainStarted = result != null
            return listOfNotNull(result)
        }
        if (owner != Owner.TOUCH) return emptyList()

        val state = touch ?: return emptyList()
        if (event.eventTimeMs < state.lastEventTimeMs) {
            return cancelTouch(state.lastEventTimeMs)
        }
        state.lastEventTimeMs = event.eventTimeMs
        state.fingerCount = maxOf(state.fingerCount, event.pointerCount)

        return when (event.action) {
            PokerTouchAction.DOWN -> emptyList()
            PokerTouchAction.POINTER_DOWN,
            PokerTouchAction.POINTER_UP,
            -> emptyList()

            PokerTouchAction.MOVE -> moveTouch(state, event)
            PokerTouchAction.UP -> finishTouch(state, event.eventTimeMs)
            PokerTouchAction.CANCEL -> cancelTouch(event.eventTimeMs)
        }
    }

    fun onFunctionEvent(event: PokerFunctionEvent): List<PokerInputController.Result> {
        if (event.action == PokerFunctionAction.DOWN && event.repeatCount > 0) return emptyList()
        if (owner == null && event.action == PokerFunctionAction.DOWN) {
            owner = Owner.FUNCTION
            val result = dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = PokerOperation.FN,
                    phase = PokerInteractionPhase.BEGIN,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
            function = FunctionState(event.eventTimeMs, result != null)
            return listOfNotNull(result)
        }
        if (owner != Owner.FUNCTION) return emptyList()

        val state = function ?: return emptyList()
        if (event.eventTimeMs < state.lastEventTimeMs) {
            return cancelFunction(state.lastEventTimeMs)
        }
        state.lastEventTimeMs = event.eventTimeMs
        return when (event.action) {
            PokerFunctionAction.DOWN -> emptyList()
            PokerFunctionAction.UP -> finishFunction(event.eventTimeMs)
            PokerFunctionAction.CANCEL -> cancelFunction(event.eventTimeMs)
        }
    }

    fun onFocusLost(): List<PokerInputController.Result> = cancelOwned(PokerCancellationReason.FOCUS_LOST)

    fun onDisconnected(): List<PokerInputController.Result> = cancelOwned(PokerCancellationReason.DISCONNECTED)

    private fun moveTouch(
        state: TouchState,
        event: PokerTouchEvent,
    ): List<PokerInputController.Result> {
        if (state.operation == null && abs(event.y - state.downY) >= touchSlopPx) {
            state.operation = operationFor(state.fingerCount, event.y - state.downY)
            val result = dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = state.operation!!,
                    phase = PokerInteractionPhase.UPDATE,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
            return listOfNotNull(result)
        }
        val operation = state.operation ?: return emptyList()
        return listOfNotNull(
            dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = operation,
                    phase = PokerInteractionPhase.UPDATE,
                    eventTimeMs = event.eventTimeMs,
                ),
            ),
        )
    }

    private fun finishTouch(
        state: TouchState,
        eventTimeMs: Long,
    ): List<PokerInputController.Result> {
        val operation = state.operation ?: if (state.fingerCount >= 2) {
            PokerOperation.TAPTAP
        } else {
            PokerOperation.TAP
        }
        val results = mutableListOf<PokerInputController.Result>()
        if (state.domainStarted) {
            dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = operation,
                    phase = PokerInteractionPhase.RELEASE,
                    eventTimeMs = eventTimeMs,
                ),
            )?.let(results::add)
        }
        clearTouch()
        return results
    }

    private fun finishFunction(eventTimeMs: Long): List<PokerInputController.Result> {
        val state = function ?: return emptyList()
        val result = if (state.domainStarted) {
            dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = PokerOperation.FN,
                    phase = PokerInteractionPhase.RELEASE,
                    eventTimeMs = eventTimeMs,
                ),
            )
        } else {
            null
        }
        clearFunction()
        return listOfNotNull(result)
    }

    private fun cancelTouch(eventTimeMs: Long): List<PokerInputController.Result> {
        val state = touch ?: return emptyList()
        val result = if (state.domainStarted) {
            controller.cancel(PokerCancellationReason.ACTION_CANCEL, eventTimeMs)
        } else {
            null
        }
        clearTouch()
        return listOfNotNull(result)
    }

    private fun cancelFunction(eventTimeMs: Long): List<PokerInputController.Result> {
        val state = function ?: return emptyList()
        val result = if (state.domainStarted) {
            controller.cancel(PokerCancellationReason.ACTION_CANCEL, eventTimeMs)
        } else {
            null
        }
        clearFunction()
        return listOfNotNull(result)
    }

    private fun cancelOwned(reason: PokerCancellationReason): List<PokerInputController.Result> {
        val eventTimeMs = monotonicNowMs()
        val result = when (owner) {
            Owner.TOUCH -> touch?.takeIf { it.domainStarted }?.let {
                controller.cancel(reason, eventTimeMs)
            }
            Owner.FUNCTION -> function?.takeIf { it.domainStarted }?.let {
                controller.cancel(reason, eventTimeMs)
            }
            null -> null
        }
        clear()
        return listOfNotNull(result)
    }

    private fun dispatch(interaction: PokerInteraction): PokerInputController.Result? =
        controller.reduce(interaction)?.also {
            if (it.navigationEffect != com.code2hack.pokerdealer.domain.PokerNavigationEffect.NONE) {
                onNavigationChanged()
            }
        }

    private fun operationFor(fingerCount: Int, deltaY: Float): PokerOperation = when {
        fingerCount >= 2 && deltaY > 0 -> PokerOperation.RIGHT
        fingerCount >= 2 -> PokerOperation.LEFT
        deltaY > 0 -> PokerOperation.DOWN
        else -> PokerOperation.UP
    }

    private fun clearTouch() {
        touch = null
        if (owner == Owner.TOUCH) owner = null
    }

    private fun clearFunction() {
        function = null
        if (owner == Owner.FUNCTION) owner = null
    }

    private fun clear() {
        owner = null
        touch = null
        function = null
    }
}

/** Android-only edge: MotionEvent/KeyEvent never cross into domain navigation. */
internal class PokerAndroidInputAdapter(
    private val builtIn: PokerBuiltInInputAdapter,
    private val functionKeyCode: Int = KeyEvent.KEYCODE_FUNCTION,
) {
    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = pokerTouchAction(event.actionMasked) ?: return false
        val pointerCount = event.pointerCount
        builtIn.onTouchEvent(
            PokerTouchEvent(
                action = action,
                pointerCount = pointerCount,
                y = if (pointerCount > 0) event.getY(0) else 0f,
                eventTimeMs = event.eventTime,
            ),
        )
        return true
    }

    @Suppress("DEPRECATION")
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != functionKeyCode) return false
        val action = pokerFunctionAction(event.action) ?: return true
        builtIn.onFunctionEvent(
            PokerFunctionEvent(
                action = action,
                eventTimeMs = event.eventTime,
                repeatCount = event.repeatCount,
            ),
        )
        return true
    }

    fun onFocusLost() = builtIn.onFocusLost()

    fun onDisconnected() = builtIn.onDisconnected()
}
