package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.UserInputRequestState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState

internal data class DealerCoreState(
    val hostSessions: Map<String, HostSessionState> = emptyMap(),
    val threads: Map<CodexThreadLocator, DiscoveredThread> = emptyMap(),
    val threadAttachments: ThreadAttachmentState = ThreadAttachmentState(),
    val threadActions: ThreadActionState = ThreadActionState(),
    val cards: List<Card> = emptyList(),
    val commandApprovals: CommandApprovalState = CommandApprovalState(),
    val fileApprovals: FileApprovalState = FileApprovalState(),
    val userInputRequests: UserInputRequestState = UserInputRequestState(),
    val error: String? = null,
) {
    val blockingRequestThreads: Set<CodexThreadLocator>
        get() = commandApprovals.unresolvedThreads() +
            fileApprovals.unresolvedThreads() +
            userInputRequests.unresolvedThreads()

    fun withDiscoveredThreads(
        hostId: String,
        discovered: List<DiscoveredThread>,
    ): DealerCoreState {
        val retainedOtherHosts = threads.filterKeys { it.hostId != hostId }
        val localByLocator = discovered.associate { incoming ->
            val locator = incoming.locator
            val current = threads[locator]
            val attached = locator in threadAttachments.attached
            val claimed = threadAttachments.hasDealerClaim(locator)
            locator to incoming.copy(
                activeTurnId = current?.activeTurnId.takeIf { incoming.workState == ThreadWorkState.BUSY },
                attached = attached,
                unreadCount = current?.unreadCount ?: incoming.unreadCount,
                intendedControlSurface = if (claimed) ControlSurface.DEALER else ControlSurface.NONE,
            )
        }
        return copy(threads = retainedOtherHosts + localByLocator).withBlockingRequests()
    }

    fun restoreAfterProcessDeath(
        attachments: Set<CodexThreadLocator>,
        actions: ThreadActionState,
        retainedCards: List<Card>,
        projection: DealerProjectionSnapshot,
        pendingRequests: DealerPendingRequestSnapshot,
        restoreError: String? = null,
    ): DealerCoreState {
        val restoredThreads = projection.threads
            .filter { it.locator in attachments }
            .associate {
                it.locator to it.copy(
                    attached = true,
                    intendedControlSurface = ControlSurface.NONE,
                )
            }
        return copy(
            threads = restoredThreads,
            threadAttachments = ThreadAttachmentState(attached = attachments),
            threadActions = actions,
            cards = retainedCards.distinctBy { it.conversationId to it.id },
            commandApprovals = pendingRequests.commandApprovals.afterProcessDeath(),
            fileApprovals = pendingRequests.fileApprovals.afterProcessDeath(),
            userInputRequests = pendingRequests.userInputRequests.afterProcessDeath(),
            error = restoreError,
        ).withBlockingRequests()
    }

    fun withBlockingRequests(): DealerCoreState {
        val blocking = blockingRequestThreads
        return copy(
            threads = threads.mapValues { (locator, thread) ->
                when {
                    locator in blocking -> thread.copy(workState = ThreadWorkState.ATTENTION_REQUIRED)
                    thread.workState == ThreadWorkState.ATTENTION_REQUIRED -> thread.copy(
                        workState = if (thread.activeTurnId == null) ThreadWorkState.READY else ThreadWorkState.BUSY,
                    )
                    else -> thread
                }
            },
        )
    }

    fun pendingRequestSnapshot(): DealerPendingRequestSnapshot = DealerPendingRequestSnapshot(
        commandApprovals = commandApprovals,
        fileApprovals = fileApprovals,
        userInputRequests = userInputRequests,
    )

    fun durableProjection(): DealerProjectionSnapshot = DealerProjectionSnapshot(
        threads = threadAttachments.attached
            .mapNotNull(threads::get)
            .map {
                it.copy(
                    attached = true,
                    intendedControlSurface = ControlSurface.NONE,
                )
            }
            .sortedWith(compareBy({ it.locator.hostId }, { it.locator.threadId })),
    )
}

private fun CommandApprovalState.afterProcessDeath(): CommandApprovalState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) request
        else request.copy(resolution = RequestResolutionState.UNKNOWN)
    },
)

private fun FileApprovalState.afterProcessDeath(): FileApprovalState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) request
        else request.copy(resolution = RequestResolutionState.UNKNOWN)
    },
)

private fun UserInputRequestState.afterProcessDeath(): UserInputRequestState = copy(
    requests = requests.mapValues { (_, request) ->
        if (request.resolution == RequestResolutionState.RESOLVED) request
        else request.copy(resolution = RequestResolutionState.UNKNOWN)
    },
)
