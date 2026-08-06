package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserInputOption(
    val label: String,
    val description: String,
)

@Serializable
data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UserInputOption>?,
    val isOther: Boolean,
    val isSecret: Boolean,
)

/** The semantic answer state shared while one request is still editable. */
@Serializable
data class UserInputAnswer(
    val selectedOption: String? = null,
    val otherText: String = "",
) {
    init {
        require(selectedOption == null || selectedOption.isNotBlank()) {
            "Selected user-input options must not be blank"
        }
    }

    override fun toString(): String = "UserInputAnswer(selectedOption=$selectedOption, otherText=<redacted>)"
}

@Serializable
data class UserInputAnswerBuffer(
    val revision: Long = 0L,
    val answers: Map<String, UserInputAnswer> = emptyMap(),
) {
    init {
        require(revision >= 0) { "User-input answer revision must not be negative" }
    }

    fun answer(questionId: String): UserInputAnswer = answers[questionId] ?: UserInputAnswer()

    fun activeValue(question: UserInputQuestion): String =
        if (question.options == null) {
            answer(question.id).otherText
        } else {
            answer(question.id).selectedOption
                ?: answer(question.id).otherText.takeIf { question.isOther }
                .orEmpty()
        }

    fun isComplete(request: UserInputRequest): Boolean =
        request.questions.all { activeValue(it).isNotBlank() }

    fun response(request: UserInputRequest): Map<String, List<String>> =
        request.questions.associate { it.id to listOf(activeValue(it)) }

    fun edit(request: UserInputRequest, questionId: String, edit: UserInputAnswerEdit): UserInputAnswerBuffer {
        val question = request.questions.firstOrNull { it.id == questionId }
            ?: throw IllegalArgumentException("Unknown user-input question $questionId")
        val current = answer(questionId)
        val next = when (edit) {
            is UserInputAnswerEdit.SelectOption -> {
                require(question.options?.any { it.label == edit.label } == true) {
                    "Option is not offered by question $questionId"
                }
                current.copy(selectedOption = edit.label)
            }

            UserInputAnswerEdit.SelectOther -> {
                require(question.options != null && question.isOther) {
                    "Question $questionId does not offer Other"
                }
                current.copy(selectedOption = null)
            }

            is UserInputAnswerEdit.SetText -> {
                require(question.options == null || (question.isOther && current.selectedOption == null)) {
                    "Question $questionId is not editing free text"
                }
                current.copy(otherText = edit.value)
            }
        }
        if (next == current) return this
        return copy(revision = revision + 1, answers = answers + (questionId to next))
    }

    fun clearSecretAnswers(request: UserInputRequest): UserInputAnswerBuffer = copy(
        answers = answers - request.questions.filter(UserInputQuestion::isSecret).map(UserInputQuestion::id).toSet(),
    )

    override fun toString(): String = "UserInputAnswerBuffer(revision=$revision, answers=<redacted>)"
}

sealed interface UserInputAnswerEdit {
    data class SelectOption(val label: String) : UserInputAnswerEdit

    data object SelectOther : UserInputAnswerEdit

    data class SetText(val value: String) : UserInputAnswerEdit {
        override fun toString(): String = "SetText(<redacted>)"
    }
}

/** Process-memory answer ownership. It is intentionally not part of recovery snapshots. */
data class UserInputAnswerState(
    val buffers: Map<ServerRequestLocator, UserInputAnswerBuffer> = emptyMap(),
) {
    fun buffer(locator: ServerRequestLocator): UserInputAnswerBuffer =
        buffers[locator] ?: UserInputAnswerBuffer()

    fun receive(
        previousRequests: UserInputRequestState,
        request: UserInputRequest,
        sameIdReissueQualified: Boolean,
    ): UserInputAnswerState {
        if (request.locator in buffers) return this
        val previous = if (sameIdReissueQualified) {
            previousRequests.requests.values.firstOrNull {
                it.locator.hostId == request.locator.hostId &&
                    it.locator.requestId == request.locator.requestId &&
                    it.locator.appServerGeneration != request.locator.appServerGeneration &&
                    it.fingerprint == request.fingerprint &&
                    it.resolution != RequestResolutionState.RESOLVED
            }
        } else {
            null
        }
        val previousBuffer = previous?.locator?.let(buffers::get)
        return copy(
            buffers = buffers + (request.locator to (previousBuffer ?: UserInputAnswerBuffer())),
        )
    }

    fun edit(
        request: UserInputRequest,
        questionId: String,
        edit: UserInputAnswerEdit,
    ): UserInputAnswerState = copy(
        buffers = buffers + (
            request.locator to buffer(request.locator).edit(request, questionId, edit)
        ),
    )

    fun remove(locator: ServerRequestLocator): UserInputAnswerState =
        copy(buffers = buffers - locator)

    fun rekey(old: ServerRequestLocator, current: ServerRequestLocator): UserInputAnswerState {
        val value = buffers[old] ?: return this
        return copy(buffers = (buffers - old) + (current to value))
    }
}

@Serializable
enum class UserInputOutcome {
    ANSWERED,
    NO_ANSWER,
    AUTO_RESOLVED,
}

