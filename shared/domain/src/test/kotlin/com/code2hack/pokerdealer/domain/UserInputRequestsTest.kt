package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UserInputRequestsTest {
    @Test
    fun `first response wins timer race and external resolution never guesses`() {
        val request = request()
        val responding = UserInputRequestState()
            .receive(request, sameIdReissueQualified = false)
            .begin(request.locator, UserInputOutcome.ANSWERED)
            .begin(request.locator, UserInputOutcome.AUTO_RESOLVED)
        val resolved = responding
            .resolved("spark", 1, "s:question", "thread")
            .requests
            .getValue(request.locator)

        assertEquals(UserInputOutcome.ANSWERED, resolved.outcome)
        assertEquals(RequestResolutionState.RESOLVED, resolved.resolution)

        val elsewhere = UserInputRequestState()
            .receive(request, sameIdReissueQualified = false)
            .resolved("spark", 1, "s:question", "thread")
            .requests
            .getValue(request.locator)
        assertEquals(true, elsewhere.resolvedElsewhere)
        assertNull(elsewhere.outcome)
    }

    @Test
    fun `disconnect is unknown and only qualified matching reissue replaces it`() {
        val old = request()
        val current = old.copy(
            locator = old.locator.copy(appServerGeneration = 2),
            receivedAtMs = 20,
        )
        val disconnected = UserInputRequestState()
            .receive(old, sameIdReissueQualified = false)
            .begin(old.locator, UserInputOutcome.NO_ANSWER)
            .connectionLost("spark", 1)
        val unqualified = disconnected.receive(current, sameIdReissueQualified = false)
        val qualified = disconnected.receive(current, sameIdReissueQualified = true)

        assertEquals(RequestResolutionState.UNKNOWN, disconnected.requests.getValue(old.locator).resolution)
        assertEquals(2, unqualified.requests.size)
        assertEquals(setOf(current.locator), qualified.requests.keys)
        assertEquals(RequestResolutionState.PENDING, qualified.requests.getValue(current.locator).resolution)
    }

    private fun request() = UserInputRequest(
        locator = ServerRequestLocator("spark", 1, "s:question"),
        thread = CodexThreadLocator("spark", "thread"),
        turnId = "turn",
        itemId = "item",
        questions = listOf(
            UserInputQuestion("answer", "Answer", "Answer?", null, false, true),
        ),
        autoResolutionMs = 100,
        receivedAtMs = 10,
        fingerprint = "fingerprint",
    )
}
