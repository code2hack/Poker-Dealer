package com.code2hack.poker

import android.content.Context
import com.code2hack.pokerdealer.protocol.FilePokerFontScaleStore
import com.code2hack.pokerdealer.protocol.PokerFontScaleController
import com.code2hack.pokerdealer.protocol.PokerFontScaleInstallResult
import com.code2hack.pokerdealer.protocol.PokerFontScaleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps only the last Dealer-acknowledged font state in Poker-private storage. */
internal object PokerPresentationRuntime {
    private val lock = Any()
    private val controller = PokerFontScaleController()
    private val mutableFontScale = MutableStateFlow(controller.state)
    private var store: FilePokerFontScaleStore? = null
    private var diagnosticsRequester: (() -> Unit)? = null

    val fontScale: StateFlow<PokerFontScaleState> = mutableFontScale.asStateFlow()

    fun initialize(context: Context) {
        initialize(FilePokerFontScaleStore(context.noBackupFilesDir.resolve(FILE_NAME)))
    }

    fun initialize(fontStore: FilePokerFontScaleStore) {
        synchronized(lock) {
            store = fontStore
            val restored = fontStore.load()
            controller.install(restored)
            mutableFontScale.value = controller.state
        }
        notifyDiagnostics()
    }

    fun install(candidate: PokerFontScaleState): PokerFontScaleInstallResult {
        val result = synchronized(lock) {
            val current = controller.state
            if (candidate.revision > current.revision) {
                runCatching { store?.save(candidate) }.getOrElse {
                    return@synchronized PokerFontScaleInstallResult.REJECTED
                }
            }
            controller.install(candidate).also {
                if (it == PokerFontScaleInstallResult.INSTALLED ||
                    it == PokerFontScaleInstallResult.DUPLICATE
                ) {
                    mutableFontScale.value = controller.state
                }
            }
        }
        notifyDiagnostics()
        return result
    }

    @Synchronized
    fun attachDiagnosticsRequester(request: () -> Unit) {
        diagnosticsRequester = request
    }

    @Synchronized
    fun detachDiagnosticsRequester() {
        diagnosticsRequester = null
    }

    private fun notifyDiagnostics() {
        synchronized(this) { diagnosticsRequester }?.invoke()
    }

    private const val FILE_NAME = "poker-font-v1.json"
}
