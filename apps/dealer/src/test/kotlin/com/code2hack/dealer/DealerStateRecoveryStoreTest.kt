package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalScope
import com.code2hack.pokerdealer.domain.CommandApprovalState
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.FileApprovalState
import com.code2hack.pokerdealer.domain.FileChangeContent
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.domain.UserInputRequestState
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
    fun retainedProjectionAndPendingRequestUncertaintySurviveRecreation() = runBlocking {
        val locator = CodexThreadLocator("host-a", "thread")
        val commandLocator = ServerRequestLocator("host-a", 4, "request")
        val fileLocator = ServerRequestLocator("host-a", 4, "file")
        val questionLocator = ServerRequestLocator("host-a", 4, "question")
        val projection = DealerProjectionSnapshot(
            threads = listOf(
                DiscoveredThread(
                    locator = locator,
                    name = "Recovery",
                    workState = ThreadWorkState.ATTENTION_REQUIRED,
                    activeTurnId = "turn",
                    attached = true,
                    unreadCount = 7,
                ),
            ),
        )
        val pending = DealerPendingRequestSnapshot(
            commandApprovals = CommandApprovalState(
                mapOf(
                    commandLocator to CommandApprovalRequest(
                        locator = commandLocator,
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

        assertEquals(projection, recovered.projection)
        assertEquals(pending, recovered.pendingRequests)
        assertTrue(recovered.pendingRequestsWritable)
        assertTrue(recovered.errors.isEmpty())
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
    }

    @Test
    fun legacyPokerBindingFileIsIgnoredBySuccessorRecovery() = runBlocking {
        val root = temporaryFolder.newFolder()
        root.resolve("poker-bindings-v1.json").writeText("{")

        val recovered = DealerStateRecoveryStore(root).read()

        assertTrue(recovered.errors.isEmpty())
        assertTrue(root.resolve("poker-bindings-v1.json").exists())
    }
}
