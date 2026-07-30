package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DealerThreadNotificationsTest {
    @Test
    fun notifiesOnlyWhenBackgroundedOrScreenOff() {
        assertNull(
            trackerAt(ThreadWorkState.BUSY).transition(
                thread(state = ThreadWorkState.ATTENTION_REQUIRED),
                hostLabel = "DGX Spark",
                request = request(),
                activityVisible = true,
                screenInteractive = true,
            ),
        )
        assertEquals(
            ThreadNotificationPriority.HIGH,
            trackerAt(ThreadWorkState.BUSY).transition(
                thread(state = ThreadWorkState.ATTENTION_REQUIRED),
                hostLabel = "DGX Spark",
                request = request(),
                activityVisible = false,
                screenInteractive = true,
            )?.priority,
        )
        assertEquals(
            ThreadNotificationPriority.HIGH,
            trackerAt(ThreadWorkState.BUSY).transition(
                thread(state = ThreadWorkState.ATTENTION_REQUIRED),
                hostLabel = "DGX Spark",
                request = request(),
                activityVisible = true,
                screenInteractive = false,
            )?.priority,
        )
    }

    @Test
    fun attentionAndReadyUsePrioritiesOneStableHostQualifiedKeyAndExactTargets() {
        val tracker = trackerAt(ThreadWorkState.BUSY)
        val attention = tracker.transition(
            thread(state = ThreadWorkState.ATTENTION_REQUIRED),
            "DGX Spark",
            request(),
            activityVisible = false,
            screenInteractive = true,
        )!!
        tracker.transition(
            thread(state = ThreadWorkState.BUSY),
            "DGX Spark",
            null,
            activityVisible = false,
            screenInteractive = true,
        )
        val ready = tracker.transition(
            thread(state = ThreadWorkState.READY),
            "DGX Spark",
            null,
            activityVisible = false,
            screenInteractive = true,
        )!!

        assertEquals(ThreadNotificationPriority.HIGH, attention.priority)
        assertEquals(ThreadNotificationPriority.NORMAL, ready.priority)
        assertEquals(attention.key, ready.key)
        assertEquals(request(), attention.target.request)
        assertNull(ready.target.request)
        assertEquals(thread().locator, attention.target.thread)

        val otherHostKey = threadNotificationKey(CodexThreadLocator("u4090", thread().locator.threadId))
        assertNotEquals(attention.key, otherHostKey)
        assertFalse(attention.key.contains("spark"))
        assertFalse(attention.key.contains(thread().locator.threadId))
    }

    @Test
    fun initialBaselineAndReconnectReconciliationDoNotNotify() {
        val tracker = ThreadTransitionNotificationTracker()
        assertNull(
            tracker.transition(
                thread(state = ThreadWorkState.READY),
                "DGX Spark",
                null,
                activityVisible = false,
                screenInteractive = true,
            ),
        )

        tracker.beginReconciliation("spark")
        assertNull(
            tracker.transition(
                thread(state = ThreadWorkState.ATTENTION_REQUIRED),
                "DGX Spark",
                request(),
                activityVisible = false,
                screenInteractive = true,
            ),
        )
        tracker.reconciled("spark", listOf(thread(state = ThreadWorkState.ATTENTION_REQUIRED)))
        assertNull(
            tracker.transition(
                thread(state = ThreadWorkState.ATTENTION_REQUIRED),
                "DGX Spark",
                request(),
                activityVisible = false,
                screenInteractive = true,
            ),
        )
    }

    @Test
    fun contentUsesOnlyHostThreadNameAndWorkStateWithGenericPublicForm() {
        val sensitive = "command diff answer question approval provider endpoint thread-id"
        val alert = trackerAt(ThreadWorkState.BUSY).transition(
            thread(
                state = ThreadWorkState.ATTENTION_REQUIRED,
                preview = sensitive,
                workingDirectory = sensitive,
            ),
            hostLabel = "DGX Spark",
            request = request(requestId = sensitive),
            activityVisible = false,
            screenInteractive = true,
        )!!
        val unlocked = "${alert.content.title} ${alert.content.text}"
        val public = "${alert.content.publicTitle} ${alert.content.publicText}"

        assertEquals("DGX Spark Review notifications · Attention required", unlocked)
        assertEquals("Poker–Dealer Poker–Dealer needs attention", public)
        assertFalse(unlocked.contains(sensitive))
        assertFalse(public.contains("DGX Spark"))
        assertFalse(public.contains("Review notifications"))
    }

    @Test
    fun simultaneousHostsProduceIndependentNotifications() {
        val tracker = ThreadTransitionNotificationTracker()
        val spark = thread(hostId = "spark", threadId = "shared", state = ThreadWorkState.BUSY)
        val u4090 = thread(hostId = "u4090", threadId = "shared", state = ThreadWorkState.BUSY)
        tracker.reconciled("spark", listOf(spark))
        tracker.reconciled("u4090", listOf(u4090))

        val first = tracker.transition(
            spark.copy(workState = ThreadWorkState.ATTENTION_REQUIRED),
            "DGX Spark",
            request(requestId = "spark-request"),
            activityVisible = false,
            screenInteractive = true,
        )!!
        val second = tracker.transition(
            u4090.copy(workState = ThreadWorkState.READY),
            "u4090",
            null,
            activityVisible = false,
            screenInteractive = true,
        )!!

        assertNotEquals(first.key, second.key)
        assertEquals(ThreadNotificationPriority.HIGH, first.priority)
        assertEquals(ThreadNotificationPriority.NORMAL, second.priority)
    }

    private fun trackerAt(state: ThreadWorkState): ThreadTransitionNotificationTracker =
        ThreadTransitionNotificationTracker().also {
            it.reconciled("spark", listOf(thread(state = state)))
        }

    private fun request(
        hostId: String = "spark",
        generation: Long = 1,
        requestId: String = "request-1",
    ) = ServerRequestLocator(hostId, generation, requestId)

    private fun thread(
        hostId: String = "spark",
        threadId: String = "thread-123",
        state: ThreadWorkState = ThreadWorkState.BUSY,
        preview: String? = null,
        workingDirectory: String? = null,
    ) = DiscoveredThread(
        locator = CodexThreadLocator(hostId, threadId),
        name = "Review notifications",
        preview = preview,
        workingDirectory = workingDirectory,
        workState = state,
    )
}
