package com.code2hack.dealer

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.CommandApprovalDecision
import com.code2hack.pokerdealer.domain.ComposerAction
import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.DeliveryState
import com.code2hack.pokerdealer.domain.PendingThreadInput
import com.code2hack.pokerdealer.domain.PermissionPreset
import com.code2hack.pokerdealer.domain.ThreadActionState
import com.code2hack.pokerdealer.domain.ThreadStartSelection
import com.code2hack.pokerdealer.domain.ThreadWorkState
import com.code2hack.pokerdealer.protocol.appserver.AppServerThreadProjection
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import com.code2hack.pokerdealer.protocol.appserver.InitializedHostSessionConnector
import com.code2hack.pokerdealer.protocol.appserver.RetainedCardStore
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real-device acceptance harness for the retained Dealer ↔ Codex slice.
 *
 * This test is skipped unless `-e pdRealDevice true` is supplied. It never embeds credentials.
 * For development devices upgraded from the Legacy debug APK it reuses the already-encrypted SSH
 * credential blob in the app sandbox, rewrites only the generic profile metadata through the
 * successor store, and keeps the private key plaintext inside this process only long enough to
 * re-encrypt it through the production Android Keystore path.
 */
@RunWith(AndroidJUnit4::class)
class DealerRealDeviceAcceptanceTest {
    @Test
    fun dealerCodexLiveAcceptance() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("real-device acceptance is opt-in", args.getString("pdRealDevice") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostId = requireArg("pdHostId")
        val tailnetHost = requireArg("pdTailnetHost")
        val sshUser = requireArg("pdSshUser")
        val workingDirectory = requireArg("pdWorkingDirectory")
        val profileStore = DealerHostConnectionProfileStore(context)
        val credentials = readExistingCredentials(profileStore, hostId)
        try {
            val aliases = credentials.second.decodeToString()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringBefore(' ') }
                .distinct()
                .toList()
            evidence("knownHostAliases=${aliases.joinToString(",")}")
            val pinSummaries = credentials.second.decodeToString()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val fields = line.split(Regex("\\s+"))
                    if (fields.size < 3) return@mapNotNull null
                    val key = runCatching { Base64.getDecoder().decode(fields[2]) }.getOrNull()
                        ?: return@mapNotNull null
                    val digest = MessageDigest.getInstance("SHA-256").digest(key)
                    val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(digest)
                    "${fields[1]}:SHA256:$fingerprint"
                }
                .toList()
            evidence("knownHostPins=${pinSummaries.joinToString(",")}")
            evidence("identityAlgorithms=${inspectIdentityAlgorithms(credentials.first).joinToString(",")}")
            if (args.getString("pdInspectOnly") == "true") return@runBlocking
            val config = DealerHostConnectionConfig(
                hostId = hostId,
                displayName = "Codex validation host",
                lanHost = "",
                tailnetHost = tailnetHost,
                sshUser = sshUser,
            )
            profileStore.save(config, credentials.first, credentials.second)
            HostConnectionIntentDataStore(context).writeEnabledHostIds(setOf(hostId))
            val restored = profileStore.load(hostId)
            assertEquals(config, restored.config)
            assertTrue(credentials.first.contentEquals(restored.privateKey))
            assertTrue(credentials.second.contentEquals(restored.knownHosts))
            restored.privateKey.fill(0)
            restored.knownHosts.fill(0)
            evidence("profile host=$hostId configured=true credentialRoundTrip=true")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val tailnet = EmbeddedTailnetController(context, scope)
            val memoryStore = LiveThreadStore()
            val recovery = LiveRecoveryStore()
            val cardsRoot = File(context.cacheDir, "dealer-real-device-${System.nanoTime()}")
            val intentStore = HostConnectionIntentDataStore(context)
            val hostSessions = HostSessionManager(
                hostIds = setOf(hostId),
                intentStore = intentStore,
                connector = InitializedHostSessionConnector(
                    DealerHostSessionFactory(profileStore, tailnet)::create,
                ),
                scope = scope,
            )
            val core = DealerCore(
                hostSessions = hostSessions,
                threadStore = memoryStore,
                recoveryStore = recovery,
                retainedCardStore = RetainedCardStore(cardsRoot),
                scope = scope,
            )
            val createdThreads = mutableListOf<CodexThreadLocator>()
            try {
                tailnet.start()
                val tailnetStatus = awaitTailnet(tailnet)
                evidence(
                    "tailnet state=${tailnetStatus.state} node=${tailnetStatus.nodeName.orEmpty()} " +
                        "path=${tailnetStatus.path.orEmpty()} relay=${tailnetStatus.relay.orEmpty()}",
                )

                core.start()
                awaitHostConnected(core, hostId)
                val connected = checkNotNull(core.state.value.hostSessions[hostId])
                evidence(
                    "codex connected=true route=${connected.route} version=${connected.appServerVersion.orEmpty()}",
                )
                assertEquals(HostSessionStatus.CONNECTED, connected.status)

                val sendThread = createDisposableThread(core, hostId, workingDirectory)
                createdThreads += sendThread
                val sendPrompt = "Reply with exactly DEALER_REAL_DEVICE_OK."
                assertTrue(core.editDraft(sendThread, ComposerDraft.fromText(sendPrompt)))
                val sendRevision = core.state.value.threadActions.composerDraft(sendThread).revision
                val sendOutcome = core.submitDraft(
                    sendThread,
                    expectedDraftRevision = sendRevision,
                    expectedAction = ComposerAction.START,
                )
                assertEquals(DealerMutationOutcome.ACCEPTED, sendOutcome)
                val acceptedUserCard = core.state.value.cards.single {
                    it.conversationId == conversationId(sendThread) &&
                        it.fullText == sendPrompt &&
                        it.delivery == DeliveryState.ACCEPTED
                }
                val clientId = acceptedUserCard.id
                val sendTurnId = checkNotNull(core.state.value.threads[sendThread]?.activeTurnId)
                assertTrue(memoryStore.pendingInputs[sendThread] == null)
                evidence(
                    "send outcome=$sendOutcome clientId=$clientId turnId=$sendTurnId " +
                        "draftCleared=${core.state.value.threadActions.composerDraft(sendThread).isEmpty}",
                )

                val oldSession = checkNotNull(hostSessions.connectedSession(hostId)?.appServer)
                oldSession.close()
                awaitCondition(RECONNECT_TIMEOUT_MS, "replacement app-server session did not connect") {
                    val current = hostSessions.connectedSession(hostId)?.appServer
                    current != null && current !== oldSession &&
                        core.state.value.hostSessions[hostId]?.status == HostSessionStatus.CONNECTED
                }
                evidence("reconnect replacementSession=true")

                awaitCondition(TURN_TIMEOUT_MS, "send thread did not settle after reconnect") {
                    core.readThread(sendThread)
                    core.state.value.threads[sendThread]?.workState == ThreadWorkState.READY &&
                        core.state.value.cards.any {
                            it.conversationId == conversationId(sendThread) &&
                                it.role.name == "AGENT" && it.fullText.isNotBlank()
                        }
                }
                core.readThread(sendThread)
                val authoritativeRead = checkNotNull(hostSessions.connectedSession(hostId)?.appServer)
                    .threadRead(sendThread.threadId)
                val matchingMessages = AppServerThreadProjection.countUserClientId(authoritativeRead, clientId)
                assertEquals(1, matchingMessages)
                assertEquals(
                    1,
                    core.state.value.cards.count {
                        it.conversationId == conversationId(sendThread) && it.id == clientId
                    },
                )
                val delivered = core.state.value.cards.single {
                    it.conversationId == conversationId(sendThread) && it.id == clientId
                }
                assertEquals(DeliveryState.DELIVERED, delivered.delivery)
                val streamedAgentCards = core.state.value.cards.count {
                    it.conversationId == conversationId(sendThread) &&
                        it.role.name == "AGENT" && it.fullText.isNotBlank()
                }
                assertTrue(streamedAgentCards > 0)
                evidence(
                    "sendReconnect authoritativeClientMatches=$matchingMessages stateCardMatches=1 " +
                        "delivery=${delivered.delivery} streamedAgentCards=$streamedAgentCards",
                )

                val controlThread = createDisposableThread(core, hostId, workingDirectory)
                createdThreads += controlThread
                val longPrompt =
                    "Write a detailed 1200-word explanation of Unix history. Keep the turn active until the full response is complete."
                assertTrue(core.editDraft(controlThread, ComposerDraft.fromText(longPrompt)))
                val startOutcome = core.submitDraft(controlThread, expectedAction = ComposerAction.START)
                assertEquals(DealerMutationOutcome.ACCEPTED, startOutcome)
                val activeTurnId = checkNotNull(core.state.value.threads[controlThread]?.activeTurnId)

                val steerPrompt = "Instead, reply with exactly DEALER_STEER_OK."
                assertTrue(core.editDraft(controlThread, ComposerDraft.fromText(steerPrompt)))
                val staleSteer = core.submitDraft(
                    controlThread,
                    expectedAction = ComposerAction.STEER,
                    expectedTurnId = "stale-$activeTurnId",
                )
                assertEquals(DealerMutationOutcome.REJECTED, staleSteer)
                val steerOutcome = core.submitDraft(
                    controlThread,
                    expectedAction = ComposerAction.STEER,
                    expectedTurnId = activeTurnId,
                )
                assertEquals(DealerMutationOutcome.ACCEPTED, steerOutcome)
                evidence("steer stale=$staleSteer live=$steerOutcome turnId=$activeTurnId")

                val staleInterrupt = core.interrupt(controlThread, expectedTurnId = "stale-$activeTurnId")
                assertEquals(DealerMutationOutcome.REJECTED, staleInterrupt)
                val interruptOutcome = core.interrupt(controlThread, expectedTurnId = activeTurnId)
                assertEquals(DealerMutationOutcome.ACCEPTED, interruptOutcome)
                evidence("interrupt stale=$staleInterrupt live=$interruptOutcome turnId=$activeTurnId")
                awaitCondition(TURN_TIMEOUT_MS, "interrupted thread did not settle") {
                    core.readThread(controlThread)
                    core.state.value.threads[controlThread]?.workState == ThreadWorkState.READY
                }

                val approvalThread = core.createThread(
                    hostId,
                    ThreadStartSelection(
                        workingDirectory = workingDirectory,
                        permissionPreset = PermissionPreset.ASK_ON_PHONE,
                    ),
                )
                if (approvalThread == null) {
                    limitation("command approval thread could not be created with ASK_ON_PHONE: ${core.state.value.error}")
                } else {
                    createdThreads += approvalThread
                    val approvalPrompt = "Use the shell to run exactly `pwd`, then reply with exactly DEALER_COMMAND_OK."
                    assertTrue(core.editDraft(approvalThread, ComposerDraft.fromText(approvalPrompt)))
                    val approvalStart = core.submitDraft(approvalThread, expectedAction = ComposerAction.START)
                    assertEquals(DealerMutationOutcome.ACCEPTED, approvalStart)
                    val request = awaitOptional(COMMAND_REQUEST_TIMEOUT_MS) {
                        core.state.value.commandApprovals.unresolved(hostId).firstOrNull()
                    }
                    if (request == null) {
                        limitation("safe pwd command approval was not elicited on live app-server")
                    } else {
                        assertTrue(request.locator.appServerGeneration > 0)
                        assertEquals(approvalThread, request.thread)
                        if (CommandApprovalDecision.ACCEPT in request.offeredDecisions) {
                            val approvalOutcome = core.resolveCommandApproval(
                                request.locator,
                                CommandApprovalDecision.ACCEPT,
                            )
                            assertEquals(DealerMutationOutcome.ACCEPTED, approvalOutcome)
                            evidence(
                                "commandApproval outcome=$approvalOutcome generation=${request.locator.appServerGeneration} " +
                                    "requestId=${request.locator.requestId}",
                            )
                        } else {
                            limitation("live command request did not offer ACCEPT")
                        }
                    }
                }

                val version = core.state.value.hostSessions[hostId]?.appServerVersion
                if (version != "0.146.0") {
                    limitation(
                        "structured user-input live handling is qualified only for 0.146.0; connected app-server=${version.orEmpty()}",
                    )
                }
                limitation("file approval was not deliberately elicited; no source/workspace file mutation was manufactured")
            } finally {
                createdThreads.asReversed().forEach { locator ->
                    runCatching { core.deleteThread(locator) }
                }
                runCatching { core.close() }
                runCatching { tailnet.stop() }
                scope.cancel()
                cardsRoot.deleteRecursively()
            }
        } finally {
            credentials.first.fill(0)
            credentials.second.fill(0)
        }
    }

    private suspend fun createDisposableThread(
        core: DealerCore,
        hostId: String,
        workingDirectory: String,
    ): CodexThreadLocator {
        val locator = core.createThread(hostId, ThreadStartSelection(workingDirectory = workingDirectory))
        assertNotNull("unable to create disposable live thread: ${core.state.value.error}", locator)
        val created = checkNotNull(locator)
        assertTrue(core.state.value.threadAttachments.hasDealerClaim(created))
        assertEquals(ThreadWorkState.READY, core.state.value.threads[created]?.workState)
        evidence("thread created=${created.threadId} control=Dealer workState=READY")
        return created
    }

    private suspend fun awaitTailnet(controller: EmbeddedTailnetController): EmbeddedTailnetStatus {
        return withTimeout(TAILNET_TIMEOUT_MS) {
            while (true) {
                val status = controller.status.value
                when (status.state) {
                    EmbeddedTailnetConnectionState.CONNECTED,
                    EmbeddedTailnetConnectionState.DEGRADED,
                    -> return@withTimeout status

                    EmbeddedTailnetConnectionState.LOGIN_REQUIRED ->
                        error("embedded tailnet requires user login")

                    EmbeddedTailnetConnectionState.ERROR ->
                        error("embedded tailnet failed: ${status.error}")

                    else -> delay(250)
                }
            }
            error("unreachable")
        }
    }

    private suspend fun awaitHostConnected(core: DealerCore, hostId: String) {
        var last: String? = null
        try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                while (core.state.value.hostSessions[hostId]?.status != HostSessionStatus.CONNECTED) {
                    val state = core.state.value.hostSessions[hostId]
                    val rendered = state?.toString() ?: "missing"
                    if (rendered != last) {
                        last = rendered
                        evidence("hostSession $rendered")
                    }
                    if (state?.status == HostSessionStatus.ERROR) {
                        throw AssertionError("Codex host connection entered terminal ERROR: $state")
                    }
                    delay(250)
                }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val state = core.state.value.hostSessions[hostId]
            evidence("hostConnectTimeout state=$state coreError=${core.state.value.error.orEmpty()}")
            throw AssertionError(
                "Codex host did not connect; state=$state coreError=${core.state.value.error.orEmpty()}",
                failure,
            )
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long, message: String, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(250)
        }
        assertTrue(message, condition())
    }

    private suspend fun <T> awaitOptional(timeoutMs: Long, value: suspend () -> T?): T? =
        runCatching {
            withTimeout(timeoutMs) {
                while (true) {
                    value()?.let { return@withTimeout it }
                    delay(250)
                }
                null
            }
        }.getOrNull()

    private fun inspectIdentityAlgorithms(privateKey: ByteArray): List<String> {
        val jschClass = Class.forName("com.jcraft.jsch.JSch")
        val jsch = jschClass.getDeclaredConstructor().newInstance()
        jschClass.getMethod(
            "addIdentity",
            String::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
        ).invoke(jsch, "validation-inspect", privateKey, null, null)
        val repository = jschClass.getMethod("getIdentityRepository").invoke(jsch)
        val identities = Class.forName("com.jcraft.jsch.IdentityRepository")
            .getMethod("getIdentities")
            .invoke(repository) as java.util.Vector<*>
        val identityClass = Class.forName("com.jcraft.jsch.Identity")
        val algorithm = identityClass.getMethod("getAlgName")
        return identities.mapNotNull { identity ->
            identity?.let { algorithm.invoke(it) as? String }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readExistingCredentials(
        store: DealerHostConnectionProfileStore,
        hostId: String,
    ): Pair<ByteArray, ByteArray> {
        val method = DealerHostConnectionProfileStore::class.java
            .getDeclaredMethod("readCredentials", String::class.java)
            .apply { isAccessible = true }
        return method.invoke(store, hostId) as Pair<ByteArray, ByteArray>
    }

    private fun requireArg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf(String::isNotBlank)
            ?: error("missing instrumentation argument $name")

    private fun conversationId(locator: CodexThreadLocator): String =
        "${locator.hostId}/${locator.threadId}"

    private fun evidence(message: String) {
        Log.i(TAG, "EVIDENCE $message")
    }

    private fun limitation(message: String) {
        Log.w(TAG, "LIMITATION $message")
    }

    private companion object {
        const val TAG = "PD_REAL_DEVICE"
        const val TAILNET_TIMEOUT_MS = 45_000L
        const val CONNECTION_TIMEOUT_MS = 90_000L
        const val RECONNECT_TIMEOUT_MS = 90_000L
        const val TURN_TIMEOUT_MS = 180_000L
        const val COMMAND_REQUEST_TIMEOUT_MS = 30_000L
    }
}

private class LiveThreadStore : DealerThreadStateStore {
    private val attachments = linkedSetOf<CodexThreadLocator>()
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
        actions = if (pending == null) {
            actions.pendingInputs[locator]?.let { current ->
                runCatching { actions.inputAccepted(locator, current.clientId) }.getOrDefault(actions)
            } ?: actions
        } else {
            actions.copy(pendingInputs = actions.pendingInputs + (locator to pending))
        }
    }

    override suspend fun writePendingInterrupt(locator: CodexThreadLocator, turnId: String?) {
        pendingInterrupts[locator] = turnId
        actions = actions.copy(
            pendingInterrupts = if (turnId == null) {
                actions.pendingInterrupts - locator
            } else {
                actions.pendingInterrupts + (locator to turnId)
            },
        )
    }
}

private class LiveRecoveryStore : DealerRecoveryStateStore {
    private var restored = RestoredDealerState(
        projection = DealerProjectionSnapshot(),
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
