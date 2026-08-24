package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.CommandApprovalRequest
import com.code2hack.pokerdealer.domain.CommandApprovalScope
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

const val COMMAND_APPROVAL_METHOD = "item/commandExecution/requestApproval"

sealed interface CommandApprovalParseResult {
    data class Accepted(val request: CommandApprovalRequest) : CommandApprovalParseResult
    data class Rejected(val reason: String) : CommandApprovalParseResult
}

object CommandApprovalProtocol {
    fun parse(
        hostId: String,
        appServerGeneration: Long,
        wire: AppServerRequest,
    ): CommandApprovalParseResult {
        if (wire.method != COMMAND_APPROVAL_METHOD) {
            return CommandApprovalParseResult.Rejected("Unsupported server request")
        }
        val params = wire.params as? JsonObject
            ?: return CommandApprovalParseResult.Rejected("Command approval parameters are malformed")
        val threadId = params.text("threadId")
            ?: return CommandApprovalParseResult.Rejected("Command approval has no thread")
        val turnId = params.text("turnId")
            ?: return CommandApprovalParseResult.Rejected("Command approval has no turn")
        val itemId = params.text("itemId")
            ?: return CommandApprovalParseResult.Rejected("Command approval has no item")
        val startedAtMs = params.long("startedAtMs")
            ?: return CommandApprovalParseResult.Rejected("Command approval has no start time")
        val command = params.nullableText("command")
        val cwd = params.nullableText("cwd")
        val network = params["networkApprovalContext"] as? JsonObject
        val networkHost = network?.text("host")
        val networkProtocol = network?.text("protocol")
        val commandScopeComplete = !command.isNullOrBlank()
        val networkScopeComplete = !networkHost.isNullOrBlank() && !networkProtocol.isNullOrBlank()
        if (!commandScopeComplete && !networkScopeComplete) {
            return CommandApprovalParseResult.Rejected("Command approval scope is incomplete")
        }
        if (network != null && !networkScopeComplete) {
            return CommandApprovalParseResult.Rejected("Network approval scope is incomplete")
        }

        val proposalElement = params["proposedExecpolicyAmendment"]
        val proposal = when (proposalElement) {
            null -> null
            is JsonArray -> proposalElement.map {
                (it as? JsonPrimitive)?.contentOrNull
                    ?: return CommandApprovalParseResult.Rejected("Execpolicy amendment is malformed")
            }
            else -> return CommandApprovalParseResult.Rejected("Execpolicy amendment is malformed")
        }
        val offered = availableDecisions(params["availableDecisions"], proposal != null)
        if (offered.isEmpty()) {
            return CommandApprovalParseResult.Rejected("Command approval has no safe protocol-proven decision")
        }

        val approvalId = params.nullableText("approvalId")
        val scope = CommandApprovalScope(command, cwd, networkHost, networkProtocol)
        return CommandApprovalParseResult.Accepted(
            CommandApprovalRequest(
                locator = ServerRequestLocator(
                    hostId,
                    appServerGeneration,
                    wire.id.requestIdKey(),
                ),
                thread = CodexThreadLocator(hostId, threadId),
                turnId = turnId,
                itemId = itemId,
                approvalId = approvalId,
                scope = scope,
                proposedExecpolicyAmendment = proposal,
                offeredDecisions = offered.toSet(),
                offeredDecisionOrder = offered,
                fingerprint = fingerprint(wire.method, threadId, turnId, itemId, approvalId, scope, proposal),
                createdAtMs = startedAtMs,
            ),
        )
    }

    fun response(
        request: CommandApprovalRequest,
        decision: CommandApprovalDecision,
    ): JsonObject {
        require(decision in request.offeredDecisions) { "Decision is unavailable for this request" }
        return buildJsonObject {
            put(
                "decision",
                if (decision == CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT) {
                    buildJsonObject {
                        put(
                            decision.wireName,
                            buildJsonObject {
                                put(
                                    "execpolicy_amendment",
                                    buildJsonArray {
                                        request.proposedExecpolicyAmendment
                                            ?.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }
                } else {
                    JsonPrimitive(decision.wireName)
                },
            )
        }
    }

    fun resolved(notification: AppServerNotification): ResolvedServerRequest? {
        if (notification.method != "serverRequest/resolved") return null
        val params = notification.params as? JsonObject ?: return null
        return ResolvedServerRequest(
            requestId = params["requestId"]?.requestIdKey() ?: return null,
            threadId = params.text("threadId") ?: return null,
        )
    }

    private fun availableDecisions(
        element: JsonElement?,
        hasExecpolicyProposal: Boolean,
    ): List<CommandApprovalDecision> {
        val protocolDecisions = if (element == null) {
            PROVEN_DECISIONS
        } else {
            (element as? JsonArray).orEmpty().mapNotNull { decision ->
                val name = (decision as? JsonPrimitive)?.contentOrNull
                    ?: (decision as? JsonObject)?.keys?.singleOrNull()
                CommandApprovalDecision.entries.firstOrNull { it.wireName == name }
            }
        }
        return protocolDecisions.filter {
            it != CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT || hasExecpolicyProposal
        }.distinct()
    }

    private fun fingerprint(
        method: String,
        threadId: String,
        turnId: String,
        itemId: String,
        approvalId: String?,
        scope: CommandApprovalScope,
        proposal: List<String>?,
    ): String = AppServerJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("method", JsonPrimitive(method))
            put("threadId", JsonPrimitive(threadId))
            put("turnId", JsonPrimitive(turnId))
            put("itemId", JsonPrimitive(itemId))
            approvalId?.let { put("approvalId", JsonPrimitive(it)) }
            scope.command?.let { put("command", JsonPrimitive(it)) }
            scope.workingDirectory?.let { put("cwd", JsonPrimitive(it)) }
            scope.networkHost?.let { put("networkHost", JsonPrimitive(it)) }
            scope.networkProtocol?.let { put("networkProtocol", JsonPrimitive(it)) }
            proposal?.let {
                put("proposedExecpolicyAmendment", buildJsonArray {
                    it.forEach { token -> add(JsonPrimitive(token)) }
                })
            }
        },
    )

    private val PROVEN_DECISIONS = listOf(
        CommandApprovalDecision.ACCEPT,
        CommandApprovalDecision.ACCEPT_FOR_SESSION,
        CommandApprovalDecision.ACCEPT_WITH_EXECPOLICY_AMENDMENT,
        CommandApprovalDecision.DECLINE,
        CommandApprovalDecision.CANCEL,
    )
}

data class ResolvedServerRequest(
    val requestId: String,
    val threadId: String,
)

internal fun JsonElement.requestIdKey(): String = when (this) {
    is JsonPrimitive -> if (isString) "s:$content" else "n:$content"
    else -> AppServerJson.encodeToString(JsonElement.serializer(), this)
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.nullableText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()
