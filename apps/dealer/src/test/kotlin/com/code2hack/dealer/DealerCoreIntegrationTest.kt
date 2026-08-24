package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ControlSurface
import com.code2hack.pokerdealer.domain.DiscoveredThread
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.AppServerNotification
import com.code2hack.pokerdealer.protocol.appserver.AppServerRequest
import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.HostConnectionIntentStore
import com.code2hack.pokerdealer.protocol.appserver.HostSession
import com.code2hack.pokerdealer.protocol.appserver.HostSessionBackoff
import com.code2hack.pokerdealer.protocol.appserver.HostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcPeer
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DealerCoreIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val locator = CodexThreadLocator("host", "thread")

    @Test
    fun dealerCodexReconnectReconcilesUnknownSendWithoutPokerAndWithoutReplay() = runBlocking {
        val clientId = AtomicReference<String>()
        val turnStarts = AtomicInteger()
        val firstPeer = CorePeer { method, params ->
            when (method) {
                "initialize" -> JsonObject(emptyMap())
                "thread/loaded/list" -> buildJsonObject { put("data", JsonArray(emptyList())) }
                "thread/list" -> discoveryPage()
                "thread/resume" -> threadRead()
                "thread/read" -> threadRead()
                "turn/start" -> {
                    turnStarts.incrementAndGet()
                    clientId.set((params as JsonObject).getValue("clientUserMessageId").let { (it as JsonPrimitive).content })
                    error("connection lost after turn/start write")
                }
                else -> error("Unexpected request on first connection: $method")
            }
        }
        val secondPeer = CorePeer { method, _ ->
            when (method) {
                "initialize" -> JsonObject(emptyMap())
                "thread/loaded/list" -> buildJsonObject { put("data", JsonArray(emptyList())) }
                "thread/list" -> discoveryPage()
                "thread/resume" -> threadRead(clientId.get())
                "thread/read" -> threadRead(clientId.get())
                else -> error("Unexpected request on replacement connection: $method")
            }
        }
        val connector = SequencedConnector(listOf(firstPeer, secondPeer))
        val intent = MemoryIntentStore(setOf("host"))
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostSessions = HostSessionManager(
            hostIds = setOf("host"),
            intentStore = intent,
            connector = connector,
            scope = managerScope,
            backoff = HostSessionBackoff(initialMs = 1, maxMs = 1),
        )
        val threadStore = MemoryThreadStore(locator)
        val recovery = MemoryRecoveryStore(
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
            hostSessions = hostSessions,
            threadStore = threadStore,
            recoveryStore = recovery,
            retainedCardStore = RetainedCardStore(temporaryFolder.newFolder("cards")),
            scope = coreScope,
        )

        try {
            core.start()
            awaitCondition { connector.connectCount.get() >= 1 && core.state.value.threads[locator] != null }
            assertTrue(core.takeControl(locator))
            assertTrue(core.editDraft(locator, ComposerDraft.fromText("hello")))

            val outcome = core.submitDraft(locator)

            assertEquals(DealerMutationOutcome.UNKNOWN, outcome)
            assertEquals(1, turnStarts.get())
            assertNotNull(clientId.get())

            awaitCondition {
                connector.connectCount.get() >= 2 &&
                    core.state.value.threadActions.pendingInputs[locator] == null
            }

            assertEquals(1, turnStarts.get())
            assertFalse(locator in core.state.value.threadActions.pendingInputs)
            assertEquals(ThreadWorkState.READY, core.state.value.threads[locator]?.workState)
            assertTrue(threadStore.pendingInputs[locator] == null)
        } finally {
            core.close()
            coreScope.cancel()
            managerScope.cancel()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun discoveryPage(): JsonObject = buildJsonObject {
        put(
            "data",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(locator.threadId))
                        put("source", JsonPrimitive("appServer"))
                        put("name", JsonPrimitive("Thread"))
                        put("cwd", JsonPrimitive("/work"))
                        put("status", JsonPrimitive("idle"))
                    },
                )
            },
        )
    }

    private fun threadRead(deliveredClientId: String? = null): JsonObject = buildJsonObject {
        put(
            "thread",
            buildJsonObject {
                put("id", JsonPrimitive(locator.threadId))
                put("status", JsonPrimitive("idle"))
                put(
                    "turns",
                    if (deliveredClientId == null) {
                        JsonArray(emptyList())
                    } else {
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive("turn-accepted"))
                                    put("status", JsonPrimitive("completed"))
                                    put("startedAt", JsonPrimitive(1))
                                    put(
                                        "items",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", JsonPrimitive("user-item"))
                                                    put("type", JsonPrimitive("userMessage"))
                                                    put("clientId", JsonPrimitive(deliveredClientId))
                                                    put(
                                                        "content",
                                                        buildJsonArray {
                                                            add(buildJsonObject { put("text", JsonPrimitive("hello")) })
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

private class MemoryIntentStore(initial: Set<String>) : HostConnectionIntentStore {
    private var enabled = initial
    override suspend fun readEnabledHostIds(): Set<String> = enabled
    override suspend fun writeEnabledHostIds(hostIds: Set<String>) {
        enabled = hostIds
    }
}

private class SequencedConnector(
    private val peers: List<CorePeer>,
) : HostSessionConnector {
    val connectCount = AtomicInteger()

    override suspend fun connect(hostId: String): HostSession {
        val index = connectCount.getAndIncrement()
        val peer = peers.getOrElse(index) { peers.last() }
        val appServer = CodexAppServerSession(peer)
        appServer.initialize()
        return CoreHostSession(appServer)
    }
}

private class CoreHostSession(
    override val appServer: CodexAppServerSession,
) : HostSession {
    override val route: HostConnectionRoute? = HostConnectionRoute.SSH_LAN
    override val diagnostics: List<RouteDiagnostic> = emptyList()
    override suspend fun awaitDisconnect(): Nothing = appServer.awaitClose()
    override suspend fun close() = appServer.close()
}

private class CorePeer(
    private val response: suspend (String, JsonElement) -> JsonElement,
) : JsonRpcPeer {
    private val closed = CompletableDeferred<Throwable>()

    override suspend fun request(method: String, params: JsonElement): JsonElement = response(method, params)
    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? {
        closed.await()
        return null
    }
    override suspend fun receiveServerRequest(): AppServerRequest? {
        closed.await()
        return null
    }
    override suspend fun awaitClose(): Nothing = throw closed.await()
    override suspend fun close() {
        closed.complete(IllegalStateException("closed"))
    }
}

private class MemoryThreadStore(initialAttachment: CodexThreadLocator) : DealerThreadStateStore {
    private val attachments = linkedSetOf(initialAttachment)
    private var actions = ThreadActionState()
    val pendingInputs = mutableMapOf<CodexThreadLocator, PendingThreadInput?>()
    private val pendingInterrupts = mutableMapOf<CodexThreadLocator, String?>()

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

private class MemoryRecoveryStore(
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
