package com.code2hack.poker

import com.code2hack.pokerdealer.protocol.PokerTransientNotice
import com.code2hack.pokerdealer.protocol.PokerTransientNoticeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One HUD overlay slot; a new notice replaces the old one and never queues. */
internal class PokerNoticeRuntime private constructor(
    private val scope: CoroutineScope,
) {
    private val slot = PokerTransientNoticeSlot()
    private val mutableNotice = MutableStateFlow<PokerTransientNotice?>(null)
    private var expiryJob: Job? = null

    val notice: StateFlow<PokerTransientNotice?> = mutableNotice.asStateFlow()

    fun show(notice: PokerTransientNotice) {
        val entry = slot.show(notice)
        mutableNotice.value = notice
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(notice.durationMs)
            if (slot.expire(entry.token)) mutableNotice.value = null
        }
    }

    companion object {
        private val production by lazy {
            PokerNoticeRuntime(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }

        val notice: StateFlow<PokerTransientNotice?>
            get() = production.notice

        fun show(notice: PokerTransientNotice) = production.show(notice)

        internal fun forTest(scope: CoroutineScope): PokerNoticeRuntime = PokerNoticeRuntime(scope)
    }
}
