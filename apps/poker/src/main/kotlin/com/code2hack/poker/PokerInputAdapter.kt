package com.code2hack.poker

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.code2hack.pokerdealer.domain.PokerCancellationReason
import com.code2hack.pokerdealer.domain.PokerBindingCaptureResult
import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingController
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerBindingMap
import com.code2hack.pokerdealer.domain.PokerGlassesGesture
import com.code2hack.pokerdealer.domain.PokerInputController
import com.code2hack.pokerdealer.domain.PokerInputSource
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPostureSample
import com.code2hack.pokerdealer.domain.PokerWheelState
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

internal enum class PokerAndroidInputDeviceKind {
    BUILT_IN,
    EXTERNAL_HID,
    OTHER,
}

internal fun pokerAndroidInputDeviceKind(
    isExternal: Boolean,
    isVirtual: Boolean,
): PokerAndroidInputDeviceKind = when {
    isVirtual -> PokerAndroidInputDeviceKind.OTHER
    isExternal -> PokerAndroidInputDeviceKind.EXTERNAL_HID
    else -> PokerAndroidInputDeviceKind.BUILT_IN
}

internal fun pokerAndroidInputDeviceKind(device: InputDevice?): PokerAndroidInputDeviceKind {
    if (device == null || device.isVirtual || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return PokerAndroidInputDeviceKind.OTHER
    }
    return pokerAndroidInputDeviceKind(isExternal = device.isExternal, isVirtual = false)
}

internal data class PokerAndroidKeyEvent(
    val deviceKind: PokerAndroidInputDeviceKind,
    val descriptor: String?,
    val keyCode: Int,
    val action: Int,
    val eventTimeMs: Long,
    val repeatCount: Int,
)

