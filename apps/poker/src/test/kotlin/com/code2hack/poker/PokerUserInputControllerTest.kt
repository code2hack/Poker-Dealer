package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.domain.UserInputAnswerBuffer
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationKind
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationRequest
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationResult
import com.code2hack.pokerdealer.protocol.UserInputAnswerMutationOutcome
import com.code2hack.pokerdealer.protocol.UserInputRequestProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerUserInputControllerTest {
    @Test
    fun `focused option edits the shared request and stale generation replaces the panel`() = runBlocking {
        val thread = CodexThreadLocator("spark", "thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = thread,
            evidence = ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("item", collapsedLineCount = 1))),
        )
        navigation.view(thread)
        var sent: UserInputAnswerMutationRequest? = null
        val controller = PokerUserInputController(navigation) {
            sent = it
            true
        }
        val request = request(ServerRequestLocator("spark", 1, "request"))
        val projection = UserInputRequestProjection(
            request = request,
            cardId = "item",
            controlGeneration = 4,
            connectionEpoch = 7,
            modeSession = "mode-1",
        )
        controller.applyProjection(projection)

        assertEquals(com.code2hack.pokerdealer.domain.PokerNavigationEffect.ENTERED_REQUEST_PANEL, navigation.apply(PokerOperation.DOWN))
        assertTrue(controller.selectFocused())
        assertEquals(UserInputAnswerMutationKind.SELECT_OPTION, sent?.kind)
        assertEquals("Spark", sent?.value)
        assertEquals("choice", sent?.target?.questionId)

        val result = UserInputAnswerMutationResult(
            target = checkNotNull(sent).target,
            outcome = UserInputAnswerMutationOutcome.ACKNOWLEDGED,
            buffer = UserInputAnswerBuffer().edit(
                request,
                "choice",
                com.code2hack.pokerdealer.domain.UserInputAnswerEdit.SelectOption("Spark"),
            ),
        )
        controller.applyResult(result)
        assertEquals("Spark", controller.projection(request.locator)?.buffer?.activeValue(request.questions[0]))

        val replacement = request.copy(locator = request.locator.copy(appServerGeneration = 2))
        controller.applyProjection(projection.copy(request = replacement, controlGeneration = 5))
        assertEquals(null, controller.projection(request.locator))
        assertEquals(5L, controller.projection(replacement.locator)?.controlGeneration)
    }

    private fun request(locator: ServerRequestLocator) = UserInputRequest(
        locator = locator,
        thread = CodexThreadLocator("spark", "thread"),
        turnId = "turn",
        itemId = "item",
        questions = listOf(
            UserInputQuestion(
                id = "choice",
                header = "Host",
                question = "Which host?",
                options = listOf(
                    com.code2hack.pokerdealer.domain.UserInputOption("Spark", "DGX"),
                    com.code2hack.pokerdealer.domain.UserInputOption("Fold6", "Phone"),
                ),
                isOther = false,
                isSecret = false,
            ),
        ),
        autoResolutionMs = null,
        receivedAtMs = 1,
        fingerprint = "fingerprint",
    )
}
