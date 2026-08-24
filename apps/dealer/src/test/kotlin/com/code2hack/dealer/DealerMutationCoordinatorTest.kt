package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadAttachmentState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.AppServerNotification
import com.code2hack.pokerdealer.protocol.appserver.AppServerRequest
import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcPeer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerMutationCoordinatorTest {
    private val locator = CodexThreadLocator("host", "thread")

    @Test
    fun acceptedSendClearsPersistedLockOnlyAfterCodexAcceptance() = runBlocking {
        val peer = ScriptedPeer { method, _ ->
            when (method) {
                "initialize" -> JsonObject(emptyMap())
                "turn/start" -> buildJsonObject {
                    put("turn", buildJsonObject { put("id", JsonPrimitive("turn-1")) })
                }
                else -> error("Unexpected request $method")
            }
        }
        val session = CodexAppServerSession(peer).also { it.initialize() }
        val store = FakeThreadStateStore()
        val state = readyState("hello")
        val coordinator = DealerMutationCoordinator(
            state = state,
            store = store,
            appServer = { session },
            newClientId = { "client-1" },
        )

        val outcome = coordinator.submitDraft(locator)

        assertEquals(DealerMutationOutcome.ACCEPTED, outcome)
        assertEquals(1, peer.requestCount("turn/start"))
        assertFalse(locator in state.value.threadActions.pendingInputs)
        assertTrue(state.value.threadActions.composerDraft(locator).isEmpty)
        assertEquals(null, store.pendingInputs[locator])
        assertEquals("turn-1", state.value.threads[locator]?.activeTurnId)
        assertEquals(ThreadWorkState.BUSY, state.value.threads[locator]?.workState)
    }

    @Test
    fun uncertainSendRemainsFencedAndIsNeverBlindlyReplayed() = runBlocking {
        val peer = ScriptedPeer { method, _ ->
            when (method) {
                "initialize" -> JsonObject(emptyMap())
                "turn/start" -> error("connection lost after write")
                else -> error("Unexpected request $method")
            }
        }
        val session = CodexAppServerSession(peer).also { it.initialize() }
        val store = FakeThreadStateStore()
        val state = readyState("hello")
        val coordinator = DealerMutationCoordinator(
            state = state,
            store = store,
            appServer = { session },
            newClientId = { "client-1" },
        )

        assertEquals(DealerMutationOutcome.UNKNOWN, coordinator.submitDraft(locator))
        assertTrue(state.value.threadActions.pendingInputs.getValue(locator).uncertain)
        assertTrue(store.pendingInputs[locator] != null)
        assertEquals(1, peer.requestCount("turn/start"))

        assertEquals(DealerMutationOutcome.REJECTED, coordinator.submitDraft(locator))
        assertEquals(1, peer.requestCount("turn/start"))
        assertTrue(state.value.error?.contains("Reconcile the previous input") == true)
    }

    @Test
    fun staleSteerTargetIsRejectedBeforeAnyCodexMutation() = runBlocking {
        val peer = ScriptedPeer { method, _ ->
            if (method == "initialize") JsonObject(emptyMap()) else error("Unexpected request $method")
        }
        val session = CodexAppServerSession(peer).also { it.initialize() }
        val store = FakeThreadStateStore()
        val state = busyState("turn-current", "steer")
        val coordinator = DealerMutationCoordinator(
            state = state,
            store = store,
            appServer = { session },
            newClientId = { "client-steer" },
        )

        val outcome = coordinator.submitDraft(
            locator,
            expectedAction = ComposerAction.STEER,
            expectedTurnId = "turn-stale",
        )

        assertEquals(DealerMutationOutcome.REJECTED, outcome)
        assertEquals(0, peer.requestCount("turn/steer"))
        assertTrue(store.pendingInputs.isEmpty())
    }

    @Test
    fun uncertainInterruptStaysLockedAndIsNotReplayed() = runBlocking {
        val peer = ScriptedPeer { method, _ ->
            when (method) {
                "initialize" -> JsonObject(emptyMap())
                "turn/interrupt" -> error("connection lost after interrupt write")
                else -> error("Unexpected request $method")
            }
        }
        val session = CodexAppServerSession(peer).also { it.initialize() }
        val store = FakeThreadStateStore()
        val state = busyState("turn-1", draft = "")
        val coordinator = DealerMutationCoordinator(
            state = state,
            store = store,
            appServer = { session },
        )

        assertEquals(DealerMutationOutcome.UNKNOWN, coordinator.interrupt(locator, "turn-1"))
        assertEquals("turn-1", state.value.threadActions.pendingInterrupts[locator])
        assertEquals("turn-1", store.pendingInterrupts[locator])
        assertEquals(1, peer.requestCount("turn/interrupt"))

        assertEquals(DealerMutationOutcome.REJECTED, coordinator.interrupt(locator, "turn-1"))
        assertEquals(1, peer.requestCount("turn/interrupt"))
    }

    private fun readyState(draft: String) = kotlinx.coroutines.flow.MutableStateFlow(
        DealerCoreState(
            threads = mapOf(
                locator to DiscoveredThread(
                    locator = locator,
                    workState = ThreadWorkState.READY,
                    attached = true,
                    intendedControlSurface = ControlSurface.DEALER,
                ),
            ),
            threadAttachments = ThreadAttachmentState().attach(locator).claim(locator),
            threadActions = ThreadActionState().editDraft(locator, draft),
        ),
    )

    private fun busyState(turnId: String, draft: String) = kotlinx.coroutines.flow.MutableStateFlow(
        DealerCoreState(
            threads = mapOf(
                locator to DiscoveredThread(
                    locator = locator,
                    workState = ThreadWorkState.BUSY,
                    activeTurnId = turnId,
                    attached = true,
                    intendedControlSurface = ControlSurface.DEALER,
                ),
            ),
            threadAttachments = ThreadAttachmentState().attach(locator).claim(locator),
            threadActions = ThreadActionState().editDraft(locator, draft),
        ),
    )
}

