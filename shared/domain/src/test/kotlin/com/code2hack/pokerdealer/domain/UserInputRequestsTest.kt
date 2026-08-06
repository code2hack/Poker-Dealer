package com.code2hack.pokerdealer.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `qualified reissue preserves the earliest auto-resolution deadline`() {
        val old = request().copy(receivedAtMs = 10)
        val current = old.copy(
            locator = old.locator.copy(appServerGeneration = 2),
            receivedAtMs = 20,
        )

        val rebound = UserInputRequestState()
            .receive(old, sameIdReissueQualified = false)
            .connectionLost("spark", 1)
            .receive(current, sameIdReissueQualified = true)
            .requests
            .getValue(current.locator)

        assertEquals(10L, rebound.receivedAtMs)
        assertEquals(110L, rebound.deadlineAtMs)
    }

    @Test
    fun `answer buffer preserves Other text while switching named option`() {
        val request = requestWithOptions()
        val buffer = UserInputAnswerBuffer()
            .edit(request, "target", UserInputAnswerEdit.SelectOther)
            .edit(request, "target", UserInputAnswerEdit.SetText("another host"))
            .edit(request, "target", UserInputAnswerEdit.SelectOption("Spark"))

        assertEquals("Spark", buffer.activeValue(request.questions.single()))
        assertEquals("another host", buffer.answer("target").otherText)

        val other = buffer.edit(request, "target", UserInputAnswerEdit.SelectOther)
        assertEquals("another host", other.activeValue(request.questions.single()))
        assertTrue(other.isComplete(request))
    }

    @Test
    fun `qualified request reissue carries only the live process buffer`() {
        val old = requestWithOptions()
        val current = old.copy(locator = old.locator.copy(appServerGeneration = 2))
        val requests = UserInputRequestState().receive(old, sameIdReissueQualified = false)
        val answers = UserInputAnswerState()
            .receive(UserInputRequestState(), old, sameIdReissueQualified = false)
            .edit(old, "target", UserInputAnswerEdit.SelectOption("Fold6"))
        val rebound = answers.receive(requests, current, sameIdReissueQualified = true)

        assertEquals("Fold6", rebound.buffer(current.locator).activeValue(current.questions.single()))
        assertEquals(UserInputAnswerBuffer(), UserInputAnswerState().receive(requests, current, true).buffer(current.locator))
    }

    @Test
    fun `secret buffer has a redacted diagnostic representation and purges explicitly`() {
        val request = request().copy(
            questions = listOf(request().questions.single().copy(isSecret = true)),
        )
        val buffer = UserInputAnswerBuffer()
            .edit(request, "answer", UserInputAnswerEdit.SetText("secret-value"))

        assertTrue("secret-value" !in buffer.toString())
        assertTrue(buffer.clearSecretAnswers(request).answers.isEmpty())
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

    private fun requestWithOptions() = request().copy(
        questions = listOf(
            UserInputQuestion(
                id = "target",
                header = "Target",
                question = "Where?",
                options = listOf(
                    UserInputOption("Spark", "workstation"),
                    UserInputOption("Fold6", "phone"),
                ),
                isOther = true,
                isSecret = false,
            ),
        ),
    )
}
