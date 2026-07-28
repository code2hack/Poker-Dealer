package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardRole
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CardState
import com.code2hack.pokerdealer.domain.FileChangeContent
import com.code2hack.pokerdealer.domain.TurnOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class StructuredCardUpdate(
    val cards: List<Card>,
    val requiresReread: Boolean = false,
)

object AppServerStructuredCardProjection {
    fun apply(
        current: List<Card>,
        notification: AppServerNotification,
        conversationId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): StructuredCardUpdate {
        val params = notification.params as? JsonObject ?: return StructuredCardUpdate(current)
        val turnId = params.text("turnId")
            ?: (params["turn"] as? JsonObject)?.text("id")
            ?: return StructuredCardUpdate(current)
        val nextSequence = (current.maxOfOrNull(Card::sequence) ?: 0L) + 1

        fun upsert(candidate: Card, authoritative: Boolean = false): StructuredCardUpdate {
            val prior = current.firstOrNull { it.id == candidate.id }
            val card = if (prior == null) {
                candidate
            } else {
                candidate.copy(
                    sequence = prior.sequence,
                    revision = prior.revision + 1,
                    createdAtMs = prior.createdAtMs,
                    fullText = if (!authoritative && candidate.fullText.isEmpty()) {
                        prior.fullText
                    } else {
                        candidate.fullText
                    },
                )
            }
            return StructuredCardUpdate(
                cards = current.filterNot { it.id == card.id } + card,
                requiresReread = !card.contentComplete,
            )
        }

        return when (notification.method) {
            "item/started", "item/completed" -> {
                val item = params["item"] as? JsonObject ?: return StructuredCardUpdate(current, true)
                val itemId = item.text("id") ?: return StructuredCardUpdate(current, true)
                val prior = current.firstOrNull { it.id == itemId }
                if (notification.method == "item/started" && prior?.state.isTerminal()) {
                    return StructuredCardUpdate(current)
                }
                val card = item.structuredCard(
                    conversationId = conversationId,
                    turnId = turnId,
                    sequence = prior?.sequence ?: nextSequence,
                    revision = (prior?.revision ?: 0L) + 1,
                    createdAtMs = prior?.createdAtMs
                        ?: params.long("startedAtMs")
                        ?: params.long("completedAtMs")
                        ?: nowMs,
                    updatedAtMs = params.long("completedAtMs") ?: nowMs,
                    preserveOutput = prior?.fullText.takeIf {
                        notification.method == "item/started" && item.text("aggregatedOutput") == null
                    },
                ) ?: return StructuredCardUpdate(current)
                upsert(card, authoritative = notification.method == "item/completed")
            }
            "item/commandExecution/outputDelta", "item/fileChange/outputDelta" -> {
                val itemId = params.text("itemId") ?: return StructuredCardUpdate(current, true)
                val delta = params.text("delta") ?: return StructuredCardUpdate(current, true)
                val source = if (notification.method.contains("commandExecution")) {
                    CardSource.CODEX_COMMAND
                } else {
                    CardSource.CODEX_FILE_CHANGE
                }
                val prior = current.firstOrNull { it.id == itemId }
                if (prior?.state.isTerminal()) return StructuredCardUpdate(current)
                upsert(
                    (prior ?: placeholder(itemId, conversationId, turnId, nextSequence, source, nowMs))
                        .copy(
                            fullText = prior?.fullText.orEmpty() + delta,
                            updatedAtMs = nowMs,
                            contentComplete = prior?.contentComplete == true,
                        ),
                )
            }
            "item/fileChange/patchUpdated" -> {
                val itemId = params.text("itemId") ?: return StructuredCardUpdate(current, true)
                val changes = params.fileChanges()
                val prior = current.firstOrNull { it.id == itemId }
                if (prior?.state.isTerminal()) return StructuredCardUpdate(current)
                upsert(
                    (prior ?: placeholder(
                        itemId,
                        conversationId,
                        turnId,
                        nextSequence,
                        CardSource.CODEX_FILE_CHANGE,
                        nowMs,
                    )).copy(
                        state = CardState.OPEN,
                        status = prior?.status ?: "inProgress",
                        fullText = changes.joinToString(separator = "") { it.diff },
                        fileChanges = changes,
                        updatedAtMs = nowMs,
                        contentComplete = changes.isNotEmpty(),
                    ),
                )
            }
            "turn/diff/updated" -> {
                val diff = params.text("diff") ?: return StructuredCardUpdate(current, true)
                val card = current.lastOrNull {
                    it.turnId == turnId && it.source == CardSource.CODEX_FILE_CHANGE
                } ?: placeholder(
                    "$turnId:diff",
                    conversationId,
                    turnId,
                    nextSequence,
                    CardSource.CODEX_FILE_CHANGE,
                    nowMs,
                )
                if (card.state.isTerminal()) return StructuredCardUpdate(current)
                upsert(
                    card.copy(
                        fullText = diff,
                        updatedAtMs = nowMs,
                        contentComplete = card.fileChanges.isNotEmpty(),
                    ),
                )
            }
            "turn/completed" -> {
                val outcome = (params["turn"] as? JsonObject)?.text("status").toOutcome()
                StructuredCardUpdate(
                    current.map { card ->
                        if (card.turnId == turnId) {
                            card.copy(
                                revision = card.revision + 1,
                                turnOutcome = outcome,
                                updatedAtMs = nowMs,
                            )
                        } else {
                            card
                        }
                    },
                )
            }
            else -> StructuredCardUpdate(current)
        }
    }

