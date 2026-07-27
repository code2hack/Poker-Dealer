package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.InitialCodexHosts
import com.code2hack.pokerdealer.protocol.host.CommandResult
import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.HostSshClient
import com.code2hack.pokerdealer.protocol.host.HostSshSession
import com.code2hack.pokerdealer.protocol.host.HostTcpDialer
import com.code2hack.pokerdealer.protocol.host.HostIdentityException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    val raw: JsonObject,
)

class UpstreamCodexDaemon(
    codexExecutable: String = DEFAULT_CODEX_EXECUTABLE,
) {
    init {
        require(codexExecutable.isNotBlank()) { "Codex executable is required" }
    }

    val daemonVersionCommand = "$codexExecutable app-server daemon version"
    val daemonStartCommand = "$codexExecutable app-server daemon start"
    val appServerProxyCommand = "$codexExecutable app-server proxy"

    suspend fun ensureRunning(ssh: HostSshSession): DaemonVersions {
        val current = ssh.exec(daemonVersionCommand)
        if (current.exitCode == 0) {
            val versions = parseVersions(current)
            if (versions.status == null || versions.status == "running") return versions
        }
        val started = ssh.exec(daemonStartCommand)
        require(started.exitCode == 0) { "Failed to start app-server daemon: ${started.stderr.ifBlank { started.stdout }}" }
        val version = ssh.exec(daemonVersionCommand)
        require(version.exitCode == 0) {
            "Failed to query app-server daemon after start: ${version.stderr.ifBlank { version.stdout }}"
        }
        return parseVersions(version).also {
            require(it.status == null || it.status == "running") {
                "App-server daemon did not reach running state: ${it.status}"
            }
        }
    }

    fun parseVersions(result: CommandResult): DaemonVersions {
        val raw = AppServerJson.parseToJsonElement(result.stdout.trim()).jsonObject
        return DaemonVersions(
            status = (raw["status"] as? JsonPrimitive)?.contentOrNull,
            cliVersion = raw.findString("cliVersion", "cli_version", "codexVersion", "codex_version"),
            appServerVersion = raw.findString("appServerVersion", "app_server_version", "serverVersion", "server_version"),
            raw = raw,
        )
    }

    private fun JsonElement.findString(vararg keys: String): String? = when (this) {
        is JsonObject -> {
            keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
                ?: values.firstNotNullOfOrNull { it.findString(*keys) }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findString(*keys) }
        else -> null
    }

    companion object {
        const val DEFAULT_CODEX_EXECUTABLE = "~/.local/bin/codex"
    }
}

