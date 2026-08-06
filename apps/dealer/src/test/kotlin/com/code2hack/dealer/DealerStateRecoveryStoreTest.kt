package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalScope
import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.FileChangeContent
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.domain.UserInputRequestState
import com.code2hack.pokerdealer.domain.PokerBindingControl
import com.code2hack.pokerdealer.domain.PokerBindingDevice
import com.code2hack.pokerdealer.domain.PokerBindingMap
import com.code2hack.pokerdealer.domain.PokerBindingEntry
import com.code2hack.pokerdealer.domain.PokerOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DealerStateRecoveryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun samePhoneRebootRestoresOfflineStateAsObserverAndLocksUncertainRequest() = runBlocking {
        val locator = CodexThreadLocator("spark", "thread")
        val requestLocator = ServerRequestLocator("spark", 4, "request")
        val fileLocator = ServerRequestLocator("spark", 4, "file")
        val questionLocator = ServerRequestLocator("spark", 4, "question")
        val projection = DealerProjectionSnapshot(
            threads = listOf(
                DiscoveredThread(
                    locator = locator,
                    name = "Recovery",
                    workState = ThreadWorkState.ATTENTION_REQUIRED,
                    activeTurnId = "turn",
                    attached = true,
                    unreadCount = 7,
                    intendedControlSurface = ControlSurface.DEALER,
                ),
            ),
        )
        val pending = DealerPendingRequestSnapshot(
            commandApprovals = CommandApprovalState(
                mapOf(
                    requestLocator to CommandApprovalRequest(
                        locator = requestLocator,
                        thread = locator,
                        turnId = "turn",
                        itemId = "item",
                        approvalId = "approval",
                        scope = CommandApprovalScope("pwd", "/work", null, null),
                        proposedExecpolicyAmendment = null,
                        offeredDecisions = setOf(CommandApprovalDecision.ACCEPT),
                        fingerprint = "fingerprint",
                        createdAtMs = 1,
                        resolution = RequestResolutionState.RESPONDING,
                        decision = CommandApprovalDecision.ACCEPT,
                    ),
                ),
            ),
            fileApprovals = FileApprovalState(
                mapOf(
                    fileLocator to FileApprovalRequest(
                        locator = fileLocator,
                        thread = locator,
                        turnId = "turn",
                        itemId = "file-item",
                        reason = null,
                        grantRoot = null,
                        fileChanges = listOf(FileChangeContent("file", "update", "diff")),
                        wireFingerprint = "wire-file",
                        fingerprint = "file",
                        createdAtMs = 2,
                        reviewComplete = true,
                    ),
                ),
            ),
            userInputRequests = UserInputRequestState(
                mapOf(
                    questionLocator to UserInputRequest(
                        locator = questionLocator,
                        thread = locator,
                        turnId = "turn",
                        itemId = "question-item",
                        questions = listOf(
                            UserInputQuestion("id", "Header", "Question", null, false, false),
                        ),
                        autoResolutionMs = null,
                        receivedAtMs = 3,
                        fingerprint = "question",
                    ),
                ),
            ),
        )
        val root = temporaryFolder.newFolder()
        DealerStateRecoveryStore(root).apply {
            writeProjection(projection)
            writePendingRequests(pending)
        }

        val recovered = DealerStateRecoveryStore(root).read()
        val restored = DealerUiState(
            threadAttachments = ThreadAttachmentState(
                attached = setOf(locator),
                dealerClaims = setOf(locator),
            ),
        ).restoreAfterProcessDeath(
            attachments = setOf(locator),
            actions = ThreadActionState(drafts = mapOf(locator to "unsent")),
            cards = listOf(card(locator)),
            projection = recovered.projection,
            pendingRequests = recovered.pendingRequests,
        )

        assertEquals(7, restored.threads.getValue(locator).unreadCount)
        assertEquals(ControlSurface.NONE, restored.threads.getValue(locator).intendedControlSurface)
        assertTrue(restored.threadAttachments.dealerClaims.isEmpty())
        assertEquals("unsent", restored.threadActions.drafts[locator])
        assertEquals(RequestResolutionState.UNKNOWN, restored.commandApprovals.requests[requestLocator]?.resolution)
        assertEquals(RequestResolutionState.UNKNOWN, restored.fileApprovals.requests[fileLocator]?.resolution)
        assertEquals(RequestResolutionState.UNKNOWN, restored.userInputRequests.requests[questionLocator]?.resolution)
        assertTrue(restored.userInputAnswers.buffers.isEmpty())
        assertEquals(setOf(locator), restored.knownBlockingRequestThreads)
        assertEquals(listOf(card(locator)), restored.cards)
    }

    @Test
    fun corruptDerivedProjectionIsDiscardedButCorruptUncertaintyIsPreserved() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerStateRecoveryStore(root)
        store.writeProjection(DealerProjectionSnapshot())
        store.writePendingRequests(DealerPendingRequestSnapshot())
        root.resolve("thread-projection-v1.json").writeText("{")

        val projectionFailure = store.read()

        assertTrue(projectionFailure.errors.single().startsWith("Discarded corrupt cached thread projection"))
        assertFalse(root.resolve("thread-projection-v1.json").exists())
        assertTrue(projectionFailure.pendingRequestsWritable)

        root.resolve("pending-requests-v1.json").writeText("{")
        val requestFailure = store.read()

        assertFalse(requestFailure.pendingRequestsWritable)
        assertTrue(root.resolve("pending-requests-v1.json").exists())
        assertTrue(requestFailure.errors.single().startsWith("Unable to restore pending request uncertainty"))

        root.resolve("poker-bindings-v1.json").writeText("{")
        val bindingFailure = store.read()

        assertFalse(bindingFailure.pokerBindingsWritable)
        assertTrue(root.resolve("poker-bindings-v1.json").exists())
        assertTrue(bindingFailure.errors.last().startsWith("Unable to restore Poker bindings"))
    }

    @Test
    fun pokerBindingsSurviveRecovery() = runBlocking {
        val map = PokerBindingMap(
            revision = 2,
            entries = listOf(
                PokerBindingEntry(
                    PokerOperation.FN,
                    listOf(PokerBindingControl.remote("remote-a", 42)),
                ),
            ),
        )
        val root = temporaryFolder.newFolder()
        DealerStateRecoveryStore(root).apply {
            writePokerBindings(DealerPokerBindingSnapshot(map, listOf("remote-a", "remote-b")))
        }

        val recovered = DealerStateRecoveryStore(root).read()

        assertEquals(map, recovered.pokerBindings.map)
        assertEquals(listOf("remote-a", "remote-b"), recovered.pokerBindings.knownRemoteDescriptors)
        assertEquals(PokerBindingDevice.remote("remote-a"), recovered.pokerBindings.map.devices.single())
    }

    private fun card(locator: CodexThreadLocator) = Card(
        id = "card",
        conversationId = "${locator.hostId}/${locator.threadId}",
        sequence = 1,
        revision = 1,
        role = CardRole.AGENT,
        state = CardState.COMMITTED,
        fullText = "retained",
        createdAtMs = 1,
        updatedAtMs = 1,
        source = CardSource.CODEX_AGENT_MESSAGE,
    )
}
