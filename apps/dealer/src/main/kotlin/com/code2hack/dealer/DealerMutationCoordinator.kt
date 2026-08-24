package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.Card
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.AppServerPhotoAsset
import com.code2hack.pokerdealer.protocol.appserver.AppServerTurnInput
import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.JsonRpcRemoteException
import com.code2hack.pokerdealer.protocol.appserver.M1TurnInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

enum class DealerMutationOutcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN,
}

/** Preserves Legacy Send/Steer/Interrupt fencing and no-blind-replay behavior. */
internal class DealerMutationCoordinator(
    private val state: MutableStateFlow<DealerCoreState>,
    private val store: DealerThreadStateStore,
    private val appServer: (String) -> CodexAppServerSession?,
    private val resolvePhoto: suspend (String) -> AppServerPhotoAsset? = { null },
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
) {
    private val persistenceMutex = Mutex()

    suspend fun submitDraft(
        locator: CodexThreadLocator,
        expectedDraftRevision: Long? = null,
        expectedAction: ComposerAction? = null,
        expectedTurnId: String? = null,
    ): DealerMutationOutcome {
        val before = state.value
        val thread = before.threads[locator]
        val draft = before.threadActions.composerDraft(locator)
        val session = appServer(locator.hostId) ?: return reject("Connect ${locator.hostId} before sending")
        if (expectedDraftRevision != null && draft.revision != expectedDraftRevision) {
            return reject("Composer draft revision is stale")
        }
        val clientId = newClientId()
        val (actions, pending) = try {
            before.threadActions.beginInput(
                locator = locator,
                workState = thread?.workState,
                activeTurnId = thread?.activeTurnId,
                hasDealerClaim = before.threadAttachments.hasDealerClaim(locator),
                clientId = clientId,
            )
        } catch (failure: IllegalArgumentException) {
            return reject(failure.message)
        }
        if (expectedAction != null && pending.action != expectedAction ||
            expectedTurnId != null && pending.expectedTurnId != expectedTurnId
        ) {
            return reject("Primary semantic action is stale")
        }

        val conversationId = "${locator.hostId}/${locator.threadId}"
        val sequence = before.cards
            .filter { it.conversationId == conversationId }
            .maxOfOrNull(Card::sequence)
            ?.plus(1)
            ?: 1L
        val pendingCard = M1TurnInput(pending.draftText, locator.threadId, clientId)
            .pendingUserCard(conversationId, sequence)
        state.update {
            it.copy(
                threadActions = actions,
                cards = it.cards.filterNot { card -> card.id == clientId } + pendingCard,
                error = null,
            )
        }

        try {
            persistenceMutex.withLock { store.writePendingInput(locator, pending) }
        } catch (failure: Throwable) {
            state.update { current ->
                val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                current.copy(
                    threadActions = if (matching) current.threadActions.inputRejected(locator, clientId)
                    else current.threadActions,
                    cards = current.cards.updateDelivery(clientId, DeliveryState.REJECTED),
                    error = "Unable to persist the pending input; nothing was sent: ${failure.message}",
                )
            }
            return DealerMutationOutcome.REJECTED
        }

        return try {
            val input = AppServerTurnInput.fromDraft(pending.draft, resolvePhoto)
            val reasoningEffort = actions.pendingReasoningEfforts[locator]
            val response = when (pending.action) {
                ComposerAction.START -> session.turnStart(
                    locator.threadId,
                    input,
                    clientId,
                    effort = reasoningEffort,
                )
                ComposerAction.STEER -> session.turnSteer(
                    locator.threadId,
                    checkNotNull(pending.expectedTurnId),
                    input,
                    clientId,
                )
                ComposerAction.BLOCKED -> error("Blocked input cannot be submitted")
            }
            val turnId = response.turnIdFor(pending.action)
                ?: error("${pending.action.label} response did not include a turn ID")
            require(pending.expectedTurnId == null || pending.expectedTurnId == turnId) {
                "Steer response did not match the expected active turn"
            }

            var clearAcceptedDraft = false
            state.update { current ->
                val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                clearAcceptedDraft = matching
                current.copy(
                    threadActions = if (matching) current.threadActions.inputAccepted(locator, clientId)
                    else current.threadActions,
                    cards = current.cards.updateDelivery(clientId, DeliveryState.ACCEPTED),
                    threads = current.threads[locator]?.let { row ->
                        current.threads + (
                            locator to row.copy(
                                status = "active",
                                workState = ThreadWorkState.BUSY,
                                activeTurnId = turnId,
                            )
                        )
                    } ?: current.threads,
                    error = null,
                )
            }
            if (clearAcceptedDraft) {
                val retainedDraft = state.value.threadActions.composerDraft(locator)
                runCatching {
                    persistenceMutex.withLock {
                        store.writeDraft(locator, retainedDraft)
                        store.writePendingInput(locator, null)
                        store.writeReasoningEffort(
                            locator,
                            state.value.threadActions.pendingReasoningEfforts[locator],
                        )
                    }
                }.onFailure { failure ->
                    state.update {
                        it.copy(error = "Input was accepted, but local cleanup failed: ${failure.message}")
                    }
                }
            }
            DealerMutationOutcome.ACCEPTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: JsonRpcRemoteException) {
            val cleanupFailure = runCatching {
                persistenceMutex.withLock { store.writePendingInput(locator, null) }
            }.exceptionOrNull()
            state.update { current ->
                val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                current.copy(
                    threadActions = when {
                        matching && cleanupFailure == null -> current.threadActions.inputRejected(locator, clientId)
                        matching -> current.threadActions.inputUncertain(locator, clientId)
                        else -> current.threadActions
                    },
                    cards = current.cards.updateDelivery(clientId, DeliveryState.REJECTED),
                    error = cleanupFailure?.let {
                        "${rejected.message}; local action lock cleanup failed: ${it.message}"
                    } ?: rejected.message,
                )
            }
            if (cleanupFailure == null) DealerMutationOutcome.REJECTED else DealerMutationOutcome.UNKNOWN
        } catch (failure: Throwable) {
            state.update { current ->
                val matching = current.threadActions.pendingInputs[locator]?.clientId == clientId
                current.copy(
                    threadActions = if (matching) current.threadActions.inputUncertain(locator, clientId)
                    else current.threadActions,
                    cards = current.cards.updateDelivery(clientId, DeliveryState.UNKNOWN),
                    error = "${failure.message ?: failure::class.java.simpleName}; input was not replayed",
                )
            }
            DealerMutationOutcome.UNKNOWN
        }
    }

    suspend fun interrupt(
        locator: CodexThreadLocator,
        expectedTurnId: String? = null,
    ): DealerMutationOutcome {
        val before = state.value
        val currentTurnId = before.threads[locator]?.activeTurnId
        if (expectedTurnId != null && expectedTurnId != currentTurnId) {
            return reject("Interrupt turn target is stale")
        }
        val session = appServer(locator.hostId) ?: return reject("Connect ${locator.hostId} before interrupting")
        val (actions, turnId) = try {
            before.threadActions.beginInterrupt(
                locator,
                currentTurnId,
                before.threadAttachments.hasDealerClaim(locator),
            )
        } catch (failure: IllegalArgumentException) {
            return reject(failure.message)
        }
        state.update { it.copy(threadActions = actions, error = null) }
        try {
            persistenceMutex.withLock { store.writePendingInterrupt(locator, turnId) }
        } catch (failure: Throwable) {
            state.update {
                it.copy(
                    threadActions = it.threadActions.reconcileInterrupt(locator, null),
                    error = "Unable to persist Interrupt; nothing was sent: ${failure.message}",
                )
            }
            return DealerMutationOutcome.REJECTED
        }

        return try {
            session.turnInterrupt(locator.threadId, turnId)
            DealerMutationOutcome.ACCEPTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: JsonRpcRemoteException) {
            val cleanupFailure = runCatching {
                persistenceMutex.withLock { store.writePendingInterrupt(locator, null) }
            }.exceptionOrNull()
            state.update {
                it.copy(
                    threadActions = if (cleanupFailure == null) {
                        it.threadActions.reconcileInterrupt(locator, null)
                    } else {
                        it.threadActions
                    },
                    error = cleanupFailure?.let { failure ->
                        "${rejected.message}; local Interrupt lock cleanup failed: ${failure.message}"
                    } ?: rejected.message,
                )
            }
            if (cleanupFailure == null) DealerMutationOutcome.REJECTED else DealerMutationOutcome.UNKNOWN
        } catch (failure: Throwable) {
            state.update {
                it.copy(error = "${failure.message ?: failure::class.java.simpleName}; interrupt was not replayed")
            }
            DealerMutationOutcome.UNKNOWN
        }
    }

    private fun reject(message: String?): DealerMutationOutcome {
        state.update { it.copy(error = message ?: "Mutation rejected") }
        return DealerMutationOutcome.REJECTED
    }
}

private fun JsonObject.turnIdFor(action: ComposerAction): String? = when (action) {
    ComposerAction.START -> ((this["turn"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
    ComposerAction.STEER -> (this["turnId"] as? JsonPrimitive)?.contentOrNull
    ComposerAction.BLOCKED -> null
}

private fun List<Card>.updateDelivery(clientId: String, delivery: DeliveryState): List<Card> =
    map { card ->
        if (card.id == clientId && card.delivery != null) {
            card.copy(delivery = delivery, revision = card.revision + 1)
        } else {
            card
        }
    }
