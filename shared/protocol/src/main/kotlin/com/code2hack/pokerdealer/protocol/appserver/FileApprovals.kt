package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CardSource
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.FileApprovalDecision
import com.code2hack.pokerdealer.domain.FileApprovalRequest
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

const val FILE_APPROVAL_METHOD = "item/fileChange/requestApproval"

sealed interface FileApprovalParseResult {
    data class Accepted(val request: FileApprovalRequest) : FileApprovalParseResult
    data class Incomplete(val request: FileApprovalRequest) : FileApprovalParseResult
    data class Rejected(val reason: String) : FileApprovalParseResult
}

object FileApprovalProtocol {
    fun parse(
        hostId: String,
        appServerGeneration: Long,
        wire: AppServerRequest,
        reviewCard: Card?,
    ): FileApprovalParseResult {
        if (wire.method != FILE_APPROVAL_METHOD) {
            return FileApprovalParseResult.Rejected("Unsupported server request")
        }
        val params = wire.params as? JsonObject
            ?: return FileApprovalParseResult.Rejected("File approval parameters are malformed")
        val threadId = params.text("threadId")
            ?: return FileApprovalParseResult.Rejected("File approval has no thread")
        val turnId = params.text("turnId")
            ?: return FileApprovalParseResult.Rejected("File approval has no turn")
        val itemId = params.text("itemId")
            ?: return FileApprovalParseResult.Rejected("File approval has no item")
        val startedAtMs = params.long("startedAtMs")
            ?: return FileApprovalParseResult.Rejected("File approval has no start time")
        val reviewComplete = reviewCard?.let {
            it.id == itemId &&
                it.conversationId == "$hostId/$threadId" &&
                it.turnId == turnId &&
                it.source == CardSource.CODEX_FILE_CHANGE &&
                it.contentComplete &&
                it.storageError == null &&
                it.fileChanges.isNotEmpty()
        } == true
        val changes = reviewCard?.fileChanges.takeIf { reviewComplete }.orEmpty()
        val reason = params.nullableText("reason")
        val grantRoot = params.nullableText("grantRoot")
        val request = FileApprovalRequest(
            locator = ServerRequestLocator(hostId, appServerGeneration, wire.id.requestIdKey()),
            thread = CodexThreadLocator(hostId, threadId),
            turnId = turnId,
            itemId = itemId,
            reason = reason,
            grantRoot = grantRoot,
            fileChanges = changes,
            wireFingerprint = fingerprint(
                wire.method,
                threadId,
                turnId,
                itemId,
                reason,
                grantRoot,
                emptyList(),
            ),
            fingerprint = fingerprint(
                wire.method,
                threadId,
                turnId,
                itemId,
                reason,
                grantRoot,
                changes,
            ),
            createdAtMs = startedAtMs,
            reviewComplete = reviewComplete,
        )
        return if (reviewComplete) {
            FileApprovalParseResult.Accepted(request)
        } else {
            FileApprovalParseResult.Incomplete(request)
        }
    }

    fun response(decision: FileApprovalDecision): JsonObject =
        buildJsonObject { put("decision", JsonPrimitive(decision.wireName)) }

    private fun fingerprint(
        method: String,
        threadId: String,
        turnId: String,
        itemId: String,
        reason: String?,
        grantRoot: String?,
        review: List<com.code2hack.pokerdealer.domain.FileChangeContent>,
    ): String = AppServerJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("method", JsonPrimitive(method))
            put("threadId", JsonPrimitive(threadId))
            put("turnId", JsonPrimitive(turnId))
            put("itemId", JsonPrimitive(itemId))
            reason?.let { put("reason", JsonPrimitive(it)) }
            grantRoot?.let { put("grantRoot", JsonPrimitive(it)) }
            put("changes", buildJsonArray {
                review.forEach { change ->
                    add(buildJsonObject {
                        put("path", JsonPrimitive(change.path))
                        put("kind", JsonPrimitive(change.kind))
                        put("diff", JsonPrimitive(change.diff))
                    })
                }
            })
        },
    )
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.nullableText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()
