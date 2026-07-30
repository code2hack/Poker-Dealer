package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
import java.security.MessageDigest

internal enum class ThreadNotificationPriority {
    HIGH,
    NORMAL,
}

internal data class ThreadNotificationContent(
    val title: String,
    val text: String,
    val publicTitle: String = "Poker–Dealer",
    val publicText: String = "Poker–Dealer needs attention",
)

internal data class ThreadNotificationTarget(
    val thread: CodexThreadLocator,
    val request: ServerRequestLocator?,
)

internal data class ThreadTransitionNotification(
    val key: String,
    val priority: ThreadNotificationPriority,
    val content: ThreadNotificationContent,
    val target: ThreadNotificationTarget,
)

internal class ThreadTransitionNotificationTracker {
    private val states = mutableMapOf<CodexThreadLocator, ThreadWorkState>()
    private val reconcilingHosts = mutableSetOf<String>()

    fun beginReconciliation(hostId: String) {
        reconcilingHosts += hostId
        states.keys.removeAll { it.hostId == hostId }
    }

    fun reconciled(hostId: String, threads: Collection<DiscoveredThread>) {
        states.keys.removeAll { it.hostId == hostId }
        threads.filter { it.locator.hostId == hostId }.forEach {
            it.workState?.let { state -> states[it.locator] = state }
        }
        reconcilingHosts -= hostId
    }

    fun transition(
        thread: DiscoveredThread,
        hostLabel: String,
        request: ServerRequestLocator?,
        activityVisible: Boolean,
        screenInteractive: Boolean,
    ): ThreadTransitionNotification? {
        val state = thread.workState ?: return null
        val hadBaseline = thread.locator in states
        val previous = states.put(thread.locator, state)
        if (thread.locator.hostId in reconcilingHosts ||
            !hadBaseline ||
            previous == state ||
            state == ThreadWorkState.BUSY ||
            activityVisible && screenInteractive
        ) {
            return null
        }
        return ThreadTransitionNotification(
            key = threadNotificationKey(thread.locator),
            priority = if (state == ThreadWorkState.ATTENTION_REQUIRED) {
                ThreadNotificationPriority.HIGH
            } else {
                ThreadNotificationPriority.NORMAL
            },
            content = ThreadNotificationContent(
                title = hostLabel,
                text = "${thread.name ?: "Unnamed thread"} · ${state.label()}",
            ),
            target = ThreadNotificationTarget(
                thread = thread.locator,
                request = request.takeIf { state == ThreadWorkState.ATTENTION_REQUIRED },
            ),
        )
    }
}

internal fun threadNotificationKey(locator: CodexThreadLocator): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${locator.hostId}\u0000${locator.threadId}".toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun ThreadWorkState.label(): String = when (this) {
    ThreadWorkState.BUSY -> "Busy"
    ThreadWorkState.ATTENTION_REQUIRED -> "Attention required"
    ThreadWorkState.READY -> "Ready"
}
