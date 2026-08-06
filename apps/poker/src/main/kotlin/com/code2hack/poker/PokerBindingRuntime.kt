package com.code2hack.poker

import com.code2hack.pokerdealer.domain.PokerBindingController

/** Process-local seam: the listener service and foreground input surface use one controller. */
internal object PokerBindingRuntime {
    val controller = PokerBindingController()

    @Volatile
    var isForeground: Boolean = false
        private set

    private var onLocalChange: (() -> Unit)? = null
    private var onConnectionLost: (() -> Unit)? = null

    @Synchronized
    fun attachService(onChanged: () -> Unit) {
        onLocalChange = onChanged
    }

    @Synchronized
    fun detachService() {
        onLocalChange = null
    }

    @Synchronized
    fun attachActivity(onLost: () -> Unit) {
        onConnectionLost = onLost
    }

    @Synchronized
    fun detachActivity() {
        onConnectionLost = null
    }

    fun notifyLocalChange() {
        val callback = synchronized(this) { onLocalChange }
        callback?.invoke()
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        if (foreground) return
        val wasLearning = controller.learningTarget != null
        controller.cancelLearning()
        if (wasLearning) notifyLocalChange()
    }

    fun notifyConnectionLost() {
        controller.connectionLost()
        val callback = synchronized(this) { onConnectionLost }
        callback?.invoke()
    }
}
