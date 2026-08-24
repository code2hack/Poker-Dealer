package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.PendingThreadInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadAttachmentStoreTest {
    @Test
    fun attachmentsSurviveStoreRecreationWithoutControlClaims() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locators = setOf(
            CodexThreadLocator("spark", "same"),
            CodexThreadLocator("u4090", "same"),
        )

        val store = DealerThreadAttachmentStore(context)
        locators.forEach { store.detach(it) }
        locators.forEach { store.attach(it) }

        assertEquals(locators, DealerThreadAttachmentStore(context).read().intersect(locators))
    }

    @Test
    fun draftsSurviveRecreationAndStayHostQualified() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val u4090 = CodexThreadLocator("u4090", "same-draft")
        val spark = CodexThreadLocator("spark", "same-draft")
        val store = DealerThreadAttachmentStore(context)
        store.writeDraft(u4090, "u4090 draft")
        store.writeDraft(spark, "spark draft")

        val restored = DealerThreadAttachmentStore(context).readDrafts()

        assertEquals("u4090 draft", restored[u4090])
        assertEquals("spark draft", restored[spark])
    }

    @Test
    fun uncertainInputAndInterruptLocksSurviveRecreationWithoutReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = CodexThreadLocator("u4090", "pending-input")
        val interrupt = CodexThreadLocator("spark", "pending-interrupt")
        val store = DealerThreadAttachmentStore(context)
        store.writePendingInput(
            input,
            PendingThreadInput("client", ComposerAction.STEER, "turn-1", "draft"),
        )
        store.writePendingInterrupt(interrupt, "turn-2")

        val restored = DealerThreadAttachmentStore(context).readActions()

        assertEquals(true, restored.pendingInputs.getValue(input).uncertain)
        assertEquals("turn-1", restored.pendingInputs.getValue(input).expectedTurnId)
        assertEquals("turn-2", restored.pendingInterrupts[interrupt])
    }

    @Test
    fun orderedDraftAndPendingInputSurviveRecreationWithoutEmojiLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locator = CodexThreadLocator("spark", "ordered-draft")
        val draft = ComposerDraft(
            revision = 4,
            elements = listOf(
                ComposerElement.Text("before"),
                ComposerElement.Photo("asset-1"),
                ComposerElement.Text("after"),
            ),
        )
        val store = DealerThreadAttachmentStore(context)
        store.writeDraft(locator, draft)
        store.writePendingInput(
            locator,
            PendingThreadInput(
                clientId = "client",
                action = ComposerAction.START,
                expectedTurnId = null,
                draftText = draft.displayText,
                draft = draft,
            ),
        )

        val restored = DealerThreadAttachmentStore(context)
        assertEquals(draft, restored.readComposerDrafts()[locator])
        assertEquals(draft, restored.readActions().pendingInputs.getValue(locator).draft)
        assertEquals("before📷after", restored.readDrafts()[locator])
    }

    @Test
    fun nextTurnReasoningEffortSurvivesRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locator = CodexThreadLocator("u4090", "reasoning")
        DealerThreadAttachmentStore(context).writeReasoningEffort(locator, "high")

        val restored = DealerThreadAttachmentStore(context).readActions()

        assertEquals("high", restored.pendingReasoningEfforts[locator])
    }

    @Test
    fun permanentDeletePurgesOnlyItsAttachmentDraftAndPendingAction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deleted = CodexThreadLocator("u4090", "purged")
        val retained = CodexThreadLocator("spark", "retained")
        val store = DealerThreadAttachmentStore(context)
        listOf(deleted, retained).forEach {
            store.attach(it)
            store.writeDraft(it, it.hostId)
            store.writePendingInterrupt(it, "turn")
        }

        store.purge(deleted)
        val actions = store.readActions()

        assertEquals(setOf(retained), store.read().intersect(setOf(deleted, retained)))
        assertEquals(mapOf(retained to "spark"), actions.drafts.filterKeys { it in setOf(deleted, retained) })
        assertEquals(mapOf(retained to "turn"), actions.pendingInterrupts.filterKeys { it in setOf(deleted, retained) })
    }
}
