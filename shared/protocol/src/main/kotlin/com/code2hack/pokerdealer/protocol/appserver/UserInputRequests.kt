package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ServerRequestLocator
import com.code2hack.pokerdealer.domain.UserInputOption
import com.code2hack.pokerdealer.domain.UserInputQuestion
import com.code2hack.pokerdealer.domain.UserInputRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

const val USER_INPUT_REQUEST_METHOD = "item/tool/requestUserInput"

sealed interface UserInputParseResult {
    data class Accepted(val request: UserInputRequest) : UserInputParseResult
    data class Rejected(val reason: String) : UserInputParseResult
}

object UserInputProtocol {
    fun parse(
        hostId: String,
        appServerGeneration: Long,
        wire: AppServerRequest,
        receivedAtMs: Long,
    ): UserInputParseResult {
        if (wire.method != USER_INPUT_REQUEST_METHOD) {
            return UserInputParseResult.Rejected("Unsupported server request")
        }
        val params = wire.params as? JsonObject
            ?: return UserInputParseResult.Rejected("User-input parameters are malformed")
        val threadId = params.text("threadId")
            ?: return UserInputParseResult.Rejected("User-input request has no thread")
        val turnId = params.text("turnId")
            ?: return UserInputParseResult.Rejected("User-input request has no turn")
        val itemId = params.text("itemId")
            ?: return UserInputParseResult.Rejected("User-input request has no item")
        val autoResolutionMs = when (val value = params["autoResolutionMs"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)
                ?.contentOrNull
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?: return UserInputParseResult.Rejected("User-input timeout is malformed")
            else -> return UserInputParseResult.Rejected("User-input timeout is malformed")
        }
        val rawQuestions = params["questions"] as? JsonArray
            ?: return UserInputParseResult.Rejected("User-input questions are malformed")
        if (rawQuestions.isEmpty()) {
            return UserInputParseResult.Rejected("User-input request has no questions")
        }

        val ids = mutableSetOf<String>()
        val questions = mutableListOf<UserInputQuestion>()
        rawQuestions.forEach { rawQuestion ->
            val question = rawQuestion as? JsonObject
                ?: return UserInputParseResult.Rejected("User-input question is malformed")
            val id = question.text("id")?.takeIf(String::isNotBlank)
                ?: return UserInputParseResult.Rejected("User-input question has no id")
            if (!ids.add(id)) {
                return UserInputParseResult.Rejected("User-input question id is duplicated")
            }
            val header = question.text("header")?.takeIf(String::isNotBlank)
                ?: return UserInputParseResult.Rejected("User-input question has no header")
            val prompt = question.text("question")?.takeIf(String::isNotBlank)
                ?: return UserInputParseResult.Rejected("User-input question has no prompt")
            val isOther = question.optionalBoolean("isOther")
                ?: return UserInputParseResult.Rejected("User-input isOther flag is malformed")
            val isSecret = question.optionalBoolean("isSecret")
                ?: return UserInputParseResult.Rejected("User-input isSecret flag is malformed")
            val options = when (val value = question["options"]) {
                null, JsonNull -> null
                is JsonArray -> {
                    val labels = mutableSetOf<String>()
                    value.map { rawOption ->
                        val option = rawOption as? JsonObject
                            ?: return UserInputParseResult.Rejected("User-input option is malformed")
                        val label = option.text("label")?.takeIf(String::isNotBlank)
                            ?: return UserInputParseResult.Rejected("User-input option has no label")
                        val description = option.text("description")
                            ?: return UserInputParseResult.Rejected("User-input option has no description")
                        if (!labels.add(label)) {
                            return UserInputParseResult.Rejected("User-input option label is duplicated")
                        }
                        UserInputOption(label, description)
                    }
                }
                else -> return UserInputParseResult.Rejected("User-input options are malformed")
            }
            if (options?.isEmpty() == true && !isOther) {
                return UserInputParseResult.Rejected("User-input question has no answerable option")
            }
            questions += UserInputQuestion(id, header, prompt, options, isOther, isSecret)
        }

        return UserInputParseResult.Accepted(
            UserInputRequest(
                locator = ServerRequestLocator(hostId, appServerGeneration, wire.id.requestIdKey()),
                thread = CodexThreadLocator(hostId, threadId),
                turnId = turnId,
                itemId = itemId,
                questions = questions,
                autoResolutionMs = autoResolutionMs,
                receivedAtMs = receivedAtMs,
                fingerprint = fingerprint(wire.method, threadId, turnId, itemId, questions, autoResolutionMs),
            ),
        )
    }

    fun response(
        request: UserInputRequest,
        answers: Map<String, List<String>>,
    ): JsonObject {
        if (answers.isNotEmpty()) {
            require(answers.keys == request.questions.mapTo(mutableSetOf(), UserInputQuestion::id)) {
                "Every question must be answered exactly once"
            }
            request.questions.forEach { question ->
                val values = answers.getValue(question.id)
                require(values.size == 1 && values.single().isNotBlank()) {
                    "Answer for ${question.id} must contain exactly one non-blank value"
                }
                val labels = question.options?.mapTo(mutableSetOf(), UserInputOption::label)
                require(labels == null || values.all { it in labels } || question.isOther) {
                    "Answer for ${question.id} is not an offered option"
                }
            }
        }
        return buildJsonObject {
            put("answers", buildJsonObject {
                answers.forEach { (id, values) ->
                    put(id, buildJsonObject {
                        put("answers", buildJsonArray {
                            values.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
        }
    }

    private fun fingerprint(
        method: String,
        threadId: String,
        turnId: String,
        itemId: String,
        questions: List<UserInputQuestion>,
        autoResolutionMs: Long?,
    ): String = AppServerJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("method", JsonPrimitive(method))
            put("threadId", JsonPrimitive(threadId))
            put("turnId", JsonPrimitive(turnId))
            put("itemId", JsonPrimitive(itemId))
            autoResolutionMs?.let { put("autoResolutionMs", JsonPrimitive(it)) }
            put("questions", buildJsonArray {
                questions.forEach { question ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(question.id))
                        put("header", JsonPrimitive(question.header))
                        put("question", JsonPrimitive(question.question))
                        put("isOther", JsonPrimitive(question.isOther))
                        put("isSecret", JsonPrimitive(question.isSecret))
                        question.options?.let { options ->
                            put("options", buildJsonArray {
                                options.forEach { option ->
                                    add(buildJsonObject {
                                        put("label", JsonPrimitive(option.label))
                                        put("description", JsonPrimitive(option.description))
                                    })
                                }
                            })
                        }
                    })
                }
            })
        },
    )
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return false
    return (value as? JsonPrimitive)?.booleanOrNull
}
