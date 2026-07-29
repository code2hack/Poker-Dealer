package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.host.CommandResult
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.HostIdentityException
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteConnectionException
import com.code2hack.pokerdealer.protocol.host.RouteDiagnostic
import com.code2hack.pokerdealer.protocol.host.withConnectionPhaseTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException

data class DaemonVersions(
    val status: String?,
    val cliVersion: String?,
    val appServerVersion: String?,
    val managedCodexVersion: String?,
    val socketPath: String?,
    val raw: JsonObject,
)

interface CodexDaemonLifecycle {
    val appServerProxyCommand: String
    suspend fun ensureRunning(ssh: HostSshSession): DaemonVersions
}

abstract class JsonCodexDaemonLifecycle(
    codexExecutable: String,
) : CodexDaemonLifecycle {
    init {
        require(codexExecutable.isNotBlank()) { "Codex executable is required" }
    }

    val daemonVersionCommand = "$codexExecutable app-server daemon version"
    val daemonStartCommand = "$codexExecutable app-server daemon start"
    final override val appServerProxyCommand = "$codexExecutable app-server proxy"

    final override suspend fun ensureRunning(ssh: HostSshSession): DaemonVersions {
        val current = ssh.exec(daemonVersionCommand)
        if (current.exitCode == 0) {
            val versions = parseVersions(current)
            if (isRunning(versions)) return validate(versions)
        }
        val started = ssh.exec(daemonStartCommand)
        require(started.exitCode == 0) { "Failed to start app-server daemon: ${started.stderr.ifBlank { started.stdout }}" }
        validateStart(started)
        val version = ssh.exec(daemonVersionCommand)
        require(version.exitCode == 0) {
            "Failed to query app-server daemon after start: ${version.stderr.ifBlank { version.stdout }}"
        }
        return validate(parseVersions(version))
    }

    fun parseVersions(result: CommandResult): DaemonVersions {
        val raw = AppServerJson.parseToJsonElement(result.stdout.trim()).jsonObject
        return DaemonVersions(
            status = (raw["status"] as? JsonPrimitive)?.contentOrNull,
            cliVersion = raw.findString("cliVersion", "cli_version", "codexVersion", "codex_version"),
            appServerVersion = raw.findString("appServerVersion", "app_server_version", "serverVersion", "server_version"),
            managedCodexVersion = raw.findString("managedCodexVersion", "managed_codex_version"),
            socketPath = raw.findString("socketPath", "socket_path", "controlSocket", "control_socket"),
            raw = raw,
        )
    }

    protected abstract fun isRunning(versions: DaemonVersions): Boolean
    protected abstract fun validate(versions: DaemonVersions): DaemonVersions
    protected open fun validateStart(result: CommandResult) = Unit

    private fun JsonElement.findString(vararg keys: String): String? = when (this) {
        is JsonObject -> {
            keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
                ?: values.firstNotNullOfOrNull { it.findString(*keys) }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findString(*keys) }
        else -> null
    }
}

class UpstreamCodexDaemon(
    codexExecutable: String = DEFAULT_CODEX_EXECUTABLE,
) : JsonCodexDaemonLifecycle(codexExecutable) {
    override fun isRunning(versions: DaemonVersions): Boolean =
        versions.status == null || versions.status == "running"

    override fun validate(versions: DaemonVersions): DaemonVersions {
        require(isRunning(versions)) {
            "App-server daemon did not reach running state: ${versions.status}"
        }
        return versions
    }

    companion object {
        const val DEFAULT_CODEX_EXECUTABLE = "~/.local/bin/codex"
    }
}

class TermuxCommunityCodexDaemon(
    codexExecutable: String = DEFAULT_CODEX_EXECUTABLE,
) : JsonCodexDaemonLifecycle(codexExecutable) {
    override fun isRunning(versions: DaemonVersions): Boolean = versions.status == "running"

    override fun validate(versions: DaemonVersions): DaemonVersions {
        require(isRunning(versions)) {
            "Termux app-server daemon did not reach running state: ${versions.status}"
        }
        require(!versions.socketPath.isNullOrBlank()) {
            "Termux app-server daemon did not report a bound control socket"
        }
        return versions
    }

    override fun validateStart(result: CommandResult) {
        val started = parseVersions(result)
        require(started.status == "started") {
            "Termux app-server daemon start returned status ${started.status}"
        }
        require(!started.socketPath.isNullOrBlank()) {
            "Termux app-server daemon start did not report a bound control socket"
        }
    }

    companion object {
        const val DEFAULT_CODEX_EXECUTABLE = "codex"
    }
}

