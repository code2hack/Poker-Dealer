package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FileApprovalsTest {
    @Test
    fun `first action wins independently of a concurrent command request`() {
        val file = request()
        val command = CommandApprovalRequest(
            locator = ServerRequestLocator("host", 1, "command-request"),
            thread = file.thread,
            turnId = file.turnId,
            itemId = "command-item",
            approvalId = null,
            scope = CommandApprovalScope("pwd", "/work", null, null),
            proposedExecpolicyAmendment = null,
            offeredDecisions = setOf(CommandApprovalDecision.ACCEPT, CommandApprovalDecision.CANCEL),
            fingerprint = "command",
            createdAtMs = 1,
        )
        val files = FileApprovalState()
            .receive(file, sameIdReissueQualified = false)
            .begin(file.locator, FileApprovalDecision.ACCEPT)
            .begin(file.locator, FileApprovalDecision.DECLINE)
        val commands = CommandApprovalState()
            .receive(command, sameIdReissueQualified = false)

        assertEquals(FileApprovalDecision.ACCEPT, files.requests.getValue(file.locator).decision)
        assertEquals(RequestResolutionState.RESPONDING, files.requests.getValue(file.locator).resolution)
        assertEquals(RequestResolutionState.PENDING, commands.requests.getValue(command.locator).resolution)
    }

    @Test
    fun `incomplete review cannot be actioned and fails closed without changing turn state`() {
        val incomplete = request().copy(reviewComplete = false, fileChanges = emptyList())
        val pending = FileApprovalState().receive(incomplete, sameIdReissueQualified = false)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            pending.begin(incomplete.locator, FileApprovalDecision.ACCEPT)
        }
        val failed = pending.failClosed(incomplete.locator, "diff incomplete")
            .requests
            .getValue(incomplete.locator)
        assertEquals(RequestResolutionState.RESOLVED, failed.resolution)
        assertEquals("diff incomplete", failed.failureReason)
    }

    @Test
    fun `disconnect safety cancel is valid even while review is incomplete`() {
        val request = request().copy(reviewComplete = false, fileChanges = emptyList())
        val cancelling = FileApprovalState()
            .receive(request, sameIdReissueQualified = false)
            .begin(request.locator, FileApprovalDecision.CANCEL)
            .requests
            .getValue(request.locator)

        assertEquals(RequestResolutionState.RESPONDING, cancelling.resolution)
        assertEquals(FileApprovalDecision.CANCEL, cancelling.decision)
    }

    @Test
    fun `disconnect and reissue never replay an uncertain response`() {
        val old = request()
        val current = old.copy(locator = old.locator.copy(appServerGeneration = 2))
        val unqualified = FileApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .begin(old.locator, FileApprovalDecision.CANCEL)
            .connectionLost("host", 1)
            .receive(current, sameIdReissueQualified = false)

        assertEquals(2, unqualified.requests.size)
        assertEquals(RequestResolutionState.UNKNOWN, unqualified.requests.getValue(old.locator).resolution)
        assertEquals(RequestResolutionState.PENDING, unqualified.requests.getValue(current.locator).resolution)
        assertNull(unqualified.requests.getValue(current.locator).decision)
    }

    @Test
    fun `external resolution remains in history without guessing`() {
        val request = request()
        val resolved = FileApprovalState()
            .receive(request, sameIdReissueQualified = false)
            .resolved("host", 1, "file-request", "thread")
            .requests
            .getValue(request.locator)

        assertEquals(RequestResolutionState.RESOLVED, resolved.resolution)
        assertEquals(true, resolved.resolvedElsewhere)
        assertNull(resolved.decision)
    }

    private fun request() = FileApprovalRequest(
        locator = ServerRequestLocator("host", 1, "file-request"),
        thread = CodexThreadLocator("host", "thread"),
        turnId = "turn",
        itemId = "file-item",
        reason = null,
        grantRoot = null,
        fileChanges = listOf(FileChangeContent("file.kt", "update", "-old\n+new\n")),
        wireFingerprint = "wire-file",
        fingerprint = "file",
        createdAtMs = 1,
        reviewComplete = true,
    )
}
