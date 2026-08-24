package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.ThreadActionState

internal interface DealerThreadStateStore {
    suspend fun read(): Set<CodexThreadLocator>
    suspend fun attach(locator: CodexThreadLocator)
    suspend fun detach(locator: CodexThreadLocator)
    suspend fun purge(locator: CodexThreadLocator)
    suspend fun readActions(): ThreadActionState
    suspend fun writeDraft(locator: CodexThreadLocator, draft: ComposerDraft)
    suspend fun writeReasoningEffort(locator: CodexThreadLocator, effort: String?)
    suspend fun writePendingInput(locator: CodexThreadLocator, pending: PendingThreadInput?)
    suspend fun writePendingInterrupt(locator: CodexThreadLocator, turnId: String?)
}

internal interface DealerRecoveryStateStore {
    suspend fun read(): RestoredDealerState
    suspend fun writeProjection(snapshot: DealerProjectionSnapshot)
    suspend fun writePendingRequests(snapshot: DealerPendingRequestSnapshot)
}