private class FakeThreadStateStore : DealerThreadStateStore {
    val attachments = linkedSetOf<CodexThreadLocator>()
    var actions = ThreadActionState()
    val pendingInputs = mutableMapOf<CodexThreadLocator, PendingThreadInput?>()
    val pendingInterrupts = mutableMapOf<CodexThreadLocator, String?>()

    override suspend fun read(): Set<CodexThreadLocator> = attachments.toSet()
    override suspend fun attach(locator: CodexThreadLocator) {
        attachments += locator
    }
    override suspend fun detach(locator: CodexThreadLocator) {
        attachments -= locator
    }
    override suspend fun purge(locator: CodexThreadLocator) {
        attachments -= locator
        pendingInputs.remove(locator)
        pendingInterrupts.remove(locator)
        actions = actions.purge(setOf(locator))
    }
    override suspend fun readActions(): ThreadActionState = actions
    override suspend fun writeDraft(locator: CodexThreadLocator, draft: ComposerDraft) {
        actions = actions.editComposerDraft(locator, draft)
    }
    override suspend fun writeReasoningEffort(locator: CodexThreadLocator, effort: String?) {
        actions = actions.setPendingReasoningEffort(locator, effort)
    }
    override suspend fun writePendingInput(locator: CodexThreadLocator, pending: PendingThreadInput?) {
        pendingInputs[locator] = pending
    }
    override suspend fun writePendingInterrupt(locator: CodexThreadLocator, turnId: String?) {
        pendingInterrupts[locator] = turnId
    }
}

private class ScriptedPeer(
    private val response: suspend (String, JsonElement) -> JsonElement,
) : JsonRpcPeer {
    private val requests = mutableListOf<String>()

    fun requestCount(method: String): Int = requests.count { it == method }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        requests += method
        return response(method, params)
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = null
    override suspend fun receiveServerRequest(): AppServerRequest? = null
    override suspend fun close() = Unit
}
