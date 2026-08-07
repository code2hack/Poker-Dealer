package com.code2hack.dealer.asr

import android.content.Context
import com.code2hack.pokerdealer.protocol.PokerAsrAvailability
import com.code2hack.pokerdealer.protocol.PokerAsrPackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One process-local owner for the pack manager and native recognizer. */
internal class DealerAsrProcess private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val runtime = DealerAsrRuntime(applicationContext)
    private val offlineSpools = DealerAsrOfflineSpoolStore(
        root = applicationContext.createDeviceProtectedStorageContext().noBackupFilesDir
            .resolve("asr-spools"),
        hasRoomFor = DealerAsrStorageSpace.from(
            applicationContext.createDeviceProtectedStorageContext(),
        )::hasRoom,
    )
    private val manager = DealerAsrDownloadManager(
        context = applicationContext,
        runtimeHealth = runtime::checkInstalledPack,
    )

    internal val downloadManager: DealerAsrDownloadManager
        get() = manager

    internal suspend fun start() = withContext(Dispatchers.IO) {
        offlineSpools.purgeAtStartup()
        manager.start()
    }

    internal suspend fun availability(): PokerAsrAvailability = withContext(Dispatchers.IO) {
        start()
        val key = manager.stateFlow.value.defaultPack
            ?: return@withContext PokerAsrAvailability(false, reason = "model-pack-not-installed")
        val job = manager.stateFlow.value.jobs.firstOrNull { it.key == key }
            ?: return@withContext PokerAsrAvailability(false, reason = "model-pack-not-installed")
        if (job.state != DealerAsrDownloadState.READY) {
            return@withContext PokerAsrAvailability(false, reason = "model-pack-not-installed")
        }
        val reason = runCatching { runtime.checkInstalledPack(job) }
            .getOrElse { "runtime-load-failed" }
        if (reason != null) return@withContext PokerAsrAvailability(false, reason = reason)
        PokerAsrAvailability(true, selection(job))
    }

    internal suspend fun open(expected: PokerAsrPackSelection? = null): DealerAsrProcessSession = withContext(Dispatchers.IO) {
        start()
        val key = manager.stateFlow.value.defaultPack
            ?: error("model-pack-not-installed")
        val job = manager.stateFlow.value.jobs.firstOrNull { it.key == key }
            ?: error("model-pack-not-installed")
        require(job.state == DealerAsrDownloadState.READY) { "model-pack-not-installed" }
        expected?.let { require(selection(job) == it) { "ASR pack selection changed" } }
        val manifest = requireNotNull(job.runtimeManifest()) { "pack-manifest-invalid" }
        val pack = when (val verification = runtime.verifyPack(manifest)) {
            is DealerAsrPackVerification.Verified -> verification.pack
            is DealerAsrPackVerification.Rejected -> error(verification.reason)
        }
        when (val startup = runtime.startup(pack)) {
            is DealerAsrStartup.Ready -> Unit
            is DealerAsrStartup.Unavailable -> error(startup.reason)
        }
        val profile = manager.beginAsrSession(key)
        try {
            val recognizer = when (pack.adapter) {
                DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING ->
                    DealerAsrSessionRecognizer(runtime.openParakeetStreaming(pack, profile.profile))
                DealerAsrAdapter.MOONSHINE_V2_OFFLINE ->
                    runtime.openMoonshineOffline(pack, profile.profile, offlineSpools)
            }
            DealerAsrProcessSession(
                recognizer = recognizer,
                manager = manager,
                profile = profile,
            )
        } catch (failure: Throwable) {
            manager.endAsrSession(profile)
            throw failure
        }
    }

    internal fun close() {
        offlineSpools.purge()
        manager.close()
    }

    private fun selection(job: DealerAsrDownloadJob): PokerAsrPackSelection {
        val profile = runCatching { Json.parseToJsonElement(job.currentProfileJson) as JsonObject }
            .getOrElse { error("profile-invalid") }
        return PokerAsrPackSelection(job.packId, job.revision, profile)
    }

    internal companion object {
        @Volatile
        private var shared: DealerAsrProcess? = null

        fun shared(context: Context): DealerAsrProcess = synchronized(this) {
            shared ?: DealerAsrProcess(context).also { shared = it }
        }

        fun closeShared(process: DealerAsrProcess) = synchronized(this) {
            if (shared === process) {
                shared = null
                process.close()
            }
        }
    }
}

internal interface DealerAsrRecognizer {
    fun acceptPcm16(pcm: ByteArray)

    fun provisionalText(): String

    fun commitSlice(): String

    fun discardSlice()

    fun close()
}

private class DealerAsrSessionRecognizer(
    private val session: DealerAsrSession,
) : DealerAsrRecognizer {
    override fun acceptPcm16(pcm: ByteArray) = session.acceptPcm16(pcm)

    override fun provisionalText(): String = session.provisionalText()

    override fun commitSlice(): String = session.commitSlice()

    override fun discardSlice() = session.discardSlice()

    override fun close() = session.close()
}

internal class DealerAsrProcessSession internal constructor(
    private val recognizer: DealerAsrRecognizer,
    private val endSession: suspend () -> Unit,
    private val profile: DealerAsrProfile,
) {
    internal constructor(
        recognizer: DealerAsrSession,
        manager: DealerAsrDownloadManager,
        profile: DealerAsrSessionProfile,
    ) : this(
        recognizer = DealerAsrSessionRecognizer(recognizer),
        endSession = { manager.endAsrSession(profile) },
        profile = profile.profile,
    )

    internal constructor(
        recognizer: DealerAsrRecognizer,
        manager: DealerAsrDownloadManager,
        profile: DealerAsrSessionProfile,
    ) : this(
        recognizer = recognizer,
        endSession = { manager.endAsrSession(profile) },
        profile = profile.profile,
    )

    internal constructor(
        recognizer: DealerAsrRecognizer,
        profile: DealerAsrProfile,
    ) : this(
        recognizer = recognizer,
        endSession = {},
        profile = profile,
    )

    private var closed = false

    fun acceptPcm16(pcm: ByteArray) {
        check(!closed) { "ASR session is closed" }
        recognizer.acceptPcm16(pcm)
    }

    fun provisionalText(): String = recognizer.provisionalText()

    fun commitSlice(): String = recognizer.commitSlice().withPausePunctuation(profile.pausePunctuation)

    fun discardSlice() = recognizer.discardSlice()

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            recognizer.close()
        } finally {
            endSession()
        }
    }
}

internal fun String.withPausePunctuation(mark: String): String {
    if (isBlank() || mark.isEmpty()) return this
    val trimmed = trimEnd()
    if (trimmed.lastOrNull() in setOf('.', '?', '!', ',', ';', ':')) return this
    return trimmed + mark
}