class CodexAppServerSession(
    private val peer: JsonRpcPeer,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var initialized = false

    suspend fun initialize(): JsonObject {
        check(!initialized) { "app-server connection is already initialized" }
        val result = peer.request(
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
        peer.notify("initialized")
        initialized = true
        return result
    }

    suspend fun threadList(limit: Int = 20): JsonObject {
        checkInitialized()
        return peer.request(
            "thread/list",
            buildJsonObject {
                put("limit", JsonPrimitive(limit))
                put("archived", JsonPrimitive(false))
            },
        ).jsonObject
    }

    suspend fun threadResume(threadId: String): JsonObject {
        checkInitialized()
        return peer.request(
            "thread/resume",
            buildJsonObject { put("threadId", JsonPrimitive(threadId)) },
        ).jsonObject
    }

    suspend fun threadRead(threadId: String): JsonObject {
        checkInitialized()
        return peer.request(
            "thread/read",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
                put("includeTurns", JsonPrimitive(true))
            },
        ).jsonObject
    }

    suspend fun turnStart(
        threadId: String,
        text: String,
        clientUserMessageId: String,
    ): JsonObject {
        checkInitialized()
        return peer.request(
            "turn/start",
            buildJsonObject {
                put("threadId", JsonPrimitive(threadId))
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
            val notification = peer.receiveNotification()
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

    suspend fun close() {
        peer.close()
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

data class M1TurnInput(
    val text: String,
    val threadId: String? = null,
    val clientUserMessageId: String,
)

data class M1RunResult(
    val host: CodexHost,
    val route: HostConnectionRoute,
    val reconnectRoute: HostConnectionRoute,
    val daemonVersions: DaemonVersions,
    val initializeResult: JsonObject,
    val threadId: String,
    val conversationId: String,
    val historyCards: List<Card>,
    val streamedCards: List<Card>,
    val matchingUserMessagesAfterReconnect: Int,
    val recoveredAfterDisconnect: Boolean,
)

class M1OneHostDealerSlice(
    private val host: CodexHost = InitialCodexHosts.u4090,
    private val dialer: HostTcpDialer,
    private val sshClient: HostSshClient,
    private val daemon: UpstreamCodexDaemon = UpstreamCodexDaemon(),
    private val appServerFactory: suspend (DuplexByteStream) -> CodexAppServerSession = { proxy ->
        val socket = AppServerWebSocket(proxy)
        socket.open()
        CodexAppServerSession(WebSocketJsonRpcPeer(socket))
    },
) {
    suspend fun run(
        input: M1TurnInput,
        onCard: suspend (Card) -> Unit = {},
    ): M1RunResult {
        require(input.text.isNotBlank()) { "Turn text must not be blank" }
        require(input.clientUserMessageId.isNotBlank()) { "Client user-message ID must not be blank" }

        val emittedRevisions = mutableMapOf<String, Long>()
        val emitCard: suspend (Card) -> Unit = { card ->
            emittedRevisions[card.id] = maxOf(emittedRevisions[card.id] ?: 0, card.revision)
            onCard(card)
        }
        var recoveryContext: RecoveryContext? = null
        var turnAttempted = false
        val firstPass = try {
            connect().useConnected { first ->
                val initializeResult = first.appServer.initialize()
                val listedThreads = first.appServer.threadList()
                val threadId = input.threadId ?: listedThreads.firstThreadId()
                    ?: error("No app-server threads available on ${host.id}")
                val conversationId = "${host.id}/$threadId"
                first.appServer.threadResume(threadId)
                val historyCards = AppServerThreadProjection.cards(
                    first.appServer.threadRead(threadId),
                    conversationId = conversationId,
                )
                recoveryContext = RecoveryContext(
                    host = host,
                    route = first.route,
                    daemonVersions = first.daemonVersions,
                    initializeResult = initializeResult,
                    threadId = threadId,
                    conversationId = conversationId,
                    historyCards = historyCards,
                )
                historyCards.forEach { emitCard(it) }
                turnAttempted = true
                val turnId = first.appServer.turnStart(threadId, input.text, input.clientUserMessageId).turnId()
                    ?: error("turn/start response did not include a turn ID")
                val streamedCards = first.appServer.streamAgentCards(
                    threadId = threadId,
                    turnId = turnId,
                    conversationId = conversationId,
                    firstSequence = historyCards.size + 1L,
                    onCard = emitCard,
                )
                FirstPass(
                    route = first.route,
                    daemonVersions = first.daemonVersions,
                    initializeResult = initializeResult,
                    threadId = threadId,
                    conversationId = conversationId,
                    historyCards = historyCards,
                    streamedCards = streamedCards,
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val context = recoveryContext
            if (turnAttempted && context != null) {
                val inspection = try {
                    inspectAfterReconnect(context.threadId, input.clientUserMessageId)
                } catch (recoveryFailure: Throwable) {
                    failure.addSuppressed(recoveryFailure)
                    throw failure
                }
                if (inspection.matchingUserMessages == 1) {
                    val turn = AppServerThreadProjection.turnForClientId(
                        inspection.threadRead,
                        input.clientUserMessageId,
                        context.conversationId,
                        context.historyCards.size + 1L,
                    )
                    if (turn?.status == "completed") {
                        val recoveredCards = turn.agentCards.map { card ->
                            card.copy(revision = (emittedRevisions[card.id] ?: 0) + 1)
                        }
                        recoveredCards.forEach { emitCard(it) }
                        return context.result(
                            reconnectRoute = inspection.route,
                            streamedCards = recoveredCards,
                            matchingUserMessages = 1,
                        )
                    }
                }
                throw IllegalStateException(
                    "Turn outcome is ${inspection.turnStatus(input.clientUserMessageId)}; reconnect found " +
                        "${inspection.matchingUserMessages} matching user message(s). turn/start was not replayed.",
                    failure,
                )
            }
            throw failure
        }

        val inspection = inspectAfterReconnect(firstPass.threadId, input.clientUserMessageId)
        require(inspection.matchingUserMessages == 1) {
            "Reconnect expected one ${input.clientUserMessageId} user message, found ${inspection.matchingUserMessages}"
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
            streamedCards = firstPass.streamedCards,
            matchingUserMessagesAfterReconnect = inspection.matchingUserMessages,
            recoveredAfterDisconnect = false,
        )
    }

    private suspend fun inspectAfterReconnect(
        threadId: String,
        clientUserMessageId: String,
    ): ReconnectInspection = connect().useConnected { connection ->
        connection.appServer.initialize()
        connection.appServer.threadResume(threadId)
        val threadRead = connection.appServer.threadRead(threadId)
        ReconnectInspection(
            route = connection.route,
            threadRead = threadRead,
            matchingUserMessages = AppServerThreadProjection.countUserClientId(threadRead, clientUserMessageId),
        )
    }

    private suspend fun connect(): ConnectedM1 {
        val routed = connectSsh()
        var proxy: DuplexByteStream? = null
        try {
            val versions = daemon.ensureRunning(routed.ssh)
            proxy = routed.ssh.execStream(daemon.appServerProxyCommand)
            return ConnectedM1(
                routed.route,
                routed.tcp,
                routed.ssh,
                versions,
                appServerFactory(proxy),
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                proxy?.closeSuppressing(failure)
                routed.ssh.closeSuppressing(failure)
                routed.tcp.closeSuppressing(failure)
            }
            throw failure
        }
    }

    private suspend fun connectSsh(): RoutedSsh {
        require(host.connectionRoutes.isNotEmpty()) { "No routes configured for ${host.id}" }
        val failures = mutableListOf<Throwable>()
        host.connectionRoutes.forEach { route ->
            try {
                return connectSsh(route)
            } catch (failure: HostIdentityException) {
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        throw IllegalStateException("Unable to connect to ${host.id} through any configured route", failures.last()).also {
            failures.dropLast(1).forEach(it::addSuppressed)
        }
    }

    private suspend fun connectSsh(route: HostConnectionRoute): RoutedSsh {
        var tcp: DuplexByteStream? = null
        try {
            tcp = dialer.connect(host, route, port = 22)
            return RoutedSsh(route, tcp, sshClient.connect(host, tcp))
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
        val streamedCards: List<Card>,
    )

    private data class ReconnectInspection(
        val route: HostConnectionRoute,
        val threadRead: JsonObject,
        val matchingUserMessages: Int,
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
    ) {
        fun result(
            reconnectRoute: HostConnectionRoute,
            streamedCards: List<Card>,
            matchingUserMessages: Int,
        ) = M1RunResult(
            host = host,
            route = route,
            reconnectRoute = reconnectRoute,
            daemonVersions = daemonVersions,
            initializeResult = initializeResult,
            threadId = threadId,
            conversationId = conversationId,
            historyCards = historyCards,
            streamedCards = streamedCards,
            matchingUserMessagesAfterReconnect = matchingUserMessages,
            recoveredAfterDisconnect = true,
        )
    }
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
                    id = item?.string("id") ?: "$conversationId-${sequence}",
                    conversationId = conversationId,
                    sequence = sequence++,
                    revision = 1,
                    role = roleFor(type),
                    state = CardState.COMMITTED,
                    fullText = text,
                    createdAtMs = createdAtMs,
                    updatedAtMs = createdAtMs,
                    source = sourceFor(type),
                )
            }
        }
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
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
private fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.firstThreadId(): String? =
    this["data"].orEmptyArray().firstNotNullOfOrNull { (it as? JsonObject)?.string("id") }
private fun JsonObject.turnId(): String? = (this["turn"] as? JsonObject)?.string("id")
