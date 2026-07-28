package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandApprovalsTest {
    @Test
    fun `multiple requests resolve independently and duplicate tap loses`() {
        val first = request("host-a", 1, "request-1", "thread-1", "fingerprint-1")
        val second = request("host-b", 1, "request-1", "thread-1", "fingerprint-2")
        val pending = CommandApprovalState()
            .receive(first, sameIdReissueQualified = false)
            .receive(second, sameIdReissueQualified = false)

        val responding = pending
            .begin(first.locator, CommandApprovalDecision.ACCEPT)
            .begin(first.locator, CommandApprovalDecision.DECLINE)

        assertEquals(CommandApprovalDecision.ACCEPT, responding.requests.getValue(first.locator).decision)
        assertEquals(RequestResolutionState.RESPONDING, responding.requests.getValue(first.locator).resolution)
        assertEquals(RequestResolutionState.PENDING, responding.requests.getValue(second.locator).resolution)
    }

    @Test
    fun `external resolution never guesses a decision`() {
        val request = request("host", 3, "request", "thread", "fingerprint")
        val resolved = CommandApprovalState()
            .receive(request, sameIdReissueQualified = false)
            .resolved("host", 3, "request", "thread")
            .requests
            .getValue(request.locator)

        assertEquals(RequestResolutionState.RESOLVED, resolved.resolution)
        assertEquals(true, resolved.resolvedElsewhere)
        assertNull(resolved.decision)
    }

    @Test
    fun `disconnect uncertainty is preserved and response is not replayed`() {
        val request = request("host", 4, "request", "thread", "fingerprint")
        val disconnected = CommandApprovalState()
            .receive(request, sameIdReissueQualified = false)
            .begin(request.locator, CommandApprovalDecision.CANCEL)
            .connectionLost("host", 4)
            .requests
            .getValue(request.locator)

        assertEquals(RequestResolutionState.UNKNOWN, disconnected.resolution)
        assertEquals(CommandApprovalDecision.CANCEL, disconnected.decision)
    }

    @Test
    fun `matching reissue requires qualification and scope fingerprint`() {
        val old = request("host", 1, "same-id", "thread", "same-scope")
        val reissued = request("host", 2, "same-id", "thread", "same-scope")
        val unqualified = CommandApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(reissued, sameIdReissueQualified = false)
        val qualified = CommandApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(reissued, sameIdReissueQualified = true)
        val changedScope = CommandApprovalState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .receive(reissued.copy(fingerprint = "different-scope"), sameIdReissueQualified = true)

        assertEquals(2, unqualified.requests.size)
        assertEquals(setOf(reissued.locator), qualified.requests.keys)
        assertEquals(RequestResolutionState.PENDING, qualified.requests.getValue(reissued.locator).resolution)
        assertEquals(2, changedScope.requests.size)
        assertFalse(qualified.requests.getValue(reissued.locator).decision != null)
    }

    @Test
    fun `turn settlement clears uncertain blocking request without guessing`() {
        val request = request("host", 1, "request", "thread", "fingerprint")
        val settled = CommandApprovalState()
            .receive(request, sameIdReissueQualified = false)
            .connectionLost("host", 1)
            .turnSettled(request.thread, request.turnId)
            .requests
            .getValue(request.locator)

        assertEquals(RequestResolutionState.RESOLVED, settled.resolution)
        assertEquals(true, settled.resolvedElsewhere)
        assertNull(settled.decision)
    }

    private fun request(
        hostId: String,
        generation: Long,
        requestId: String,
        threadId: String,
        fingerprint: String,
    ) = CommandApprovalRequest(
        locator = ServerRequestLocator(hostId, generation, requestId),
        thread = CodexThreadLocator(hostId, threadId),
        turnId = "turn",
        itemId = "item",
        approvalId = null,
        scope = CommandApprovalScope("pwd", "/work", null, null),
        proposedExecpolicyAmendment = null,
        offeredDecisions = setOf(
            CommandApprovalDecision.ACCEPT,
            CommandApprovalDecision.DECLINE,
            CommandApprovalDecision.CANCEL,
        ),
        fingerprint = fingerprint,
        createdAtMs = 1,
    )
}