@Serializable
data class UserInputRequest(
    val locator: ServerRequestLocator,
    val thread: CodexThreadLocator,
    val turnId: String,
    val itemId: String,
    val questions: List<UserInputQuestion>,
    val autoResolutionMs: Long?,
    val receivedAtMs: Long,
    val fingerprint: String,
    val resolution: RequestResolutionState = RequestResolutionState.PENDING,
    val outcome: UserInputOutcome? = null,
    val resolvedElsewhere: Boolean = false,
) {
    val deadlineAtMs: Long?
        get() = autoResolutionMs?.let {
            if (receivedAtMs > Long.MAX_VALUE - it) Long.MAX_VALUE else receivedAtMs + it
        }

    val panelId: String
        get() = "${locator.hostId}/${locator.appServerGeneration}/${locator.requestId}"

    val panelPositionCount: Int
        get() = questions.sumOf { question ->
            question.options?.let { options ->
                (options.size + if (question.isOther) 1 else 0).coerceAtLeast(1)
            } ?: 1
        }
}

fun UserInputRequest.toPokerRequestPanelLayout(): PokerRequestPanelLayout =
    PokerRequestPanelLayout(
        id = panelId,
        positionCount = panelPositionCount,
        questions = questions.map { question ->
            val labels = question.options.orEmpty().map(UserInputOption::label) +
                if (question.options != null && question.isOther) listOf("Other") else emptyList()
            PokerRequestQuestionLayout(
                id = question.id,
                controlCount = labels.size.coerceAtLeast(1),
                optionLabels = labels,
                hasOther = question.options != null && question.isOther,
            )
        },
    )

@Serializable
data class UserInputRequestState(
    val requests: Map<ServerRequestLocator, UserInputRequest> = emptyMap(),
) {
    fun receive(
        request: UserInputRequest,
        sameIdReissueQualified: Boolean,
    ): UserInputRequestState {
        val current = requests[request.locator]
        require(current == null || current.fingerprint == request.fingerprint) {
            "Server request identity was reused with different question scope"
        }
        if (current != null) return this

        val reissued = requests.values.lastOrNull {
            sameIdReissueQualified &&
                it.locator.hostId == request.locator.hostId &&
                it.locator.requestId == request.locator.requestId &&
                it.locator.appServerGeneration != request.locator.appServerGeneration &&
                it.fingerprint == request.fingerprint &&
                it.resolution != RequestResolutionState.RESOLVED
        }
        return copy(
            requests = (reissued?.let { requests - it.locator } ?: requests) + (
                request.locator to request.copy(
                    receivedAtMs = reissued?.receivedAtMs?.let { minOf(it, request.receivedAtMs) }
                        ?: request.receivedAtMs,
                    resolution = RequestResolutionState.PENDING,
                    outcome = null,
                )
            ),
        )
    }

    fun begin(
        locator: ServerRequestLocator,
        outcome: UserInputOutcome,
    ): UserInputRequestState {
        val request = requests[locator] ?: return this
        if (request.resolution != RequestResolutionState.PENDING) return this
        return update(request.copy(resolution = RequestResolutionState.RESPONDING, outcome = outcome))
    }

    fun unknown(locator: ServerRequestLocator): UserInputRequestState {
        val request = requests[locator] ?: return this
        if (request.resolution == RequestResolutionState.RESOLVED) return this
        return update(request.copy(resolution = RequestResolutionState.UNKNOWN))
    }

    fun resolved(
        hostId: String,
        appServerGeneration: Long,
        requestId: String,
        threadId: String,
    ): UserInputRequestState {
        val request = requests.values.lastOrNull {
            it.locator.hostId == hostId &&
                it.locator.appServerGeneration == appServerGeneration &&
                it.locator.requestId == requestId &&
                it.thread.threadId == threadId &&
                it.resolution != RequestResolutionState.RESOLVED
        } ?: return this
        return update(
            request.copy(
                resolution = RequestResolutionState.RESOLVED,
                resolvedElsewhere = request.resolution != RequestResolutionState.RESPONDING,
                outcome = request.outcome.takeIf {
                    request.resolution == RequestResolutionState.RESPONDING
                },
            ),
        )
    }

    fun connectionLost(hostId: String, appServerGeneration: Long): UserInputRequestState =
        copy(
            requests = requests.mapValues { (_, request) ->
                if (request.locator.hostId == hostId &&
                    request.locator.appServerGeneration == appServerGeneration &&
                    request.resolution in ACTIVE_STATES
                ) {
                    request.copy(resolution = RequestResolutionState.UNKNOWN)
                } else {
                    request
                }
            },
        )

    fun turnSettled(thread: CodexThreadLocator, turnId: String): UserInputRequestState =
        copy(
            requests = requests.mapValues { (_, request) ->
                if (request.thread == thread &&
                    request.turnId == turnId &&
                    request.resolution != RequestResolutionState.RESOLVED
                ) {
                    request.copy(
                        resolution = RequestResolutionState.RESOLVED,
                        resolvedElsewhere = request.resolution != RequestResolutionState.RESPONDING,
                        outcome = request.outcome.takeIf {
                            request.resolution == RequestResolutionState.RESPONDING
                        },
                    )
                } else {
                    request
                }
            },
        )

    fun unresolved(hostId: String? = null): List<UserInputRequest> =
        requests.values.filter {
            it.resolution != RequestResolutionState.RESOLVED &&
                (hostId == null || it.locator.hostId == hostId)
        }

    fun unresolvedThreads(): Set<CodexThreadLocator> =
        unresolved().mapTo(mutableSetOf(), UserInputRequest::thread)

    private fun update(request: UserInputRequest): UserInputRequestState =
        copy(requests = requests + (request.locator to request))

    private companion object {
        val ACTIVE_STATES = setOf(RequestResolutionState.PENDING, RequestResolutionState.RESPONDING)
    }
}
