package com.code2hack.poker

import com.code2hack.pokerdealer.domain.ComposerDraft
import com.code2hack.pokerdealer.domain.ComposerElement
import com.code2hack.pokerdealer.domain.CodexThreadLocator
import com.code2hack.pokerdealer.domain.PokerComposerLayout
import com.code2hack.pokerdealer.domain.PokerInteraction
import com.code2hack.pokerdealer.domain.PokerInteractionPhase
import com.code2hack.pokerdealer.domain.PokerNavigationReducer
import com.code2hack.pokerdealer.domain.PokerOperation
import com.code2hack.pokerdealer.domain.PokerPrimaryAction
import com.code2hack.pokerdealer.domain.PokerWheelAction
import com.code2hack.pokerdealer.domain.PokerWheelContext
import com.code2hack.pokerdealer.domain.PokerWheelSelection
import com.code2hack.pokerdealer.domain.PokerCardLayout
import com.code2hack.pokerdealer.domain.PokerPileLayout
import com.code2hack.pokerdealer.domain.ThreadWorkEvidence
import com.code2hack.pokerdealer.protocol.ComposerDraftProjection
import com.code2hack.pokerdealer.protocol.PhotoAssetCodec
import com.code2hack.pokerdealer.protocol.PhotoCaptureBegin
import com.code2hack.pokerdealer.protocol.PhotoCaptureChunk
import com.code2hack.pokerdealer.protocol.PhotoCaptureComplete
import com.code2hack.pokerdealer.protocol.PhotoCaptureOutcome
import com.code2hack.pokerdealer.protocol.PhotoCaptureResult
import com.code2hack.pokerdealer.protocol.PhotoDeleteResult
import com.code2hack.pokerdealer.protocol.PhotoStartOutcome
import com.code2hack.pokerdealer.protocol.PhotoStartResult
import com.code2hack.pokerdealer.protocol.PhotoStartTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PokerPhotoControllerTest {
    @Test
    fun `photo zoom steps stay within the camera range`() {
        assertEquals(1.25f, photoZoomStep(1f, increase = true), 0.001f)
        assertEquals(1f, photoZoomStep(1f, increase = false), 0.001f)
        assertEquals(8f, photoZoomStep(8f, increase = true), 0.001f)
        assertEquals(1f, photoZoomStep(1f, increase = false), 0.001f)
    }

    @Test
    fun `capture deadline fences late camera callbacks`() = runTest {
        val cameraResult = CompletableDeferred<ByteArray?>()
        val harness = harness(this, capture = { cameraResult.await() })
        harness.start()
        runCurrent()
        harness.acceptStart()
        harness.controller.setCaptureRequestedCallback { cameraResult.await() }

        tap(harness.controller)
        runCurrent()
        assertEquals(PokerPhotoPhase.CAPTURING, harness.controller.state.value.phase)

        advanceTimeBy(POKER_PHOTO_CAPTURE_TIMEOUT_MS - 1)
        assertEquals(PokerPhotoPhase.CAPTURING, harness.controller.state.value.phase)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(PokerPhotoPhase.PREVIEW, harness.controller.state.value.phase)
        assertEquals("Photo not added", harness.controller.state.value.notice)
        cameraResult.complete(byteArrayOf(1, 2, 3))
        harness.controller.onCaptured(byteArrayOf(4, 5, 6))
        runCurrent()
        assertTrue(harness.begins.isEmpty())
    }

    @Test
    fun `repeated captures preserve bytes and ordered tokens without duplication`() = runTest {
        val harness = harness(this)
        harness.start()
        runCurrent()
        harness.acceptStart()
        val firstBytes = ByteArray(1_901) { it.toByte() }
        val secondBytes = byteArrayOf(9, 8, 7, 6)

        harness.controller.onCaptureRequested()
        harness.controller.onCaptured(firstBytes)
        runCurrent()
        val firstTarget = harness.completes.single().target
        assertArrayEquals(
            firstBytes,
            harness.chunks.flatMap { PhotoAssetCodec.decode(it.data).asList() }.toByteArray(),
        )
        assertEquals(PhotoAssetCodec.sha256(firstBytes), harness.completes.single().sha256)

        val firstDraft = ComposerDraft(
            revision = 1,
            elements = listOf(ComposerElement.Photo(firstTarget.assetId), ComposerElement.Text("x")),
        )
        val firstResult = PhotoCaptureResult(firstTarget, PhotoCaptureOutcome.ACKNOWLEDGED, firstDraft)
        harness.controller.onCaptureResult(firstResult)
        runCurrent()
        harness.controller.onCaptureResult(firstResult)
        runCurrent()
        assertEquals(PokerPhotoPhase.PREVIEW, harness.controller.state.value.phase)

        harness.controller.onCaptureRequested()
        harness.controller.onCaptured(secondBytes)
        runCurrent()
        val secondTarget = harness.completes.last().target
        assertEquals(1, secondTarget.cursorPosition)
        assertArrayEquals(secondBytes, PhotoAssetCodec.decode(harness.chunks.last().data))

        val secondDraft = ComposerDraft(
            revision = 2,
            elements = listOf(
                ComposerElement.Photo(firstTarget.assetId),
                ComposerElement.Photo(secondTarget.assetId),
                ComposerElement.Text("x"),
            ),
        )
        harness.controller.onCaptureResult(
            PhotoCaptureResult(secondTarget, PhotoCaptureOutcome.ACKNOWLEDGED, secondDraft),
        )
        runCurrent()

        val actual = harness.navigation.layout(harness.locator)?.composer?.draft
        assertEquals(secondDraft, actual)
        assertEquals(2, actual?.elements?.count { it is ComposerElement.Photo })
    }

    @Test
    fun `transfer deadline rejects late result without inserting a token`() = runTest {
        val harness = harness(this)
        harness.start()
        runCurrent()
        harness.acceptStart()
        harness.controller.onCaptureRequested()
        harness.controller.onCaptured(byteArrayOf(1, 2, 3))
        runCurrent()
        val target = harness.completes.single().target

        advanceTimeBy(POKER_PHOTO_TRANSFER_TIMEOUT_MS)
        runCurrent()
        assertEquals(PokerPhotoPhase.PREVIEW, harness.controller.state.value.phase)
        harness.controller.onCaptureResult(
            PhotoCaptureResult(
                target,
                PhotoCaptureOutcome.ACKNOWLEDGED,
                ComposerDraft(1, listOf(ComposerElement.Photo(target.assetId))),
            ),
        )
        runCurrent()

        assertTrue(harness.navigation.layout(harness.locator)?.composer?.draft?.isEmpty == false)
        assertEquals("x", harness.navigation.layout(harness.locator)?.composer?.draft?.displayText)
    }

    @Test
    fun `delete uses its five second deadline and fences late callback`() = runTest {
        val harness = harness(this)
        harness.start()
        runCurrent()
        harness.acceptStart()
        harness.controller.onCaptureRequested()
        harness.controller.onCaptured(byteArrayOf(1, 2, 3))
        runCurrent()
        val captureTarget = harness.completes.single().target
        val draft = ComposerDraft(1, listOf(ComposerElement.Photo(captureTarget.assetId)))
        harness.controller.onCaptureResult(
            PhotoCaptureResult(captureTarget, PhotoCaptureOutcome.ACKNOWLEDGED, draft),
        )
        runCurrent()

        harness.controller.requestDelete()
        runCurrent()
        val deleteTarget = harness.deletes.single()
        assertEquals(PokerPhotoPhase.DELETING, harness.controller.state.value.phase)
        advanceTimeBy(POKER_PHOTO_DELETE_TIMEOUT_MS)
        runCurrent()
        assertEquals(PokerPhotoPhase.PREVIEW, harness.controller.state.value.phase)
        assertEquals("Photo not deleted", harness.controller.state.value.notice)
        harness.controller.onDeleteResult(
            PhotoDeleteResult(deleteTarget, PhotoCaptureOutcome.ACKNOWLEDGED, ComposerDraft()),
        )
        runCurrent()
        assertEquals(draft, harness.navigation.layout(harness.locator)?.composer?.draft)
    }

    @Test
    fun `camera failure exits photo without reopening and reconnect loss cancels`() = runTest {
        val harness = harness(this)
        harness.start()
        runCurrent()
        harness.acceptStart()
        assertEquals(1, harness.counters.cameraOpens)

        harness.controller.onCameraFailure()
        runCurrent()
        assertEquals(PokerPhotoPhase.IDLE, harness.controller.state.value.phase)
        assertEquals("Photo unavailable", harness.controller.state.value.notice)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, harness.counters.cameraOpens)

        harness.start()
        runCurrent()
        harness.acceptStart()
        harness.controller.onConnectionLost()
        runCurrent()
        assertEquals(PokerPhotoPhase.IDLE, harness.controller.state.value.phase)
        assertEquals(2, harness.counters.cancels)
        assertEquals(2, harness.counters.cameraCloses)
    }

    @Test
    fun `low storage blocks capture before camera work`() = runTest {
        var captureCalls = 0
        val harness = harness(this, storageAvailable = { false })
        harness.start()
        runCurrent()
        harness.acceptStart()
        harness.controller.setCaptureRequestedCallback {
            captureCalls++
            byteArrayOf(1)
        }

        tap(harness.controller)
        runCurrent()

        assertEquals(0, captureCalls)
        assertEquals(PokerPhotoPhase.PREVIEW, harness.controller.state.value.phase)
        assertEquals("Photo not added", harness.controller.state.value.notice)
        assertTrue(harness.begins.isEmpty())
    }

    private fun tap(controller: PokerPhotoController) {
        controller.handleInteraction(
            PokerInteraction(
                source = com.code2hack.pokerdealer.domain.PokerInputSource.GLASSES,
                operation = PokerOperation.TAP,
                phase = PokerInteractionPhase.BEGIN,
                eventTimeMs = 1,
            ),
        )
        controller.handleInteraction(
            PokerInteraction(
                source = com.code2hack.pokerdealer.domain.PokerInputSource.GLASSES,
                operation = PokerOperation.TAP,
                phase = PokerInteractionPhase.RELEASE,
                eventTimeMs = 2,
            ),
        )
    }

    private data class Counters(
        var cameraOpens: Int = 0,
        var cameraCloses: Int = 0,
        var cancels: Int = 0,
    )

    private class Harness(
        val locator: CodexThreadLocator,
        val navigation: PokerNavigationReducer,
        val controller: PokerPhotoController,
        val starts: MutableList<PhotoStartTarget>,
        val begins: MutableList<PhotoCaptureBegin>,
        val chunks: MutableList<PhotoCaptureChunk>,
        val completes: MutableList<PhotoCaptureComplete>,
        val deletes: MutableList<com.code2hack.pokerdealer.protocol.PhotoAssetTarget>,
        val counters: Counters,
        private val selection: PokerWheelSelection,
    ) {
        fun start() = controller.start(selection)

        fun acceptStart() = controller.onStartResult(
            PhotoStartResult(starts.last(), PhotoStartOutcome.ACCEPTED),
        )
    }

    private fun harness(
        scope: CoroutineScope,
        capture: suspend () -> ByteArray? = { null },
        storageAvailable: () -> Boolean = { true },
    ): Harness {
        val locator = CodexThreadLocator("spark", "thread")
        val navigation = PokerNavigationReducer(viewportLineCount = 4)
        val draft = ComposerDraft.fromText("x")
        navigation.attach(
            locator = locator,
            evidence = ThreadWorkEvidence(activeTurn = false, unresolvedRequestCount = 0),
            atMs = 1,
            layout = PokerPileLayout(
                cards = listOf(PokerCardLayout("card", collapsedLineCount = 1)),
                composer = PokerComposerLayout(
                    draft = draft,
                    controlGeneration = 2,
                    connectionEpoch = 3,
                    modeSession = "mode",
                ),
            ),
        )
        navigation.view(locator)
        navigation.apply(PokerOperation.DOWN)
        val composer = PokerComposerController(navigation) { true }
        composer.applyProjection(
            ComposerDraftProjection(
                locator = locator,
                draft = draft,
                controlGeneration = 2,
                connectionEpoch = 3,
                modeSession = "mode",
            ),
        )
        navigation.setComposerCursor(locator, 0)

        val starts = mutableListOf<PhotoStartTarget>()
        val begins = mutableListOf<PhotoCaptureBegin>()
        val chunks = mutableListOf<PhotoCaptureChunk>()
        val completes = mutableListOf<PhotoCaptureComplete>()
        val deletes = mutableListOf<com.code2hack.pokerdealer.protocol.PhotoAssetTarget>()
        val counters = Counters()
        val controller = PokerPhotoController(
            navigation = navigation,
            composer = composer,
            scope = scope,
            sendStart = { target -> starts += target; true },
            sendBegin = { begin -> begins += begin; true },
            sendChunk = { chunk -> chunks += chunk; true },
            sendComplete = { complete -> completes += complete; true },
            sendDelete = { target -> deletes += target; true },
            sendCancel = { counters.cancels++; true },
            openCamera = { counters.cameraOpens++ },
            closeCamera = { counters.cameraCloses++ },
            storageAvailable = storageAvailable,
        )
        controller.setCaptureRequestedCallback(capture)
        val context = PokerWheelContext(
            targetId = "composer",
            controlGeneration = 2,
            connectionEpoch = 3,
            modeSession = "mode",
            photoAvailable = true,
            primaryAction = PokerPrimaryAction.SEND,
        )
        val selection = PokerWheelSelection(
            sessionId = "wheel",
            action = PokerWheelAction.PHOTO,
            primaryAction = null,
            context = context,
        )
        return Harness(
            locator = locator,
            navigation = navigation,
            controller = controller,
            starts = starts,
            begins = begins,
            chunks = chunks,
            completes = completes,
            deletes = deletes,
            counters = counters,
            selection = selection,
        )
    }
}