    internal fun JsonObject.structuredCard(
        conversationId: String,
        turnId: String?,
        sequence: Long,
        revision: Long = 1,
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        turnOutcome: TurnOutcome? = null,
        preserveOutput: String? = null,
    ): Card? {
        val id = text("id") ?: return null
        val type = text("type")
        val status = text("status")
        return when (type) {
            "commandExecution" -> {
                val command = text("command")
                val cwd = text("cwd")
                Card(
                    id = id,
                    conversationId = conversationId,
                    sequence = sequence,
                    revision = revision,
                    role = CardRole.TOOL,
                    state = status.cardState(),
                    fullText = text("aggregatedOutput") ?: preserveOutput.orEmpty(),
                    createdAtMs = createdAtMs,
                    updatedAtMs = updatedAtMs,
                    source = CardSource.CODEX_COMMAND,
                    turnId = turnId,
                    command = command,
                    workingDirectory = cwd,
                    status = status,
                    exitCode = int("exitCode"),
                    turnOutcome = turnOutcome,
                    contentComplete = command != null && cwd != null && status != null,
                )
            }
            "fileChange" -> {
                val changes = fileChanges()
                Card(
                    id = id,
                    conversationId = conversationId,
                    sequence = sequence,
                    revision = revision,
                    role = CardRole.TOOL,
                    state = status.cardState(),
                    fullText = changes.joinToString(separator = "") { it.diff },
                    createdAtMs = createdAtMs,
                    updatedAtMs = updatedAtMs,
                    source = CardSource.CODEX_FILE_CHANGE,
                    turnId = turnId,
                    status = status,
                    fileChanges = changes,
                    turnOutcome = turnOutcome,
                    contentComplete = status != null && changes.isNotEmpty(),
                )
            }
            else -> null
        }
    }

    private fun placeholder(
        id: String,
        conversationId: String,
        turnId: String,
        sequence: Long,
        source: CardSource,
        nowMs: Long,
    ) = Card(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        revision = 1,
        role = CardRole.TOOL,
        state = CardState.OPEN,
        fullText = "",
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
        source = source,
        turnId = turnId,
        contentComplete = false,
    )
}

private fun JsonObject.fileChanges(): List<FileChangeContent> =
    (this["changes"] as? JsonArray).orEmpty().mapNotNull { element ->
        val change = element as? JsonObject ?: return@mapNotNull null
        FileChangeContent(
            path = change.text("path") ?: return@mapNotNull null,
            kind = change.text("kind") ?: return@mapNotNull null,
            diff = change.text("diff") ?: return@mapNotNull null,
        )
    }

private fun String?.cardState(): CardState = when (this) {
    "completed" -> CardState.COMMITTED
    "failed", "declined" -> CardState.FAILED
    else -> CardState.OPEN
}

private fun CardState?.isTerminal(): Boolean =
    this == CardState.COMMITTED || this == CardState.CORRECTED || this == CardState.FAILED

private fun String?.toOutcome(): TurnOutcome? = when (this) {
    "completed" -> TurnOutcome.COMPLETED
    "interrupted", "cancelled" -> TurnOutcome.INTERRUPTED
    "failed" -> TurnOutcome.FAILED
    else -> null
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()

private fun JsonObject.int(name: String): Int? = text(name)?.toIntOrNull()