class CodexAppServerSession(
    private val peer: JsonRpcPeer,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMs: Long = 30_000,
    private val turnInactivityTimeoutMs: Long = 5 * 60_000,
) {
    private var initialized = false

    init {
        require(requestTimeoutMs > 0) { "App-server request timeout must be positive" }
        require(turnInactivityTimeoutMs > 0) { "Turn inactivity timeout must be positive" }
    }

    suspend fun initialize(): JsonObject {
        check(!initialized) { "app-server connection is already initialized" }
        val result = request(
            "initialize",
            buildJsonObject {
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive("poker-dealer"))
                        put("title", JsonPrimitive("Poker-Dealer"))
                        put("version", JsonPrimitive("0.1.0-m1"))
                    },
                )
                put("capabilities", buildJsonObject { put("experimentalApi", JsonPrimitive(false)) })
            },
        ).jsonObject
        notify("initialized")
        initialized = true
        return result
    }

    suspend fun awaitClose(): Nothing = peer.awaitClose()

    suspend fun threadList(limit: Int = 20): JsonObject {
        checkInitialized()
        return request(
            "thread/list",
            buildJsonObject {
                put("limit", JsonPrimitive(limit))
                put("archived", JsonPrimitive(false))
            },
        ).jsonObject
    }

    suspend fun threadDiscoveryList(
        archived: Boolean,
        cursor: String? = null,
        limit: Int = 100,
    ): JsonObject {
        checkInitialized()
        return request(
            "thread/list",
            buildJsonObject {
                put("limit", JsonPrimitive(limit))
                put("archived", JsonPrimitive(archived))
                cursor?.let { put("cursor", JsonPrimitive(it)) }
                put(
                    "sourceKinds",
                    buildJsonArray {
                        add(JsonPrimitive("cli"))
                        add(JsonPrimitive("vscode"))
                        add(JsonPrimitive("appServer"))
                    },
                )
            },
        ).jsonObject
    }

    suspend fun threadLoadedListOrNull(): JsonObject? {
        checkInitialized()
        return try {
            request("thread/loaded/list", JsonObject(emptyMap()), closeOnFailure = false).jsonObject
        } catch (failure: JsonRpcRemoteException) {
            if (failure.code == -32601) null else closeAfterFailure(failure)
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
        }
    }

    suspend fun threadResume(threadId: String): JsonObject {
        checkInitialized()
        return request(
            "thread/resume",
            buildJsonObject { put("threadId", JsonPrimitive(threadId)) },
        ).jsonObject
    }

    suspend fun threadUnsubscribe(threadId: String): JsonObject {
        checkInitialized()
        return request(
            "thread/unsubscribe",
            buildJsonObject { put("threadId", JsonPrimitive(threadId)) },
        ).jsonObject
    }

    suspend fun threadRead(threadId: String): JsonObject {
        checkInitialized()
        return request(
            "thread/read",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("includeTurns", JsonPrimitive(true))
            },
        ).jsonObject
    }

    suspend fun configRead(workingDirectory: String): JsonObject {
        checkInitialized()
        return actionRequest(
            "config/read",
            buildJsonObject { put("cwd", JsonPrimitive(workingDirectory)) },
        ).jsonObject
    }

    suspend fun configRequirementsRead(): JsonObject {
        checkInitialized()
        return actionRequest("configRequirements/read", JsonNull).jsonObject
    }

    suspend fun modelList(cursor: String? = null): JsonObject {
        checkInitialized()
        return actionRequest(
            "model/list",
            buildJsonObject { cursor?.let { put("cursor", JsonPrimitive(it)) } },
        ).jsonObject
    }

    suspend fun threadStart(selection: ThreadStartSelection): JsonObject =
        threadWithReviewedSettings("thread/start", null, selection)

    suspend fun threadFork(threadId: String, selection: ThreadStartSelection): JsonObject =
        threadWithReviewedSettings("thread/fork", threadId, selection)

    suspend fun threadNameSet(threadId: String, name: String) {
        checkInitialized()
        actionRequest(
            "thread/name/set",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("name", JsonPrimitive(name))
            },
        )
    }

    private suspend fun threadWithReviewedSettings(
        method: String,
        threadId: String?,
        selection: ThreadStartSelection,
    ): JsonObject {
        checkInitialized()
        val response = actionRequest(
            method,
            buildJsonObject {
                threadId?.let { put("threadId", JsonPrimitive(it)) }
                put("cwd", JsonPrimitive(selection.workingDirectory))
                selection.providerOverride?.let { put("modelProvider", JsonPrimitive(it)) }
                selection.modelOverride?.let { put("model", JsonPrimitive(it)) }
                selection.permissionPreset.sandbox?.let { put("sandbox", JsonPrimitive(it)) }
                selection.permissionPreset.approvalPolicy?.let {
                    put("approvalPolicy", JsonPrimitive(it))
                }
                selection.permissionPreset.approvalsReviewer?.let {
                    put("approvalsReviewer", JsonPrimitive(it))
                }
            },
        ).jsonObject
        require(response.string("cwd") == selection.workingDirectory) {
            "$method did not apply the selected working directory"
        }
        selection.providerOverride?.let {
            require(response.string("modelProvider") == it) {
                "$method did not apply the selected provider"
            }
        }
        selection.modelOverride?.let {
            require(response.string("model") == it) {
                "$method did not apply the selected model"
            }
        }
        selection.permissionPreset.approvalPolicy?.let {
            require(response.string("approvalPolicy") == it) {
                "$method did not apply the selected approval policy"
            }
        }
        selection.permissionPreset.approvalsReviewer?.let {
            require(response.string("approvalsReviewer") == it) {
                "$method did not apply the selected approvals reviewer"
            }
        }
        selection.permissionPreset.sandbox?.let { expected ->
            val sandbox = response["sandbox"]
            val actual = when (sandbox) {
                is JsonPrimitive -> sandbox.contentOrNull
                is JsonObject -> sandbox.string("type")
                else -> null
            }
            require(actual == expected || actual == expected.toCamelCase()) {
                "$method did not apply the selected sandbox"
            }
        }
        return response
    }

    suspend fun turnStart(
        threadId: String,
        text: String,
        clientUserMessageId: String,
        effort: String? = null,
    ): JsonObject {
        checkInitialized()
        return request(
            "turn/start",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("clientUserMessageId", JsonPrimitive(clientUserMessageId))
                effort?.let { put("effort", JsonPrimitive(it)) }
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(text))
                            },
                        )
                    },
                )
            },
        ).jsonObject
    }

    suspend fun turnSteer(
        threadId: String,
        expectedTurnId: String,
        text: String,
        clientUserMessageId: String,
    ): JsonObject {
        checkInitialized()
        return actionRequest(
            "turn/steer",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("expectedTurnId", JsonPrimitive(expectedTurnId))
                put("clientUserMessageId", JsonPrimitive(clientUserMessageId))
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(text))
                            },
                        )
                    },
                )
            },
        ).jsonObject
    }

    suspend fun turnInterrupt(threadId: String, turnId: String): JsonObject {
        checkInitialized()
        return actionRequest(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("turnId", JsonPrimitive(turnId))
            },
        ).jsonObject
    }

    suspend fun streamAgentCards(
        threadId: String,
        turnId: String,
        conversationId: String,
        firstSequence: Long,
        onCard: suspend (Card) -> Unit = {},
    ): List<Card> {
        checkInitialized()
        val buffers = linkedMapOf<String, StringBuilder>()
        val sequences = linkedMapOf<String, Long>()
        val createdAt = linkedMapOf<String, Long>()
        val revisions = linkedMapOf<String, Long>()
        var nextSequence = firstSequence
        loop@ while (true) {
            val notification = try {
                withConnectionPhaseTimeout("turn notification", turnInactivityTimeoutMs) {
                    peer.receiveNotification()
                }
            } catch (failure: Throwable) {
                closeAfterFailure(failure)
            }
                ?: error("app-server connection closed before turn $turnId completed")
            val params = notification.params as? JsonObject ?: continue
            when (notification.method) {
                "item/agentMessage/delta" -> {
                    if (params.string("threadId") != threadId || params.string("turnId") != turnId) continue@loop
                    val itemId = params.string("itemId") ?: continue@loop
                    val text = buffers.getOrPut(itemId) {
                        sequences[itemId] = nextSequence++
                        createdAt[itemId] = nowMs()
                        StringBuilder()
                    }.append(params.string("delta").orEmpty())
                    val revision = revisions.getOrDefault(itemId, 0) + 1
                    revisions[itemId] = revision
                    onCard(
                        agentCard(
                            itemId,
                            conversationId,
                            sequences.getValue(itemId),
                            revision,
                            text.toString(),
                            CardState.OPEN,
                            createdAt.getValue(itemId),
                        ),
                    )
                }
                "turn/completed" -> {
                    val turn = params["turn"] as? JsonObject ?: continue@loop
                    if (params.string("threadId") != threadId || turn.string("id") != turnId) continue@loop
                    require(turn.string("status") == "completed") {
                        "Turn $turnId ended with status ${turn.string("status") ?: "unknown"}"
                    }
                    turn["items"].orEmptyArray().forEach { itemElement ->
                        val item = itemElement as? JsonObject ?: return@forEach
                        if (item.string("type") != "agentMessage") return@forEach
                        val itemId = item.string("id") ?: return@forEach
                        val text = item.string("text") ?: return@forEach
                        buffers.getOrPut(itemId) {
                            sequences[itemId] = nextSequence++
                            createdAt[itemId] = nowMs()
                            StringBuilder()
                        }.apply {
                            clear()
                            append(text)
                        }
                    }
                    break@loop
                }
            }
        }
        return buffers.map { (itemId, text) ->
            val card = agentCard(
                itemId = itemId,
                conversationId = conversationId,
                sequence = sequences.getValue(itemId),
                revision = revisions.getOrDefault(itemId, 0) + 1,
                text = text.toString(),
                state = CardState.COMMITTED,
                createdAtMs = createdAt.getValue(itemId),
            )
            onCard(card)
            card
        }
    }

    suspend fun receiveNotification(): AppServerNotification? {
        checkInitialized()
        return peer.receiveNotification()
    }

    suspend fun close() {
        peer.close()
    }

    private suspend fun request(
        method: String,
        params: JsonElement,
        closeOnFailure: Boolean = true,
    ): JsonElement = try {
        withConnectionPhaseTimeout("$method response", requestTimeoutMs) {
            peer.request(method, params)
        }
    } catch (failure: Throwable) {
        if (closeOnFailure) closeAfterFailure(failure) else throw failure
    }

    private suspend fun notify(method: String, params: JsonElement? = null) {
        try {
            withConnectionPhaseTimeout("$method notification", requestTimeoutMs) {
                peer.notify(method, params)
            }
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
        }
    }

    private suspend fun actionRequest(method: String, params: JsonElement): JsonElement = try {
        request(method, params, closeOnFailure = false)
    } catch (failure: JsonRpcRemoteException) {
        throw failure
    } catch (failure: Throwable) {
        closeAfterFailure(failure)
    }

    private suspend fun closeAfterFailure(failure: Throwable): Nothing {
        withContext(NonCancellable) {
            try {
                peer.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        }
        throw failure
    }

    private fun checkInitialized() {
        check(initialized) { "app-server connection must be initialized first" }
    }

    private fun agentCard(
        itemId: String,
        conversationId: String,
        sequence: Long,
        revision: Long,
        text: String,
        state: CardState,
        createdAtMs: Long,
    ): Card = Card(
        id = itemId,
        conversationId = conversationId,
        sequence = sequence,
        revision = revision,
        role = CardRole.AGENT,
        state = state,
        fullText = text,
        createdAtMs = createdAtMs,
        updatedAtMs = nowMs(),
        source = CardSource.CODEX_AGENT_MESSAGE,
    )
}

