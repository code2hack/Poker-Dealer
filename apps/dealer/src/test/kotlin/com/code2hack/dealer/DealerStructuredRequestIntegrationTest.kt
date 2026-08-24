package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.RequestResolutionState
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.domain.UserInputOutcome
import com.code2hack.pokerdealer.protocol.appserver.AppServerNotification
import com.code2hack.pokerdealer.protocol.appserver.AppServerRequest
import com.code2hack.pokerdealer.protocol.appserver.COMMAND_APPROVAL_METHOD
import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.HostConnectionIntentStore
import com.code2hack.pokerdealer.protocol.appserver.HostSession
import com.code2hack.pokerdealer.protocol.appserver.HostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcPeer
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import com.code2hack.pokerdealer.protocol.appserver.USER_INPUT_REQUEST_METHOD
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class DealerStructuredRequestIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val locator = CodexThreadLocator("host", "thread")

    @Test
    fun commandApprovalRemainsStructuredAndIsResolvedOnExactGeneration() = runBlocking {
        val peer = SingleRequestPeer(commandRequest())
        val harness = requestHarness(peer)
        try {
            harness.core.start()
            awaitCondition { harness.core.state.value.commandApprovals.requests.isNotEmpty() }
            assertTrue(harness.core.takeControl(locator))
            val request = harness.core.state.value.commandApprovals.requests.values.single()

            val outcome = harness.core.resolveCommandApproval(
                request.locator,
                CommandApprovalDecision.ACCEPT,
            )

            assertEquals(DealerMutationOutcome.ACCEPTED, outcome)
            assertEquals(1L, request.locator.appServerGeneration)
            assertEquals("pwd", request.scope.command)
            assertEquals("/work", request.scope.workingDirectory)
            assertEquals("s:command-1", request.locator.requestId)
            assertEquals(listOf("command-1"), peer.respondedRequestIds)
            assertEquals("accept", peer.responseDecision)
        } finally {
            harness.close()
        }
    }

    @Test
    fun structuredUserInputIsAnsweredWithoutFlatteningQuestionsToText() = runBlocking {
        val peer = SingleRequestPeer(userInputRequest())
        val harness = requestHarness(peer)
        try {
            harness.core.start()
            awaitCondition { harness.core.state.value.userInputRequests.requests.isNotEmpty() }
            assertTrue(harness.core.takeControl(locator))
            val request = harness.core.state.value.userInputRequests.requests.values.single()
            assertEquals("Host", request.questions.single().header)
            assertEquals(2, request.questions.single().options?.size)

            val outcome = harness.core.resolveUserInput(
                request.locator,
                mapOf("host" to listOf("Codex host A")),
                UserInputOutcome.ANSWERED,
            )

            assertEquals(DealerMutationOutcome.ACCEPTED, outcome)
            assertEquals("s:question-1", request.locator.requestId)
            assertEquals(listOf("question-1"), peer.respondedRequestIds)
            assertEquals("Codex host A", peer.responseAnswer)
            assertEquals(RequestResolutionState.RESPONDING, harness.core.state.value.userInputRequests.requests[request.locator]?.resolution)
        } finally {
            harness.close()
        }
    }

    private fun requestHarness(peer: SingleRequestPeer): RequestHarness {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = CodexAppServerSession(peer)
        val manager = HostSessionManager(
            hostIds = setOf("host"),
            intentStore = RequestIntentStore(setOf("host")),
            connector = HostSessionConnector {
                session.initialize()
                RequestHostSession(session)
            },
            scope = managerScope,
        )
        val threadStore = RequestThreadStore(locator)
        val recovery = RequestRecoveryStore(
            DealerProjectionSnapshot(
                listOf(
                    DiscoveredThread(
                        locator = locator,
                        name = "Thread",
                        workState = ThreadWorkState.READY,
                        attached = true,
                        intendedControlSurface = ControlSurface.NONE,
                    ),
                ),
            ),
        )
        val core = DealerCore(
            hostSessions = manager,
            threadStore = threadStore,
            recoveryStore = recovery,
            retainedCardStore = RetainedCardStore(temporaryFolder.newFolder()),
            scope = coreScope,
        )
        return RequestHarness(core, managerScope, coreScope)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun commandRequest() = AppServerRequest(
        id = JsonPrimitive("command-1"),
        method = COMMAND_APPROVAL_METHOD,
        params = buildJsonObject {
            put("threadId", JsonPrimitive(locator.threadId))
            put("turnId", JsonPrimitive("turn-1"))
            put("itemId", JsonPrimitive("item-1"))
            put("startedAtMs", JsonPrimitive(1))
            put("command", JsonPrimitive("pwd"))
            put("cwd", JsonPrimitive("/work"))
            put(
                "availableDecisions",
                buildJsonArray {
                    add(JsonPrimitive("accept"))
                    add(JsonPrimitive("decline"))
                },
            )
        },
    )

    private fun userInputRequest() = AppServerRequest(
        id = JsonPrimitive("question-1"),
        method = USER_INPUT_REQUEST_METHOD,
        params = buildJsonObject {
            put("threadId", JsonPrimitive(locator.threadId))
            put("turnId", JsonPrimitive("turn-1"))
            put("itemId", JsonPrimitive("item-1"))
            put(
                "questions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("host"))
                            put("header", JsonPrimitive("Host"))
                            put("question", JsonPrimitive("Which host?"))
                            put("isOther", JsonPrimitive(true))
                            put("isSecret", JsonPrimitive(false))
                            put(
                                "options",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("label", JsonPrimitive("Codex host A"))
                                            put("description", JsonPrimitive("First host"))
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("label", JsonPrimitive("Codex host B"))
                                            put("description", JsonPrimitive("Second host"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )
}

private class RequestHarness(
    val core: DealerCore,
    private val managerScope: CoroutineScope,
    private val coreScope: CoroutineScope,
) {
    suspend fun close() {
        core.close()
        managerScope.cancel()
        coreScope.cancel()
    }
}

private class SingleRequestPeer(
    private val wire: AppServerRequest,
) : JsonRpcPeer {
    private val delivered = AtomicBoolean()
    val respondedRequestIds = mutableListOf<String>()
    var responseDecision: String? = null
    var responseAnswer: String? = null

    override suspend fun request(method: String, params: JsonElement): JsonElement = when (method) {
        "initialize" -> JsonObject(emptyMap())
        "thread/loaded/list" -> buildJsonObject { put("data", JsonArray(emptyList())) }
        "thread/list" -> buildJsonObject {
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("thread"))
                            put("source", JsonPrimitive("appServer"))
                            put("name", JsonPrimitive("Thread"))
                            put("cwd", JsonPrimitive("/work"))
                            put("status", JsonPrimitive("idle"))
                        },
                    )
                },
            )
        }
        "thread/resume", "thread/read" -> buildJsonObject {
            put(
                "thread",
                buildJsonObject {
                    put("id", JsonPrimitive("thread"))
                    put("status", JsonPrimitive("idle"))
                    put("turns", JsonArray(emptyList()))
                },
            )
        }
        else -> error("Unexpected request $method")
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = awaitCancellation()
    override suspend fun receiveServerRequest(): AppServerRequest? {
        if (delivered.compareAndSet(false, true)) return wire
        return awaitCancellation()
    }

    override suspend fun respond(request: AppServerRequest, result: JsonElement) {
        respondedRequestIds += request.id.let { (it as JsonPrimitive).content }
        val response = result as JsonObject
        responseDecision = (response["decision"] as? JsonPrimitive)?.content
        responseAnswer = ((response["answers"] as? JsonObject)
            ?.get("host") as? JsonObject)
            ?.get("answers")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { (it as JsonPrimitive).content }
    }

    override suspend fun reject(request: AppServerRequest, message: String) {
        error("Request unexpectedly rejected: $message")
    }

    override suspend fun awaitClose(): Nothing = awaitCancellation()
    override suspend fun close() = Unit
}

private class RequestHostSession(
    override val appServer: CodexAppServerSession,
) : HostSession {
    override val route: HostConnectionRoute? = HostConnectionRoute.SSH_LAN
    override val diagnostics: List<RouteDiagnostic> = emptyList()
    override suspend fun awaitDisconnect(): Nothing = appServer.awaitClose()
    override suspend fun close() = appServer.close()
}

private class RequestIntentStore(initial: Set<String>) : HostConnectionIntentStore {
    private var enabled = initial
    override suspend fun readEnabledHostIds(): Set<String> = enabled
    override suspend fun writeEnabledHostIds(hostIds: Set<String>) {
        enabled = hostIds
    }
}

private class RequestThreadStore(initialAttachment: CodexThreadLocator) : DealerThreadStateStore {
    private val attachments = linkedSetOf(initialAttachment)
    private var actions = ThreadActionState()

    override suspend fun read(): Set<CodexThreadLocator> = attachments.toSet()
    override suspend fun attach(locator: CodexThreadLocator) {
        attachments += locator
    }
    override suspend fun detach(locator: CodexThreadLocator) {
        attachments -= locator
    }
    override suspend fun purge(locator: CodexThreadLocator) {
        attachments -= locator
        actions = actions.purge(setOf(locator))
    }
    override suspend fun readActions(): ThreadActionState = actions
    override suspend fun writeDraft(locator: CodexThreadLocator, draft: ComposerDraft) {
        actions = actions.editComposerDraft(locator, draft)
    }
    override suspend fun writeReasoningEffort(locator: CodexThreadLocator, effort: String?) {
        actions = actions.setPendingReasoningEffort(locator, effort)
    }
    override suspend fun writePendingInput(locator: CodexThreadLocator, pending: PendingThreadInput?) = Unit
    override suspend fun writePendingInterrupt(locator: CodexThreadLocator, turnId: String?) = Unit
}

private class RequestRecoveryStore(
    projection: DealerProjectionSnapshot,
) : DealerRecoveryStateStore {
    private var restored = RestoredDealerState(
        projection = projection,
        pendingRequests = DealerPendingRequestSnapshot(),
        pendingRequestsWritable = true,
        errors = emptyList(),
    )

    override suspend fun read(): RestoredDealerState = restored
    override suspend fun writeProjection(snapshot: DealerProjectionSnapshot) {
        restored = restored.copy(projection = snapshot)
    }
    override suspend fun writePendingRequests(snapshot: DealerPendingRequestSnapshot) {
        restored = restored.copy(pendingRequests = snapshot)
    }
}