/** Converts Android input into domain events; raw Android events stop at this class. */
internal class PokerBuiltInInputAdapter(
    private val controller: PokerInputController,
    private val bindings: PokerBindingController = PokerBindingController(),
    private val touchSlopPx: Float = 24f,
    private val monotonicNowMs: () -> Long = { SystemClock.uptimeMillis() },
    private val onNavigationChanged: () -> Unit = {},
    private val onResult: (PokerInputController.Result) -> Unit = {},
    private val onWheelChanged: (PokerWheelState) -> Unit = {},
    private val photoHandler: ((PokerInteraction) -> Boolean)? = null,
) {
    private enum class Owner {
        TOUCH,
        FUNCTION,
    }

    private data class TouchState(
        val downY: Float,
        val map: PokerBindingMap,
        var fingerCount: Int,
        var lastEventTimeMs: Long,
        var gesture: PokerGlassesGesture? = null,
        var operation: PokerOperation? = null,
        var domainStarted: Boolean = false,
    )

    private data class FunctionState(
        val operation: PokerOperation?,
        var lastEventTimeMs: Long,
        var domainStarted: Boolean,
    )

    private var owner: Owner? = null
    private var touch: TouchState? = null
    private var function: FunctionState? = null
    private var photoConsumed = false

    init {
        require(touchSlopPx > 0) { "Touch slop must be positive" }
    }

    fun onTouchEvent(event: PokerTouchEvent): List<PokerInputController.Result> {
        if (owner == null && event.action == PokerTouchAction.DOWN) {
            owner = Owner.TOUCH
            val state = TouchState(
                downY = event.y,
                map = bindings.map,
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
            state.domainStarted = result != null || photoConsumed
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
            val operation = bindings.map.operationFor(
                PokerBindingControl.glasses(PokerGlassesGesture.FUNCTION_BUTTON),
            )
            val result = dispatch(
                PokerInteraction(
                    source = PokerInputSource.GLASSES,
                    operation = operation,
                    phase = PokerInteractionPhase.BEGIN,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
            function = FunctionState(
                operation = operation,
                lastEventTimeMs = event.eventTimeMs,
                domainStarted = result != null || photoConsumed,
            )
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

    fun onPosture(sample: PokerPostureSample): PokerWheelState? =
        controller.updatePosture(sample)?.also(onWheelChanged)

    private fun moveTouch(
        state: TouchState,
        event: PokerTouchEvent,
    ): List<PokerInputController.Result> {
        if (state.gesture == null && abs(event.y - state.downY) >= touchSlopPx) {
            state.gesture = gestureFor(state.fingerCount, event.y - state.downY)
            state.operation = state.map.operationFor(PokerBindingControl.glasses(state.gesture!!))
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
        val gesture = state.gesture ?: if (state.fingerCount >= 2) {
            PokerGlassesGesture.DUAL_FINGER_TAP
        } else {
            PokerGlassesGesture.SINGLE_FINGER_TAP
        }
        val operation = state.operation ?: state.map.operationFor(PokerBindingControl.glasses(gesture))
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
                    operation = state.operation,
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
            controller.cancel(PokerCancellationReason.ACTION_CANCEL, eventTimeMs)?.also {
                onResult(it)
                onWheelChanged(it.wheelState)
            }
        } else {
            null
        }
        clearTouch()
        return listOfNotNull(result)
    }

    private fun cancelFunction(eventTimeMs: Long): List<PokerInputController.Result> {
        val state = function ?: return emptyList()
        val result = if (state.domainStarted) {
            controller.cancel(PokerCancellationReason.ACTION_CANCEL, eventTimeMs)?.also {
                onResult(it)
                onWheelChanged(it.wheelState)
            }
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
                controller.cancel(reason, eventTimeMs)?.also { result ->
                    onResult(result)
                    onWheelChanged(result.wheelState)
                }
            }
            Owner.FUNCTION -> function?.takeIf { it.domainStarted }?.let {
                controller.cancel(reason, eventTimeMs)?.also { result ->
                    onResult(result)
                    onWheelChanged(result.wheelState)
                }
            }
            null -> null
        }
        clear()
        return listOfNotNull(result)
    }

    private fun dispatch(interaction: PokerInteraction): PokerInputController.Result? =
        if (photoHandler?.invoke(interaction) == true) {
            photoConsumed = true
            null
        } else controller.reduce(interaction)?.also {
            onResult(it)
            onWheelChanged(it.wheelState)
            if (it.navigationEffect != com.code2hack.pokerdealer.domain.PokerNavigationEffect.NONE) {
                onNavigationChanged()
            }
        }

    private fun gestureFor(fingerCount: Int, deltaY: Float): PokerGlassesGesture = when {
        fingerCount >= 2 && deltaY > 0 -> PokerGlassesGesture.DOUBLE_FINGER_SWIPE_FORWARD
        fingerCount >= 2 -> PokerGlassesGesture.DOUBLE_FINGER_SWIPE_BACKWARD
        deltaY > 0 -> PokerGlassesGesture.SINGLE_FINGER_SWIPE_FORWARD
        else -> PokerGlassesGesture.SINGLE_FINGER_SWIPE_BACKWARD
    }

    private fun clearTouch() {
        touch = null
        photoConsumed = false
        if (owner == Owner.TOUCH) owner = null
    }

    private fun clearFunction() {
        function = null
        photoConsumed = false
        if (owner == Owner.FUNCTION) owner = null
    }

    private fun clear() {
        owner = null
        touch = null
        function = null
        photoConsumed = false
    }
}

internal enum class PokerRemoteKeyAction {
    DOWN,
    UP,
    MULTIPLE,
}

internal data class PokerRemoteKeyEvent(
    val descriptor: String,
    val keyCode: Int,
    val action: PokerRemoteKeyAction,
    val eventTimeMs: Long,
    val repeatCount: Int = 0,
) {
    init {
        require(descriptor.isNotBlank()) { "Remote descriptor must not be blank" }
        require(keyCode >= 0) { "Remote key code must not be negative" }
        require(eventTimeMs >= 0) { "Remote event time must be non-negative" }
        require(repeatCount >= 0) { "Remote repeat count must not be negative" }
    }
}

/** Converts one bonded remote's exact descriptor/keyCode stream into canonical Poker input. */
internal class PokerRemoteInputAdapter(
    private val controller: PokerInputController,
    private val bindings: PokerBindingController,
    private val isForeground: () -> Boolean = { true },
    private val onNavigationChanged: () -> Unit = {},
    private val onBindingChanged: () -> Unit = {},
    private val onNotice: (String) -> Unit = {},
    private val onResult: (PokerInputController.Result) -> Unit = {},
    private val onWheelChanged: (PokerWheelState) -> Unit = {},
    private val photoHandler: ((PokerInteraction) -> Boolean)? = null,
) {
    private data class ActiveKey(
        val descriptor: String,
        val keyCode: Int,
        val operation: PokerOperation?,
        val learning: Boolean,
        val startedAtMs: Long,
    )

    private var active: ActiveKey? = null

    fun onKeyEvent(event: PokerRemoteKeyEvent): Boolean {
        if (!isForeground()) return false

        if (bindings.observeRemote(event.descriptor)) onBindingChanged()
        val learning = bindings.learningTarget?.takeIf {
            it.device == PokerBindingDevice.remote(event.descriptor)
        }
        val managed = event.descriptor in bindings.state.knownRemoteDescriptors
        if (learning == null && !managed) return false

        return when (event.action) {
            PokerRemoteKeyAction.DOWN -> onDown(event, learning != null)
            PokerRemoteKeyAction.UP -> onUp(event)
            PokerRemoteKeyAction.MULTIPLE -> onMultiple(event)
        }
    }

    fun onFocusLost(eventTimeMs: Long = SystemClock.uptimeMillis()) {
        cancelActive(PokerCancellationReason.FOCUS_LOST, eventTimeMs)
        if (bindings.learningTarget != null) {
            bindings.cancelLearning()
            onBindingChanged()
        }
    }

    fun onDisconnected(descriptor: String, eventTimeMs: Long = SystemClock.uptimeMillis()) {
        if (active?.descriptor == descriptor) {
            cancelActive(PokerCancellationReason.DISCONNECTED, eventTimeMs)
        }
        val wasLearning = bindings.learningTarget?.device?.descriptor == descriptor
        bindings.deviceDisconnected(descriptor)
        if (wasLearning) onBindingChanged()
    }

    fun onConnectionLost(eventTimeMs: Long = SystemClock.uptimeMillis()) {
        cancelActive(PokerCancellationReason.DISCONNECTED, eventTimeMs)
        bindings.connectionLost()
    }

    private fun onDown(event: PokerRemoteKeyEvent, learning: Boolean): Boolean {
        if (event.repeatCount > 0) return true
        active?.let { current ->
            if (current.learning &&
                (current.descriptor != event.descriptor || current.keyCode != event.keyCode)
            ) {
                active = null
                onNotice("Cannot bind")
            }
            return true
        }
        if (learning) {
            if (event.keyCode <= 0) {
                onNotice("Cannot bind")
                return true
            }
            active = ActiveKey(
                descriptor = event.descriptor,
                keyCode = event.keyCode,
                operation = null,
                learning = true,
                startedAtMs = event.eventTimeMs,
            )
            return true
        }

        val operation = runCatching {
            bindings.map.operationFor(PokerBindingControl.remote(event.descriptor, event.keyCode))
        }.getOrNull()
        active = ActiveKey(
            descriptor = event.descriptor,
            keyCode = event.keyCode,
            operation = operation,
            learning = false,
            startedAtMs = event.eventTimeMs,
        )
        if (operation != null) {
            dispatch(
                PokerInteraction(
                    source = PokerInputSource.REMOTE,
                    operation = operation,
                    phase = PokerInteractionPhase.BEGIN,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
        }
        return true
    }

    private fun onUp(event: PokerRemoteKeyEvent): Boolean {
        val current = active ?: return true
        if (current.descriptor != event.descriptor || current.keyCode != event.keyCode) return true
        active = null
        if (event.eventTimeMs < current.startedAtMs) {
            if (!current.learning) {
                controller.cancel(PokerCancellationReason.ACTION_CANCEL, current.startedAtMs)?.also {
                    onResult(it)
                    onWheelChanged(it.wheelState)
                }
            }
            return true
        }
        if (current.learning) {
            when (bindings.capture(PokerBindingControl.remote(event.descriptor, event.keyCode))) {
                PokerBindingCaptureResult.APPLIED -> onBindingChanged()
                PokerBindingCaptureResult.UNSUPPORTED -> onNotice("Cannot bind")
                PokerBindingCaptureResult.IGNORED -> Unit
            }
        } else if (current.operation != null) {
            dispatch(
                PokerInteraction(
                    source = PokerInputSource.REMOTE,
                    operation = current.operation,
                    phase = PokerInteractionPhase.RELEASE,
                    eventTimeMs = event.eventTimeMs,
                ),
            )
        }
        return true
    }

    private fun onMultiple(event: PokerRemoteKeyEvent): Boolean {
        val current = active ?: run {
            if (bindings.learningTarget != null) onNotice("Cannot bind")
            return true
        }
        active = null
        if (current.learning) {
            onNotice("Cannot bind")
        } else {
            controller.cancel(PokerCancellationReason.ACTION_CANCEL, event.eventTimeMs)?.also {
                onResult(it)
                onWheelChanged(it.wheelState)
            }
        }
        return true
    }

    private fun cancelActive(reason: PokerCancellationReason, eventTimeMs: Long) {
        val current = active ?: return
        if (!current.learning) {
            controller.cancel(reason, eventTimeMs)?.also {
                onResult(it)
                onWheelChanged(it.wheelState)
            }
        }
        active = null
    }

    private fun dispatch(interaction: PokerInteraction) {
        if (photoHandler?.invoke(interaction) == true) return
        controller.reduce(interaction)?.let {
            onResult(it)
            onWheelChanged(it.wheelState)
            if (it.navigationEffect != com.code2hack.pokerdealer.domain.PokerNavigationEffect.NONE) {
                onNavigationChanged()
            }
        }
    }
}

/** Android-only edge: MotionEvent/KeyEvent never cross into domain navigation. */
internal class PokerAndroidInputAdapter(
    private val builtIn: PokerBuiltInInputAdapter,
    private val functionKeyCode: Int = KeyEvent.KEYCODE_FUNCTION,
    private val remote: PokerRemoteInputAdapter? = null,
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
        val device = event.device
        return onKeyEvent(
            PokerAndroidKeyEvent(
                deviceKind = pokerAndroidInputDeviceKind(device),
                descriptor = device?.descriptor,
                keyCode = event.keyCode,
                action = event.action,
                eventTimeMs = event.eventTime,
                repeatCount = event.repeatCount,
            ),
        )
    }

    @Suppress("DEPRECATION")
    fun onKeyEvent(event: PokerAndroidKeyEvent): Boolean {
        if (event.deviceKind == PokerAndroidInputDeviceKind.EXTERNAL_HID) {
            val descriptor = event.descriptor?.takeIf(String::isNotBlank) ?: return false
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> PokerRemoteKeyAction.DOWN
                KeyEvent.ACTION_UP -> PokerRemoteKeyAction.UP
                KeyEvent.ACTION_MULTIPLE -> PokerRemoteKeyAction.MULTIPLE
                else -> return true
            }
            return remote?.onKeyEvent(
                PokerRemoteKeyEvent(
                    descriptor = descriptor,
                    keyCode = event.keyCode,
                    action = action,
                    eventTimeMs = event.eventTimeMs,
                    repeatCount = event.repeatCount,
                ),
            ) == true
        }
        if (event.deviceKind != PokerAndroidInputDeviceKind.BUILT_IN ||
            event.keyCode != functionKeyCode
        ) return false
        val action = pokerFunctionAction(event.action) ?: return true
        builtIn.onFunctionEvent(
            PokerFunctionEvent(
                action = action,
                eventTimeMs = event.eventTimeMs,
                repeatCount = event.repeatCount,
            ),
        )
        return true
    }

    fun onFocusLost() {
        builtIn.onFocusLost()
        remote?.onFocusLost()
    }

    fun onDisconnected() {
        builtIn.onDisconnected()
        remote?.onConnectionLost()
    }

    fun onConnectionLost() {
        builtIn.onDisconnected()
        remote?.onConnectionLost()
    }

    fun onRemoteDisconnected(descriptor: String) = remote?.onDisconnected(descriptor)

    fun onPosture(sample: PokerPostureSample): PokerWheelState? = builtIn.onPosture(sample)
}