private fun String.toCamelCase(): String {
    val parts = split('-')
    return parts.first() + parts.drop(1).joinToString("") {
        it.replaceFirstChar(Char::uppercase)
    }
}

data class M1TurnInput(
    val text: String,
    val threadId: String? = null,
    val clientUserMessageId: String,
) {
    fun pendingUserCard(
        conversationId: String,
        sequence: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Card = Card(
        id = clientUserMessageId,
        conversationId = conversationId,
        sequence = sequence,
        revision = 1,
        role = CardRole.USER,
        state = CardState.OPEN,
        fullText = text,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
        delivery = DeliveryState.LOCAL_PENDING,
        source = CardSource.DEALER_INPUT,
    )
}

data class M1Timeouts(
    val tcpConnectMs: Long = 10_000,
    val sshConnectMs: Long = 15_000,
    val daemonCommandMs: Long = 30_000,
    val proxyStartMs: Long = 15_000,
    val webSocketUpgradeMs: Long = 10_000,
    val appServerRequestMs: Long = 30_000,
    val turnInactivityMs: Long = 5 * 60_000,
    val reconnectInspectionMs: Long = 60_000,
) {
    init {
        listOf(
            tcpConnectMs,
            sshConnectMs,
            daemonCommandMs,
            proxyStartMs,
            webSocketUpgradeMs,
            appServerRequestMs,
            turnInactivityMs,
            reconnectInspectionMs,
        ).forEach { require(it > 0) { "M1 phase timeouts must be positive" } }
    }
}

enum class M1ConnectionPhase {
    CONNECTING,
    RUNNING,
    RECONNECTING,
}

enum class M1FailurePhase {
    TCP_CONNECT,
    SSH_CONNECT,
    DAEMON,
    PROXY,
    WEBSOCKET,
    APP_SERVER_INITIALIZE,
    APP_SERVER_REQUEST,
    TURN_START,
    TURN_NOTIFICATIONS,
    RECONNECT_INSPECTION,
}

private class M1PhaseMarker(
    val phase: M1FailurePhase,
) : IllegalStateException("$phase failed")

data class M1ReconnectPolicy(
    val maxAttempts: Int = 6,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 8_000,
) {
    init {
        require(maxAttempts > 0) { "Reconnect attempts must be positive" }
        require(initialBackoffMs >= 0) { "Initial reconnect backoff must not be negative" }
        require(maxBackoffMs >= initialBackoffMs) { "Maximum reconnect backoff must cover the initial backoff" }
    }

    fun backoffMs(failedAttempt: Int): Long {
        var backoff = initialBackoffMs
        repeat((failedAttempt - 1).coerceAtLeast(0)) {
            backoff = if (backoff >= maxBackoffMs / 2) maxBackoffMs else backoff * 2
        }
        return backoff
    }
}

data class M1RecoveryUpdate(
    val failedAttempt: Int,
    val maxAttempts: Int,
    val retryInMs: Long,
    val failurePhase: M1FailurePhase,
)

enum class M1TurnOutcome {
    INTERRUPTED,
    FAILED,
    UNKNOWN,
}

class M1TurnRecoveryException(
    val outcome: M1TurnOutcome,
    val delivery: DeliveryState,
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

data class M1RunResult(
    val host: CodexHost,
    val route: HostConnectionRoute,
    val reconnectRoute: HostConnectionRoute,
    val daemonVersions: DaemonVersions,
    val initializeResult: JsonObject,
    val threadId: String,
    val conversationId: String,
    val historyCards: List<Card>,
    val userCard: Card,
    val streamedCards: List<Card>,
    val matchingUserMessagesAfterReconnect: Int,
    val recoveredAfterDisconnect: Boolean,
    val routeDiagnostics: List<RouteDiagnostic>,
)

class M1OneHostDealerSlice(
    private val host: CodexHost = InitialCodexHosts.u4090,
    private val dialer: HostTcpDialer,
    private val sshClient: HostSshClient,
    private val daemon: CodexDaemonLifecycle = UpstreamCodexDaemon(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeouts: M1Timeouts = M1Timeouts(),
    private val reconnectPolicy: M1ReconnectPolicy = M1ReconnectPolicy(),
    private val appServerFactory: suspend (DuplexByteStream) -> CodexAppServerSession = { proxy ->
        val socket = AppServerWebSocket(proxy, handshakeTimeoutMs = timeouts.webSocketUpgradeMs)
        socket.open()
        CodexAppServerSession(
            WebSocketJsonRpcPeer(socket),
            requestTimeoutMs = timeouts.appServerRequestMs,
            turnInactivityTimeoutMs = timeouts.turnInactivityMs,
        )
    },
) {
    suspend fun run(
        input: M1TurnInput,
        onCard: suspend (Card) -> Unit = {},
        onPhase: suspend (M1ConnectionPhase) -> Unit = {},
        onRoute: suspend (HostConnectionRoute, List<RouteDiagnostic>) -> Unit = { _, _ -> },
        onRecovery: suspend (M1RecoveryUpdate) -> Unit = {},
    ): M1RunResult {
        require(input.text.isNotBlank()) { "Turn text must not be blank" }
        require(input.clientUserMessageId.isNotBlank()) { "Client user-message ID must not be blank" }
        onPhase(M1ConnectionPhase.CONNECTING)

        val emittedRevisions = mutableMapOf<String, Long>()
        val emitCard: suspend (Card) -> Unit = { card ->
            emittedRevisions[card.id] = maxOf(emittedRevisions[card.id] ?: 0, card.revision)
            onCard(card)
        }
        var recoveryContext: RecoveryContext? = null
        var latestUserCard: Card? = null
        var turnAttempted = false
        suspend fun markAcceptanceUnknownIfPending() {
            val current = latestUserCard
            if (turnAttempted && current?.delivery == DeliveryState.LOCAL_PENDING) {
                val unknown = current.withDelivery(DeliveryState.UNKNOWN, emittedRevisions)
                latestUserCard = unknown
                emitCard(unknown)
            }
        }
        val firstPass = try {
            connect(onRoute).useConnected { first ->
                val initializeResult = inPhase(M1FailurePhase.APP_SERVER_INITIALIZE) {
                    first.appServer.initialize()
                }
                val listedThreads = inPhase(M1FailurePhase.APP_SERVER_REQUEST) {
                    first.appServer.threadList()
                }
                val threadId = input.threadId ?: listedThreads.firstThreadId()
                    ?: error("No app-server threads available on ${host.id}")
                val conversationId = "${host.id}/$threadId"
                inPhase(M1FailurePhase.APP_SERVER_REQUEST) {
                    first.appServer.threadResume(threadId)
                }
                val historyCards = AppServerThreadProjection.cards(
                    inPhase(M1FailurePhase.APP_SERVER_REQUEST) {
                        first.appServer.threadRead(threadId)
                    },
                    conversationId = conversationId,
                )
                val pendingUserCard = input.pendingUserCard(
                    conversationId = conversationId,
                    sequence = (historyCards.maxOfOrNull(Card::sequence) ?: 0L) + 1,
                    nowMs = nowMs(),
                )
                latestUserCard = pendingUserCard
                recoveryContext = RecoveryContext(
                    host = host,
                    route = first.route,
                    daemonVersions = first.daemonVersions,
                    initializeResult = initializeResult,
                    threadId = threadId,
                    conversationId = conversationId,
                    historyCards = historyCards,
                    routeDiagnostics = first.routeDiagnostics,
                )
                historyCards.forEach { emitCard(it) }
                emitCard(pendingUserCard)
                turnAttempted = true
                val turnStart = inPhase(M1FailurePhase.TURN_START) {
                    first.appServer.turnStart(threadId, input.text, input.clientUserMessageId)
                }
                val acceptedUserCard = pendingUserCard.withDelivery(DeliveryState.ACCEPTED, emittedRevisions)
                latestUserCard = acceptedUserCard
                emitCard(acceptedUserCard)
                onPhase(M1ConnectionPhase.RUNNING)
                val turnId = turnStart.turnId()
                    ?: error("turn/start response did not include a turn ID")
                val streamedCards = inPhase(M1FailurePhase.TURN_NOTIFICATIONS) {
                    first.appServer.streamAgentCards(
                        threadId = threadId,
                        turnId = turnId,
                        conversationId = conversationId,
                        firstSequence = acceptedUserCard.sequence + 1,
                        onCard = emitCard,
                    )
                }
                FirstPass(
                    route = first.route,
                    daemonVersions = first.daemonVersions,
                    initializeResult = initializeResult,
                    threadId = threadId,
                    conversationId = conversationId,
                    historyCards = historyCards,
                    userCard = acceptedUserCard,
                    streamedCards = streamedCards,
                    routeDiagnostics = first.routeDiagnostics,
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                withContext(NonCancellable) {
                    markAcceptanceUnknownIfPending()
                }
                throw failure
            }
            val context = recoveryContext
            if (turnAttempted && context != null) {
                onPhase(M1ConnectionPhase.RECONNECTING)
                val inspection = try {
                    inspectAfterReconnect(context.threadId, input.clientUserMessageId, onRoute, onRecovery)
                } catch (recoveryFailure: Throwable) {
                    if (recoveryFailure is CancellationException) {
                        withContext(NonCancellable) {
                            markAcceptanceUnknownIfPending()
                        }
                        throw recoveryFailure
                    }
                    markAcceptanceUnknownIfPending()
                    failure.addSuppressed(recoveryFailure)
                    throw failure
                }
                val currentUserCard = latestUserCard ?: error("Missing local user card")
                val reconciledUserCard = when {
                    inspection.matchingUserMessages == 1 ->
                        currentUserCard.withDelivery(DeliveryState.DELIVERED, emittedRevisions)
                    currentUserCard.delivery == DeliveryState.LOCAL_PENDING ->
                        currentUserCard.withDelivery(DeliveryState.UNKNOWN, emittedRevisions)
                    else -> currentUserCard
                }
                latestUserCard = reconciledUserCard
                if (reconciledUserCard != currentUserCard) emitCard(reconciledUserCard)
                if (inspection.matchingUserMessages == 1) {
                    val turn = AppServerThreadProjection.turnForClientId(
                        inspection.threadRead,
                        input.clientUserMessageId,
                        context.conversationId,
                        reconciledUserCard.sequence + 1,
                    )
                    if (turn?.status == "completed") {
                        val recoveredCards = turn.agentCards.map { card ->
                            card.copy(revision = (emittedRevisions[card.id] ?: 0) + 1)
                        }
                        recoveredCards.forEach { emitCard(it) }
                        return context.result(
                            reconnectRoute = inspection.route,
                            userCard = reconciledUserCard,
                            streamedCards = recoveredCards,
                            matchingUserMessages = 1,
                            reconnectDiagnostics = inspection.diagnostics,
                        )
                    }
                }
                val status = inspection.turnStatus(input.clientUserMessageId)
                throw M1TurnRecoveryException(
                    outcome = status.toM1TurnOutcome(),
                    delivery = reconciledUserCard.delivery ?: DeliveryState.UNKNOWN,
                    message = "Turn outcome is $status; reconnect found ${inspection.matchingUserMessages} " +
                        "matching user message(s). turn/start was not replayed.",
                    cause = failure,
                )
            }
            throw failure
        }

        onPhase(M1ConnectionPhase.RECONNECTING)
        val inspection = inspectAfterReconnect(firstPass.threadId, input.clientUserMessageId, onRoute, onRecovery)
        val reconciledUserCard = if (inspection.matchingUserMessages == 1) {
            firstPass.userCard.withDelivery(DeliveryState.DELIVERED, emittedRevisions)
        } else {
            firstPass.userCard
        }
        if (reconciledUserCard != firstPass.userCard) emitCard(reconciledUserCard)
        require(inspection.matchingUserMessages == 1) {
            "Reconnect expected one ${input.clientUserMessageId} user message, found ${inspection.matchingUserMessages}; " +
                "turn/start was not replayed"
        }
        return M1RunResult(
            host = host,
            route = firstPass.route,
            reconnectRoute = inspection.route,
            daemonVersions = firstPass.daemonVersions,
            initializeResult = firstPass.initializeResult,
            threadId = firstPass.threadId,
            conversationId = firstPass.conversationId,
            historyCards = firstPass.historyCards,
            userCard = reconciledUserCard,
            streamedCards = firstPass.streamedCards,
            matchingUserMessagesAfterReconnect = inspection.matchingUserMessages,
            recoveredAfterDisconnect = false,
            routeDiagnostics = firstPass.routeDiagnostics + inspection.diagnostics,
        )
    }

    private suspend fun inspectAfterReconnect(
        threadId: String,
        clientUserMessageId: String,
        onRoute: suspend (HostConnectionRoute, List<RouteDiagnostic>) -> Unit,
        onRecovery: suspend (M1RecoveryUpdate) -> Unit,
    ): ReconnectInspection = withConnectionPhaseTimeout("reconnect inspection", timeouts.reconnectInspectionMs) {
        val failures = mutableListOf<Throwable>()
        repeat(reconnectPolicy.maxAttempts) { index ->
            try {
                return@withConnectionPhaseTimeout inspectOnce(threadId, clientUserMessageId, onRoute)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                failures += failure
                val failedAttempt = index + 1
                if (failedAttempt == reconnectPolicy.maxAttempts) {
                    failures.dropLast(1).forEach(failure::addSuppressed)
                    throw failure
                }
                val backoffMs = reconnectPolicy.backoffMs(failedAttempt)
                onRecovery(
                    M1RecoveryUpdate(
                        failedAttempt = failedAttempt,
                        maxAttempts = reconnectPolicy.maxAttempts,
                        retryInMs = backoffMs,
                        failurePhase = failure.m1FailurePhase(),
                    ),
                )
                delay(backoffMs)
            }
        }
        error("Reconnect attempts exhausted")
    }

    private suspend fun inspectOnce(
        threadId: String,
        clientUserMessageId: String,
        onRoute: suspend (HostConnectionRoute, List<RouteDiagnostic>) -> Unit,
    ): ReconnectInspection = connect(onRoute).useConnected { connection ->
        inPhase(M1FailurePhase.APP_SERVER_INITIALIZE) {
            connection.appServer.initialize()
        }
        inPhase(M1FailurePhase.APP_SERVER_REQUEST) {
            connection.appServer.threadResume(threadId)
        }
        val threadRead = inPhase(M1FailurePhase.APP_SERVER_REQUEST) {
            connection.appServer.threadRead(threadId)
        }
        ReconnectInspection(
            route = connection.route,
            threadRead = threadRead,
            matchingUserMessages = AppServerThreadProjection.countUserClientId(threadRead, clientUserMessageId),
            diagnostics = connection.routeDiagnostics,
        )
    }

    private suspend fun connect(
        onRoute: suspend (HostConnectionRoute, List<RouteDiagnostic>) -> Unit,
    ): ConnectedM1 {
        val routed = connectSsh()
        var proxy: DuplexByteStream? = null
        try {
            onRoute(routed.route, routed.diagnostics)
            val versions = inPhase(M1FailurePhase.DAEMON) {
                withConnectionPhaseTimeout("daemon status/start", timeouts.daemonCommandMs) {
                    daemon.ensureRunning(routed.ssh)
                }
            }
            proxy = inPhase(M1FailurePhase.PROXY) {
                withConnectionPhaseTimeout("app-server proxy start", timeouts.proxyStartMs) {
                    routed.ssh.execStream(daemon.appServerProxyCommand)
                }
            }
            val appServer = inPhase(M1FailurePhase.WEBSOCKET) { appServerFactory(proxy) }
            return ConnectedM1(
                routed.route,
                routed.tcp,
                routed.ssh,
                versions,
                appServer,
                routed.diagnostics,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                proxy?.closeSuppressing(failure)
                routed.ssh.closeSuppressing(failure)
                routed.tcp.closeSuppressing(failure)
            }
            if (failure is CancellationException) throw failure
            throw RouteConnectionException(
                host.id,
                routed.diagnostics.map { diagnostic ->
                    if (diagnostic.route == routed.route) {
                        diagnostic.copy(failure = failure.message ?: failure::class.java.simpleName)
                    } else {
                        diagnostic
                    }
                },
                failure,
            )
        }
    }

    private suspend fun connectSsh(): RoutedSsh {
        require(host.connectionRoutes.isNotEmpty()) { "No routes configured for ${host.id}" }
        val diagnostics = host.connectionRoutes.map { route ->
            RouteDiagnostic(route, dialer.capability(host, route), attempted = false)
        }.toMutableList()
        var actionableFailure: Throwable? = null
        host.connectionRoutes.forEachIndexed { index, route ->
            if (diagnostics[index].capability != RouteCapability.SUPPORTED_CONFIGURED) return@forEachIndexed
            try {
                val connected = connectSsh(route)
                diagnostics[index] = diagnostics[index].copy(attempted = true)
                return connected.copy(diagnostics = diagnostics)
            } catch (failure: HostIdentityException) {
                diagnostics[index] = diagnostics[index].copy(
                    attempted = true,
                    failure = failure.message ?: failure::class.java.simpleName,
                )
                throw HostIdentityException(
                    failure.message ?: "SSH host identity verification failed",
                    failure,
                    diagnostics,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                actionableFailure = failure
                diagnostics[index] = diagnostics[index].copy(
                    attempted = true,
                    failure = failure.message ?: failure::class.java.simpleName,
                )
            }
        }
        throw RouteConnectionException(host.id, diagnostics, actionableFailure)
    }

    private suspend fun connectSsh(route: HostConnectionRoute): RoutedSsh {
        var tcp: DuplexByteStream? = null
        try {
            tcp = inPhase(M1FailurePhase.TCP_CONNECT) {
                withConnectionPhaseTimeout("TCP connect ${host.id} via $route", timeouts.tcpConnectMs) {
                    dialer.connect(host, route, port = 22)
                }
            }
            val ssh = inPhase(M1FailurePhase.SSH_CONNECT) {
                withConnectionPhaseTimeout("SSH connect ${host.id} via $route", timeouts.sshConnectMs) {
                    sshClient.connect(host, tcp)
                }
            }
            return RoutedSsh(route, tcp, ssh)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                tcp?.closeSuppressing(failure)
            }
            throw failure
        }
    }

    private data class RoutedSsh(
        val route: HostConnectionRoute,
        val tcp: DuplexByteStream,
        val ssh: HostSshSession,
        val diagnostics: List<RouteDiagnostic> = emptyList(),
    )

    private suspend fun <T> ConnectedM1.useConnected(block: suspend (ConnectedM1) -> T): T {
        var failure: Throwable? = null
        try {
            return block(this)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                try {
                    close()
                } catch (closeFailure: Throwable) {
                    if (failure == null) throw closeFailure
                    failure.addSuppressed(closeFailure)
                }
            }
        }
    }

    private data class ConnectedM1(
        val route: HostConnectionRoute,
        val tcp: DuplexByteStream,
        val ssh: HostSshSession,
        val daemonVersions: DaemonVersions,
        val appServer: CodexAppServerSession,
        val routeDiagnostics: List<RouteDiagnostic>,
    ) {
        suspend fun close() {
            var failure: Throwable? = null
            try {
                appServer.close()
            } catch (caught: Throwable) {
                failure = caught
            }
            try {
                ssh.close()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            try {
                tcp.close()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }

    private data class FirstPass(
        val route: HostConnectionRoute,
        val daemonVersions: DaemonVersions,
        val initializeResult: JsonObject,
        val threadId: String,
        val conversationId: String,
        val historyCards: List<Card>,
        val userCard: Card,
        val streamedCards: List<Card>,
        val routeDiagnostics: List<RouteDiagnostic>,
    )

    private data class ReconnectInspection(
        val route: HostConnectionRoute,
        val threadRead: JsonObject,
        val matchingUserMessages: Int,
        val diagnostics: List<RouteDiagnostic>,
    ) {
        fun turnStatus(clientUserMessageId: String): String =
            AppServerThreadProjection.turnForClientId(
                threadRead,
                clientUserMessageId,
                conversationId = "",
                firstSequence = 1,
            )?.status ?: "unknown"
    }

    private data class RecoveryContext(
        val host: CodexHost,
        val route: HostConnectionRoute,
        val daemonVersions: DaemonVersions,
        val initializeResult: JsonObject,
        val threadId: String,
        val conversationId: String,
        val historyCards: List<Card>,
        val routeDiagnostics: List<RouteDiagnostic>,
    ) {
        fun result(
            reconnectRoute: HostConnectionRoute,
            userCard: Card,
            streamedCards: List<Card>,
            matchingUserMessages: Int,
            reconnectDiagnostics: List<RouteDiagnostic>,
        ) = M1RunResult(
            host = host,
            route = route,
            reconnectRoute = reconnectRoute,
            daemonVersions = daemonVersions,
            initializeResult = initializeResult,
            threadId = threadId,
            conversationId = conversationId,
            historyCards = historyCards,
            userCard = userCard,
            streamedCards = streamedCards,
            matchingUserMessagesAfterReconnect = matchingUserMessages,
            recoveredAfterDisconnect = true,
            routeDiagnostics = routeDiagnostics + reconnectDiagnostics,
        )
    }

    private fun Card.withDelivery(
        delivery: DeliveryState,
        emittedRevisions: Map<String, Long>,
    ): Card {
        check(
            this.delivery != DeliveryState.ACCEPTED ||
                delivery == DeliveryState.ACCEPTED ||
                delivery == DeliveryState.DELIVERED,
        ) {
            "Accepted delivery cannot regress to $delivery"
        }
        check(this.delivery != DeliveryState.DELIVERED || delivery == DeliveryState.DELIVERED) {
            "Delivered delivery cannot regress to $delivery"
        }
        return copy(
            revision = (emittedRevisions[id] ?: revision) + 1,
            state = if (delivery == DeliveryState.DELIVERED) CardState.COMMITTED else CardState.OPEN,
            updatedAtMs = nowMs(),
            delivery = delivery,
        )
    }

    private suspend fun <T> inPhase(
        phase: M1FailurePhase,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        failure.addSuppressed(M1PhaseMarker(phase))
        throw failure
    }
}

fun Throwable.m1FailurePhase(): M1FailurePhase = when (this) {
    is M1PhaseMarker -> phase
    else -> if (message?.startsWith("reconnect inspection timed out") == true) {
        M1FailurePhase.RECONNECT_INSPECTION
    } else {
        suppressed.filterIsInstance<M1PhaseMarker>().firstOrNull()?.phase
            ?: cause?.m1FailurePhase()
            ?: suppressed.firstNotNullOfOrNull { it.m1FailurePhase() }
            ?: M1FailurePhase.APP_SERVER_REQUEST
    }
}

private fun String.toM1TurnOutcome(): M1TurnOutcome = when (this) {
    "interrupted", "cancelled" -> M1TurnOutcome.INTERRUPTED
    "failed" -> M1TurnOutcome.FAILED
    else -> M1TurnOutcome.UNKNOWN
}

private suspend fun DuplexByteStream.closeSuppressing(failure: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
}

private suspend fun HostSshSession.closeSuppressing(failure: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
}

object AppServerThreadProjection {
    data class AuthoritativeState(
        val workState: ThreadWorkState?,
        val activeTurnId: String?,
    )

    data class TurnProjection(
        val status: String?,
        val agentCards: List<Card>,
    )

    fun cards(threadReadResponse: JsonObject, conversationId: String): List<Card> {
        val thread = threadReadResponse["thread"] as? JsonObject ?: return emptyList()
        var sequence = 1L
        return thread["turns"].orEmptyArray().flatMap { turnElement ->
            val turn = turnElement as? JsonObject ?: return@flatMap emptyList()
            val createdAtMs = (turn.long("startedAt") ?: 0L) * 1_000
            turn["items"].orEmptyArray().mapNotNull { itemElement ->
                val item = itemElement as? JsonObject
                val type = item?.string("type") ?: "unknown"
                val text = item?.projectedText(type) ?: itemElement.toString()
                if (text.isBlank()) return@mapNotNull null
                Card(
                    id = item?.string("clientId").takeIf { type == "userMessage" }
                        ?: item?.string("id")
                        ?: "$conversationId-${sequence}",
                    conversationId = conversationId,
                    sequence = sequence++,
                    revision = 1,
                    role = roleFor(type),
                    state = CardState.COMMITTED,
                    fullText = text,
                    createdAtMs = createdAtMs,
                    updatedAtMs = createdAtMs,
                    delivery = DeliveryState.DELIVERED.takeIf { type == "userMessage" },
                    source = sourceFor(type),
                )
            }
        }
    }

    fun authoritativeState(threadReadResponse: JsonObject): AuthoritativeState {
        val thread = threadReadResponse["thread"] as? JsonObject
            ?: return AuthoritativeState(null, null)
        val status = when (val value = thread["status"]) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value.string("type")
            else -> null
        }
        val activeTurnId = thread["turns"].orEmptyArray()
            .mapNotNull { it as? JsonObject }
            .lastOrNull { it.string("status") in ACTIVE_TURN_STATUSES }
            ?.string("id")
        return AuthoritativeState(
            workState = when (status) {
                "active" -> ThreadWorkState.BUSY
                "idle" -> ThreadWorkState.READY
                else -> null
            },
            activeTurnId = activeTurnId,
        )
    }

    fun countUserClientId(threadReadResponse: JsonObject, clientUserMessageId: String): Int {
        val thread = threadReadResponse["thread"] as? JsonObject ?: return 0
        return thread["turns"].orEmptyArray().sumOf { turnElement ->
            val turn = turnElement as? JsonObject ?: return@sumOf 0
            turn["items"].orEmptyArray().count { itemElement ->
                val item = itemElement as? JsonObject ?: return@count false
                item.string("type") == "userMessage" && item.string("clientId") == clientUserMessageId
            }
        }
    }

    fun turnForClientId(
        threadReadResponse: JsonObject,
        clientUserMessageId: String,
        conversationId: String,
        firstSequence: Long,
    ): TurnProjection? {
        val thread = threadReadResponse["thread"] as? JsonObject ?: return null
        val turn = thread["turns"].orEmptyArray()
            .mapNotNull { it as? JsonObject }
            .singleOrNull { candidate ->
                candidate["items"].orEmptyArray().any { itemElement ->
                    val item = itemElement as? JsonObject
                    item?.string("type") == "userMessage" && item.string("clientId") == clientUserMessageId
                }
            } ?: return null
        var sequence = firstSequence
        val createdAtMs = (turn.long("startedAt") ?: 0L) * 1_000
        val agentCards = turn["items"].orEmptyArray().mapNotNull { itemElement ->
            val item = itemElement as? JsonObject ?: return@mapNotNull null
            if (item.string("type") != "agentMessage") return@mapNotNull null
            val text = item.projectedText("agentMessage")
            if (text.isBlank()) return@mapNotNull null
            Card(
                id = item.string("id") ?: "$conversationId-${sequence}",
                conversationId = conversationId,
                sequence = sequence++,
                revision = 1,
                role = CardRole.AGENT,
                state = CardState.COMMITTED,
                fullText = text,
                createdAtMs = createdAtMs,
                updatedAtMs = createdAtMs,
                source = CardSource.CODEX_AGENT_MESSAGE,
            )
        }
        return TurnProjection(turn.string("status"), agentCards)
    }

    private fun JsonObject.projectedText(type: String): String = when (type) {
        "userMessage" -> this["content"].orEmptyArray()
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("\n")
            .ifBlank { this.toString() }
        "agentMessage", "plan" -> string("text").orEmpty().ifBlank { this.toString() }
        "reasoning" -> (this["summary"].orEmptyArray() + this["content"].orEmptyArray())
            .joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            .ifBlank { this.toString() }
        "commandExecution" -> listOfNotNull(string("command"), string("aggregatedOutput"))
            .joinToString("\n")
            .ifBlank { this.toString() }
        "fileChange" -> this.toString()
        else -> this.toString()
    }

    private fun roleFor(type: String): CardRole = when (type) {
        "userMessage" -> CardRole.USER
        "agentMessage" -> CardRole.AGENT
        "commandExecution" -> CardRole.TOOL
        else -> CardRole.SYSTEM
    }

    private fun sourceFor(type: String): CardSource = when (type) {
        "userMessage" -> CardSource.CODEX_USER_MESSAGE
        "agentMessage" -> CardSource.CODEX_AGENT_MESSAGE
        "plan" -> CardSource.CODEX_PLAN
        "reasoning" -> CardSource.CODEX_REASONING
        "commandExecution" -> CardSource.CODEX_COMMAND
        "fileChange" -> CardSource.CODEX_FILE_CHANGE
        else -> CardSource.SYSTEM
    }

    private val ACTIVE_TURN_STATUSES = setOf("inProgress", "running")
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
private fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.firstThreadId(): String? =
    this["data"].orEmptyArray().firstNotNullOfOrNull { (it as? JsonObject)?.string("id") }
private fun JsonObject.turnId(): String? = (this["turn"] as? JsonObject)?.string("id")
