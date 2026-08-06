package com.code2hack.poker

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerNavigationEffect
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.protocol.PokerApprovalDecision
import com.code2hack.pokerdealer.protocol.PokerApprovalKind
import com.code2hack.pokerdealer.protocol.PokerApprovalRequestProjection
import com.code2hack.pokerdealer.protocol.PokerApprovalScopeProjection
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionOutcome
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionResult
import com.code2hack.pokerdealer.protocol.PokerPrimaryActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokerApprovalControllerTest {
    @Test
    fun `all dealer choices stay ordered and first primary remains locked until resolution`() {
        val fixture = fixture()
        val projection = projection(actionable = true)
        fixture.controller.applyProjection(projection)

        assertEquals(PokerNavigationEffect.ENTERED_REQUEST_PANEL, fixture.navigation.apply(PokerOperation.DOWN))
        assertEquals(2, fixture.navigation.layout(fixture.thread)?.cards?.single()?.requestPanel?.positionCount)
        assertEquals("decline", fixture.navigation.layout(fixture.thread)?.cards?.single()?.requestPanel?.controlAt(0)?.optionLabel)
        assertEquals(PokerApprovalDecision.DECLINE, fixture.controller.focusedSubmission()?.decision)

        val target = PokerPrimaryActionTarget(
            locator = fixture.thread,
            action = PokerPrimaryAction.REQUEST,
            wheelSession = "wheel",
            controlGeneration = projection.controlGeneration,
            connectionEpoch = projection.connectionEpoch,
            modeSession = projection.modeSession,
            requestLocator = projection.locator,
            approvalDecision = PokerApprovalDecision.DECLINE,
            requestFingerprint = projection.fingerprint,
            operationId = "primary",
        )
        assertTrue(fixture.controller.beginPrimary(target))
        assertFalse(fixture.controller.beginPrimary(target.copy(operationId = "duplicate")))
        fixture.controller.applyPrimaryResult(PokerPrimaryActionResult(target, PokerPrimaryActionOutcome.UNKNOWN))
        assertTrue(fixture.controller.isPrimaryLocked(projection.locator))

        fixture.controller.applyProjection(
            projection.copy(
                resolution = RequestResolutionState.RESOLVED,
                decision = PokerApprovalDecision.DECLINE,
            ),
        )
        assertFalse(fixture.controller.isPrimaryLocked(projection.locator))
        assertNull(fixture.navigation.layout(fixture.thread)?.cards?.single()?.requestPanel)
    }

    @Test
    fun `incomplete approval retains every choice but cannot become a submission`() {
        val fixture = fixture()
        fixture.controller.applyProjection(projection(actionable = false, complete = false))

        assertEquals(PokerNavigationEffect.ENTERED_REQUEST_PANEL, fixture.navigation.apply(PokerOperation.DOWN))
        assertEquals(2, fixture.navigation.layout(fixture.thread)?.cards?.single()?.requestPanel?.positionCount)
        assertNull(fixture.controller.focusedSubmission())
        assertTrue(fixture.navigation.layout(fixture.thread)?.cards?.single()?.requestPanel?.primaryActionLocked == true)
    }

    private data class Fixture(
        val thread: CodexThreadLocator,
        val navigation: PokerNavigationReducer,
        val controller: PokerApprovalController,
    )

    private fun fixture(): Fixture {
        val thread = CodexThreadLocator("spark", "thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        navigation.attach(
            locator = thread,
            evidence = ThreadWorkEvidence(activeTurn = true, unresolvedRequestCount = 1),
            atMs = 1,
            layout = PokerPileLayout(listOf(PokerCardLayout("item", collapsedLineCount = 1))),
        )
        navigation.view(thread)
        return Fixture(thread, navigation, PokerApprovalController(navigation))
    }

    private fun projection(
        actionable: Boolean,
        complete: Boolean = true,
    ) = PokerApprovalRequestProjection(
        locator = ServerRequestLocator("spark", 1, "approval"),
        thread = CodexThreadLocator("spark", "thread"),
        turnId = "turn",
        itemId = "item",
        cardId = "item",
        kind = PokerApprovalKind.COMMAND,
        scope = PokerApprovalScopeProjection(command = "echo hi", workingDirectory = "/work"),
        choices = listOf(PokerApprovalDecision.DECLINE, PokerApprovalDecision.ACCEPT),
        fingerprint = "fingerprint",
        complete = complete,
        actionable = actionable,
        resolution = RequestResolutionState.PENDING,
        controlGeneration = 1,
        connectionEpoch = 2,
        modeSession = "mode",
        hasDealerClaim = true,
    )
}
