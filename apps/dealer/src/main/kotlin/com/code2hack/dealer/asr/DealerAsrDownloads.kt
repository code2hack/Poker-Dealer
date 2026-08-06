package com.code2hack.dealer.asr

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class DealerAsrPackKey(
    val packId: String,
    val revision: String,
)

internal enum class DealerAsrDownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    READY,
    REPAIR_NEEDED,
    FAILED,
}

@Serializable
internal data class DealerAsrDownloadArtifact(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val canonicalUrl: String = "",
    val downloadedBytes: Long = 0,
    val validator: String? = null,
    val sourceUrl: String? = null,
    val complete: Boolean = false,
)

@Serializable
internal data class DealerAsrDownloadJob(
    val packId: String,
    val revision: String,
    val displayName: String,
    val adapter: DealerAsrAdapter,
    val mode: DealerAsrMode,
    val downloadBytes: Long,
    val temporaryBytes: Long,
    val installedBytes: Long,
    val artifacts: List<DealerAsrDownloadArtifact>,
    val order: Long,
    val mirrorBaseUrl: String? = null,
    val state: DealerAsrDownloadState = DealerAsrDownloadState.QUEUED,
    val currentSource: String? = null,
    val warning: String? = null,
    val error: String? = null,
    val etaMillis: Long? = null,
    val startedAtMillis: Long? = null,
    val defaultProfileJson: String = "{}",
    val profileSchemaJson: String = "{}",
    val profileJson: String = "",
    val profileError: String? = null,
    val repairing: Boolean = false,
) {
    val key: DealerAsrPackKey
        get() = DealerAsrPackKey(packId, revision)

    val totalBytes: Long
        get() = artifacts.sumOf(DealerAsrDownloadArtifact::bytes)

    val downloadedBytes: Long
        get() = artifacts.sumOf { it.downloadedBytes.coerceIn(0, it.bytes) }

    val progressFraction: Float
        get() = if (totalBytes == 0L) 0f else (downloadedBytes.toDouble() / totalBytes).toFloat()

    val percentage: Int
        get() = (progressFraction * 100).toInt().coerceIn(0, 100)

    val currentProfileJson: String
        get() = profileJson.ifBlank { defaultProfileJson }
}

internal data class DealerAsrInstalledPack(
    val key: DealerAsrPackKey,
    val displayName: String,
    val isDefault: Boolean,
    val isActive: Boolean = false,
    val profileJson: String = "{}",
)

internal data class DealerAsrDownloadUiState(
    val jobs: List<DealerAsrDownloadJob> = emptyList(),
    val installed: List<DealerAsrInstalledPack> = emptyList(),
    val defaultPack: DealerAsrPackKey? = null,
    val mirrorBaseUrl: String? = null,
    val error: String? = null,
    val activeSessions: Set<DealerAsrPackKey> = emptySet(),
    val warmPacks: Set<DealerAsrPackKey> = emptySet(),
)

internal data class DealerAsrStorageSpace(
    val availableBytes: () -> Long,
    val lowStorageBytes: () -> Long,
) {
    fun hasRoom(requiredBytes: Long): Boolean {
        if (requiredBytes < 0) return false
        val available = availableBytes()
        val low = lowStorageBytes()
        val usable = if (available <= low) 0 else available - low
        return usable >= requiredBytes
    }

    fun requireRoom(requiredBytes: Long) {
        if (!hasRoom(requiredBytes)) throw DownloadRejected("insufficient-storage")
    }

    companion object {
        fun from(context: Context): DealerAsrStorageSpace {
            val path = context.noBackupFilesDir
            return DealerAsrStorageSpace(
                availableBytes = { StatFs(path.path).availableBytes },
                lowStorageBytes = {
                    runCatching {
                        val manager = context.getSystemService(StorageManager::class.java)
                            ?: return@runCatching 0L
                        val available = StatFs(path.path).availableBytes
                        val allocatable = manager.getAllocatableBytes(manager.getUuidForPath(path))
                        (available - allocatable).coerceAtLeast(0L)
                    }.getOrNull() ?: 0L
                },
            )
        }
    }
}

internal data class DealerAsrDownloadResponse(
    val statusCode: Int,
    val validator: String?,
    val contentRange: String?,
    val contentLength: Long,
    val body: InputStream,
    private val disconnect: () -> Unit,
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        disconnect()
    }
}

internal fun interface DealerAsrDownloadTransport {
    fun open(url: String, offset: Long, validator: String?): DealerAsrDownloadResponse
}

private object HttpDealerAsrDownloadTransport : DealerAsrDownloadTransport {
    override fun open(
        url: String,
        offset: Long,
        validator: String?,
    ): DealerAsrDownloadResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            if (offset > 0 && validator != null) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Range", validator)
            }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
            DealerAsrDownloadResponse(
                statusCode = status,
                validator = connection.getHeaderField("ETag")
                    ?: connection.getHeaderField("Last-Modified"),
                contentRange = connection.getHeaderField("Content-Range"),
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L),
                body = stream,
                disconnect = connection::disconnect,
            )
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }
}

internal fun normalizeDealerAsrMirrorUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw IllegalArgumentException("mirror-url-invalid")
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "mirror-url-invalid"
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
        value.none(Char::isWhitespace)
    ) { "mirror-url-invalid" }
    return if (value.endsWith('/')) value else "$value/"
}

private fun commitDealerAsrState(temporary: File, target: File) {
    try {
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal class DealerAsrDownloadManager(
    private val stateFile: File,
    private val partialRoot: File,
    private val installedRoot: File,
    private val transport: DealerAsrDownloadTransport = HttpDealerAsrDownloadTransport,
    private val storage: DealerAsrStorageSpace = DealerAsrStorageSpace(
        availableBytes = { Long.MAX_VALUE },
        lowStorageBytes = { 0L },
    ),
    initialMirrorBaseUrl: String? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val runtimeHealth: (DealerAsrDownloadJob) -> String? = { null },
    private val unloadIdleDefault: (DealerAsrPackKey) -> Unit = {},
    private val stateCommit: (File, File) -> Unit = ::commitDealerAsrState,
    profileStateFile: File = stateFile.resolveSibling("profiles-v1.json"),
) : Closeable {
    private data class ActiveTransfer(
        val key: DealerAsrPackKey,
        val job: Job,
    )

    constructor(
        context: Context,
        runtimeHealth: (DealerAsrDownloadJob) -> String? = { null },
        unloadIdleDefault: (DealerAsrPackKey) -> Unit = {},
    ) : this(
        stateFile = context.noBackupFilesDir.resolve("asr-downloads/state-v1.json"),
        partialRoot = context.noBackupFilesDir.resolve("asr-downloads/partials"),
        installedRoot = context.noBackupFilesDir.resolve("asr-packs"),
        storage = DealerAsrStorageSpace.from(context),
        runtimeHealth = runtimeHealth,
        unloadIdleDefault = unloadIdleDefault,
    )

    private val lock = Mutex()
    private val state = MutableStateFlow(DealerAsrDownloadUiState())
    private val activeResponse = AtomicReference<DealerAsrDownloadResponse?>(null)
    private val workerLock = Any()
    private var jobs = linkedMapOf<DealerAsrPackKey, DealerAsrDownloadJob>()
    private var defaultPack: DealerAsrPackKey? = null
    private var configuredMirrorBaseUrl: String? = initialMirrorBaseUrl?.let(::normalizeDealerAsrMirrorUrl)
    private var nextOrder = 1L
    private var started = false
    private var managerError: String? = null
    private var persistenceBlocked = false
    private var worker: Job? = null
    private val activeTransfer = AtomicReference<ActiveTransfer?>(null)
    private val activePacks = mutableSetOf<DealerAsrPackKey>()
    private val pendingIdleUnloads = mutableSetOf<DealerAsrPackKey>()
    private val profileStore = DealerAsrProfileStore(profileStateFile, nowMillis)

    val stateFlow: StateFlow<DealerAsrDownloadUiState> = state.asStateFlow()

    suspend fun start() {
        var startupDefault: DealerAsrDownloadJob? = null
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!started) {
                    profileStore.start()
                    loadLocked()
                    started = true
                    publishLocked()
                    startupDefault = defaultPack
                        ?.let(jobs::get)
                        ?.takeIf { it.state == DealerAsrDownloadState.READY && isHealthy(it) }
                }
            }
        }
        startupDefault?.let { validateRuntime(it) }
        ensureWorker()
    }

    suspend fun setMirrorBaseUrl(raw: String?): String? {
        val normalized = normalizeDealerAsrMirrorUrl(raw)
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                configuredMirrorBaseUrl = normalized
                persistLocked()
                publishLocked()
            }
        }
        return normalized
    }

    suspend fun queue(
        entry: DealerAsrCatalogEntry,
        mirrorBaseUrl: String? = configuredMirrorBaseUrl,
    ): DealerAsrDownloadJob {
        val mirror = normalizeDealerAsrMirrorUrl(mirrorBaseUrl)
        start()
        val queued = withContext(Dispatchers.IO) {
            lock.withLock {
                val key = DealerAsrPackKey(entry.id, entry.revision)
                jobs[key]?.let { existing ->
                    if (existing.state != DealerAsrDownloadState.FAILED) {
                        val updated = syncJobProfileLocked(existing, entry)
                        jobs[key] = updated
                        persistLocked()
                        publishLocked()
                        return@withLock updated
                    }
                    deletePartialRoot(key)
                }
                val profile = profileStore.ensureProfile(
                    key = key,
                    schema = entry.profileSchemaModel(),
                    defaultProfile = entry.defaultProfileModel(),
                )
                val job = DealerAsrDownloadJob(
                    packId = entry.id,
                    revision = entry.revision,
                    displayName = entry.displayName,
                    adapter = entry.adapter,
                    mode = entry.mode,
                    downloadBytes = entry.downloadBytes,
                    temporaryBytes = entry.temporaryBytes,
                    installedBytes = entry.installedBytes,
                    artifacts = entry.artifacts.map { artifact ->
                        DealerAsrDownloadArtifact(
                            path = artifact.path,
                            bytes = artifact.bytes,
                            sha256 = artifact.sha256,
                            canonicalUrl = artifact.canonicalUrl,
                        )
                    },
                    order = nextOrder++,
                    mirrorBaseUrl = mirror,
                    state = if (hasRoomFor(entry, remainingBytes = entry.artifacts.sumOf { it.bytes })) {
                        DealerAsrDownloadState.QUEUED
                    } else {
                        DealerAsrDownloadState.FAILED
                    },
                    error = if (hasRoomFor(entry, remainingBytes = entry.artifacts.sumOf { it.bytes })) {
                        null
                    } else {
                        "insufficient-storage"
                    },
                    defaultProfileJson = entry.defaultProfile.toString(),
                    profileSchemaJson = entry.profileSchema.toString(),
                    profileJson = profile.json.toString(),
                )
                jobs[key] = job
                persistLocked()
                publishLocked()
                job
            }
        }
        ensureWorker()
        return queued
    }

    suspend fun pause(key: DealerAsrPackKey) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key] ?: return@withLock
                if (job.state !in setOf(
                        DealerAsrDownloadState.QUEUED,
                        DealerAsrDownloadState.DOWNLOADING,
                    )
                ) return@withLock
                jobs[key] = job.copy(state = DealerAsrDownloadState.PAUSED)
                persistLocked()
                publishLocked()
            }
        }
        interruptActive(key, CancellationException("download-paused"))
        ensureWorker()
    }

    suspend fun resume(key: DealerAsrPackKey) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key] ?: return@withLock
                if (job.state !in setOf(
                        DealerAsrDownloadState.PAUSED,
                        DealerAsrDownloadState.FAILED,
                    )
                ) return@withLock
                if (job.state == DealerAsrDownloadState.FAILED) {
                    deletePartialRoot(key)
                }
                jobs[key] = job.copy(
                    artifacts = if (job.state == DealerAsrDownloadState.FAILED) {
                        job.artifacts.map { it.copy(downloadedBytes = 0, validator = null, sourceUrl = null, complete = false) }
                    } else {
                        job.artifacts
                    },
                    state = DealerAsrDownloadState.QUEUED,
                    currentSource = null,
                    warning = null,
                    error = null,
                    repairing = job.repairing,
                )
                persistLocked()
                publishLocked()
            }
        }
        ensureWorker()
    }

    suspend fun repair(key: DealerAsrPackKey) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key] ?: throw DownloadRejected("model-pack-not-installed")
                require(job.state == DealerAsrDownloadState.REPAIR_NEEDED) {
                    "model-pack-repair-not-needed"
                }
                require(key !in activePacks) { "model-pack-active" }
                val reset = job.artifacts.map {
                    it.copy(
                        downloadedBytes = 0,
                        validator = null,
                        sourceUrl = null,
                        complete = false,
                    )
                }
                val enoughStorage = hasRoomFor(job, job.totalBytes)
                jobs[key] = job.copy(
                    artifacts = reset,
                    state = if (enoughStorage) {
                        DealerAsrDownloadState.QUEUED
                    } else {
                        DealerAsrDownloadState.REPAIR_NEEDED
                    },
                    currentSource = null,
                    warning = null,
                    error = if (enoughStorage) null else "insufficient-storage",
                    repairing = enoughStorage,
                )
                if (!enoughStorage) {
                    persistLocked()
                    publishLocked()
                    return@withLock
                }
                deletePartialRoot(key)
                persistLocked()
                publishLocked()
            }
        }
        ensureWorker()
    }

    suspend fun cancel(key: DealerAsrPackKey) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key] ?: return@withLock
                if (job.state in setOf(
                        DealerAsrDownloadState.READY,
                        DealerAsrDownloadState.REPAIR_NEEDED,
                    )
                ) return@withLock
                jobs.remove(key)
                deletePartialRoot(key)
                persistLocked()
                publishLocked()
            }
        }
        interruptActive(key, CancellationException("download-cancelled"))
    }

    suspend fun setDefault(key: DealerAsrPackKey) {
        start()
        val candidate = withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key]
                require(job?.state == DealerAsrDownloadState.READY && isHealthy(job)) {
                    "model-pack-not-installed"
                }
                job
            }
        }
        runtimeFailure(candidate)?.let { reason ->
            markRepairNeeded(key, reason)
            throw DownloadRejected(reason)
        }
        val unload = withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key]
                require(job?.state == DealerAsrDownloadState.READY && isHealthy(job)) {
                    "model-pack-not-installed"
                }
                val previous = defaultPack
                if (previous == key) return@withLock null
                defaultPack = key
                previous?.let { oldDefault ->
                    if (oldDefault in activePacks) {
                        pendingIdleUnloads += oldDefault
                    } else {
                        pendingIdleUnloads -= oldDefault
                    }
                }
                pendingIdleUnloads -= key
                profileStore.setDefault(key)
                persistLocked()
                publishLocked()
                previous?.takeUnless { it in activePacks }
            }
        }
        unload?.let { runCatching { unloadIdleDefault(it) } }
    }

    suspend fun setActive(key: DealerAsrPackKey, active: Boolean) {
        start()
        val unload = withContext(Dispatchers.IO) {
            lock.withLock {
                val result = if (active) {
                    activateLocked(key)
                    null
                } else {
                    deactivateLocked(key)
                }
                publishLocked()
                result
            }
        }
        unload?.let { runCatching { unloadIdleDefault(it) } }
    }

    private fun activateLocked(key: DealerAsrPackKey) {
        val job = jobs[key]
        require(job?.state == DealerAsrDownloadState.READY && isHealthy(job)) {
            "model-pack-not-installed"
        }
        activePacks += key
        pendingIdleUnloads -= key
    }

    private fun deactivateLocked(key: DealerAsrPackKey): DealerAsrPackKey? {
        activePacks -= key
        return if (key in pendingIdleUnloads) {
            pendingIdleUnloads -= key
            key
        } else {
            null
        }
    }

    suspend fun markRepairNeeded(key: DealerAsrPackKey, reason: String = "pack-unloadable") {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key] ?: throw DownloadRejected("model-pack-not-installed")
                jobs[key] = job.copy(
                    state = DealerAsrDownloadState.REPAIR_NEEDED,
                    currentSource = null,
                    error = reason,
                    repairing = false,
                )
                persistLocked()
                publishLocked()
            }
        }
    }

    suspend fun profile(key: DealerAsrPackKey): String? = withContext(Dispatchers.IO) {
        lock.withLock { jobs[key]?.currentProfileJson }
    }

    suspend fun delete(key: DealerAsrPackKey, confirmed: Boolean) {
        require(confirmed) { "confirmation-required" }
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(defaultPack != key) { "model-pack-default" }
                require(key !in activePacks) { "model-pack-active" }
                val job = jobs[key] ?: return@withLock
                require(job.state in setOf(
                    DealerAsrDownloadState.READY,
                    DealerAsrDownloadState.REPAIR_NEEDED,
                    DealerAsrDownloadState.FAILED,
                )) { "model-pack-busy" }
                jobs.remove(key)
                pendingIdleUnloads -= key
                deletePartialRoot(key)
                installedRootFor(key).deleteRecursively()
                persistLocked()
                publishLocked()
            }
        }
    }

    override fun close() {
        activeResponse.getAndSet(null)?.close()
        activeTransfer.getAndSet(null)?.job?.cancel(CancellationException("download-manager-closed"))
        profileStore.close()
        scope.cancel()
    }

    suspend fun syncCatalog(catalog: DealerAsrCatalog) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                jobs = jobs.mapValuesTo(linkedMapOf()) { (_, job) ->
                    catalog.entries.firstOrNull { it.id == job.packId && it.revision == job.revision }
                        ?.let { syncJobProfileLocked(job, it) }
                        ?: job
                }
                persistLocked()
                publishLocked()
            }
        }
    }

    suspend fun saveProfile(key: DealerAsrPackKey, raw: String): DealerAsrProfileSaveResult {
        start()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key]
                if (job == null || job.state != DealerAsrDownloadState.READY || !isInstalled(key)) {
                    val result = DealerAsrProfileSaveResult.Rejected(
                        listOf(DealerAsrProfileError("profile", "model-pack-not-installed")),
                    )
                    profileErrorLocked(key, job, result)
                    return@withLock result
                }
                val schema = runCatching {
                    DealerAsrProfileSchema.parse(
                        downloadJson.parseToJsonElement(job.profileSchemaJson) as JsonObject,
                        key.packId,
                        key.revision,
                    )
                }.getOrElse {
                    val result = DealerAsrProfileSaveResult.Rejected(
                        listOf(DealerAsrProfileError("profileSchema", "schema-invalid")),
                    )
                    profileErrorLocked(key, job, result)
                    return@withLock result
                }
                val result = profileStore.save(key, schema, raw)
                when (result) {
                    is DealerAsrProfileSaveResult.Saved -> {
                        jobs[key] = job.copy(profileJson = result.profile.json.toString(), profileError = null)
                        persistLocked()
                        publishLocked()
                    }
                    is DealerAsrProfileSaveResult.Rejected -> profileErrorLocked(key, job, result)
                }
                result
            }
        }
    }

    suspend fun beginAsrSession(key: DealerAsrPackKey): DealerAsrSessionProfile {
        start()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val job = jobs[key]
                require(job?.state == DealerAsrDownloadState.READY && isInstalled(key)) {
                    "model-pack-not-installed"
                }
                val schema = DealerAsrProfileSchema.parse(
                    downloadJson.parseToJsonElement(job.profileSchemaJson) as JsonObject,
                    key.packId,
                    key.revision,
                )
                val session = profileStore.beginSession(key, schema)
                val wasActive = key in activePacks
                val wasPendingUnload = key in pendingIdleUnloads
                try {
                    jobs[key] = job.copy(profileJson = session.profile.json.toString(), profileError = null)
                    activateLocked(key)
                    persistLocked()
                    publishLocked()
                    session
                } catch (failure: Throwable) {
                    jobs[key] = job
                    if (!wasActive) activePacks -= key
                    if (wasPendingUnload) pendingIdleUnloads += key else pendingIdleUnloads -= key
                    profileStore.endSession(session)
                    publishLocked()
                    throw failure
                }
            }
        }
    }

    suspend fun endAsrSession(session: DealerAsrSessionProfile) {
        start()
        val unload = withContext(Dispatchers.IO) {
            lock.withLock {
                val unload = if (profileStore.endSession(session)) {
                    deactivateLocked(session.key)
                } else {
                    null
                }
                publishLocked()
                unload
            }
        }
        unload?.let { runCatching { unloadIdleDefault(it) } }
    }

    suspend fun retainWarmRecognizer(
        key: DealerAsrPackKey,
        profile: DealerAsrProfile,
        recognizer: Closeable,
    ) {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(profile.matches(key)) { "profile-schema-pack-mismatch" }
                profileStore.retainWarmRecognizer(key, profile, recognizer)
                publishLocked()
            }
        }
    }

    suspend fun evictWarmRecognizers() {
        start()
        withContext(Dispatchers.IO) {
            lock.withLock {
                profileStore.evictWarmRecognizers()
                publishLocked()
            }
        }
    }

    internal fun evictIdleRecognizer(key: DealerAsrPackKey) {
        profileStore.evictWarmRecognizer(key)
    }

    private suspend fun runtimeFailure(job: DealerAsrDownloadJob): String? = runCatching {
        runtimeHealth(job)?.takeIf(String::isNotBlank)
    }.getOrElse { "runtime-load-failed" }

    private suspend fun validateRuntime(job: DealerAsrDownloadJob): Boolean {
        val reason = runtimeFailure(job) ?: return true
        runCatching { markRepairNeeded(job.key, reason) }
        return false
    }

    private fun interruptActive(key: DealerAsrPackKey, cause: CancellationException) {
        val active = activeTransfer.get() ?: return
        if (active.key != key) return
        activeResponse.getAndSet(null)?.close()
        active.job.cancel(cause)
    }

    private fun ensureWorker() {
        synchronized(workerLock) {
            if (!started || !scope.isActive) return
            if (worker?.isActive == true) return
            worker = scope.launch {
                try {
                    workerLoop()
                } finally {
                    synchronized(workerLock) { worker = null }
                }
            }
        }
    }

    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            val key = withContext(Dispatchers.IO) {
                lock.withLock {
                    val next = jobs.values
                        .filter { it.state == DealerAsrDownloadState.QUEUED }
                        .minByOrNull(DealerAsrDownloadJob::order)
                        ?: return@withLock null
                    jobs[next.key] = next.copy(
                        state = DealerAsrDownloadState.DOWNLOADING,
                        startedAtMillis = nowMillis(),
                        currentSource = null,
                    )
                    persistLocked()
                    publishLocked()
                    next.key
                }
            } ?: return

            val transfer = scope.async(start = CoroutineStart.LAZY) { processPack(key) }
            val active = ActiveTransfer(key, transfer)
            activeTransfer.set(active)
            transfer.start()
            var failure: String? = null
            try {
                transfer.await()
            } catch (_: CancellationException) {
                if (!scope.isActive) return
            } catch (rejected: DownloadRejected) {
                failure = rejected.reason
            } catch (_: Throwable) {
                failure = "download-failed"
            } finally {
                activeTransfer.compareAndSet(active, null)
            }

            var readyJob: DealerAsrDownloadJob? = null
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val job = jobs[key] ?: return@withLock
                    when {
                        job.state == DealerAsrDownloadState.PAUSED -> {
                            persistLocked()
                            publishLocked()
                        }
                        failure != null -> {
                            jobs[key] = job.copy(
                                state = DealerAsrDownloadState.FAILED,
                                currentSource = null,
                                error = failure,
                            )
                            persistLocked()
                            publishLocked()
                        }
                        job.state == DealerAsrDownloadState.DOWNLOADING && isHealthy(job.copy(repairing = false)) -> {
                            jobs[key] = job.copy(
                                state = DealerAsrDownloadState.READY,
                                currentSource = null,
                                error = null,
                                repairing = false,
                            )
                            if (defaultPack == null) {
                                defaultPack = key
                                profileStore.setDefault(key)
                            }
                            readyJob = jobs.getValue(key)
                            persistReadyMarkerLocked(jobs.getValue(key))
                            persistLocked()
                            publishLocked()
                        }
                        job.state == DealerAsrDownloadState.DOWNLOADING -> {
                            jobs[key] = job.copy(
                                state = DealerAsrDownloadState.FAILED,
                                currentSource = null,
                                error = "download-incomplete",
                            )
                            persistLocked()
                            publishLocked()
                        }
                    }
                }
            }
            readyJob?.let { validateRuntime(it) }
        }
    }

    private suspend fun processPack(key: DealerAsrPackKey) {
        val initial = currentJob(key)
        val remaining = initial.artifacts
            .filterNot(DealerAsrDownloadArtifact::complete)
            .sumOf { it.bytes - it.downloadedBytes.coerceIn(0, it.bytes) }
        if (!hasRoomFor(initial, remaining)) throw DownloadRejected("insufficient-storage")

        initial.artifacts.indices.forEach { index ->
            currentCoroutineContext().ensureActive()
            val artifact = currentJob(key).artifacts[index]
            val file = partialArtifact(key, artifact.path)
            if (artifact.complete && isArtifactDigestValid(file, artifact.sha256, artifact.bytes)) return@forEach
            downloadArtifactWithFallback(key, index)
        }
        installPack(key)
    }

    private suspend fun downloadArtifactWithFallback(key: DealerAsrPackKey, index: Int) {
        val job = currentJob(key)
        val artifact = job.artifacts[index]
        val mirror = job.mirrorBaseUrl?.let { mirrorArtifactUrl(it, artifact.path) }
        val candidates = listOfNotNull(
            mirror?.let { it to true },
            artifactCanonicalUrl(job, artifact.path)?.let { it to false },
        ).distinctBy { it.first }
        var lastReason = "network-failure"
        candidates.forEach { (url, isMirror) ->
            updateJob(key) { it.copy(currentSource = url) }
            try {
                downloadArtifact(key, index, url)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: DownloadRejected) {
                lastReason = rejected.reason
                if (!isMirror) throw rejected
                updateJob(key) { it.copy(warning = "mirror-fallback:$lastReason") }
                resetArtifact(key, index)
            } catch (_: Throwable) {
                lastReason = "network-failure"
                if (!isMirror) throw DownloadRejected(lastReason)
                updateJob(key) { it.copy(warning = "mirror-fallback:$lastReason") }
                resetArtifact(key, index)
            }
        }
        throw DownloadRejected(lastReason)
    }

    private suspend fun downloadArtifact(
        key: DealerAsrPackKey,
        index: Int,
        url: String,
    ) {
        val job = currentJob(key)
        val artifact = job.artifacts[index]
        val file = partialArtifact(key, artifact.path)
        requireSafeArtifactFile(file, partialRootFor(key))
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "download-storage-unavailable" } }

        var offset = artifact.downloadedBytes.coerceIn(0, artifact.bytes)
        val canResume = offset > 0 && artifact.sourceUrl == url && artifact.validator != null
        if (!canResume) {
            if (offset > 0 || file.exists()) resetArtifact(key, index)
            offset = 0
        } else if (file.length() != offset) {
            RandomAccessFile(file, "rw").use { it.setLength(offset) }
        }

        var response = openResponse(url, if (canResume) offset else 0, if (canResume) artifact.validator else null)
        var append = canResume && response.statusCode == 206 &&
            response.validator == artifact.validator &&
            response.contentRange?.let { parseRangeStart(it) == offset } == true
        if (canResume && !append) {
            response.close()
            resetArtifact(key, index)
            offset = 0
            response = openResponse(url, 0, null)
        }

        try {
            if (response.statusCode !in 200..299 || (!append && response.statusCode != 200)) {
                throw DownloadRejected("http-${response.statusCode}")
            }
            response.contentRange?.let { range ->
                parseRangeTotal(range)?.takeIf { it >= 0 && it != artifact.bytes }?.let {
                    throw DownloadRejected("size-mismatch")
                }
            }
            updateArtifact(
                key,
                index,
                downloadedBytes = offset,
                validator = response.validator,
                sourceUrl = url,
                complete = false,
            )
            RandomAccessFile(file, "rw").use { output ->
                output.seek(offset)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(key)
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val nextOffset = offset + count
                    if (nextOffset > artifact.bytes) throw DownloadRejected("size-mismatch")
                    val latest = currentJob(key)
                    hasRoomFor(latest, artifact.bytes - nextOffset).let { enough ->
                        if (!enough) throw DownloadRejected("insufficient-storage")
                    }
                    output.write(buffer, 0, count)
                    offset = nextOffset
                    updateArtifact(
                        key,
                        index,
                        downloadedBytes = offset,
                        validator = response.validator,
                        sourceUrl = url,
                        complete = false,
                    )
                }
            }
            if (offset != artifact.bytes) throw DownloadRejected("size-mismatch")
            if (!isArtifactDigestValid(file, artifact.sha256, artifact.bytes)) {
                throw DownloadRejected("digest-mismatch")
            }
            updateArtifact(
                key,
                index,
                downloadedBytes = offset,
                validator = response.validator,
                sourceUrl = url,
                complete = true,
            )
        } finally {
            if (activeResponse.get() === response) activeResponse.set(null)
            response.close()
        }
    }

    private fun openResponse(url: String, offset: Long, validator: String?): DealerAsrDownloadResponse {
        val response = try {
            transport.open(url, offset, validator)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw DownloadRejected("network-failure")
        }
        activeResponse.set(response)
        return response
    }

    private suspend fun installPack(key: DealerAsrPackKey) {
        ensureCurrent(key)
        val job = currentJob(key)
        job.artifacts.forEach { artifact ->
            val file = partialArtifact(key, artifact.path)
            requireSafeArtifactFile(file, partialRootFor(key))
            if (!isArtifactDigestValid(file, artifact.sha256, artifact.bytes)) {
                throw DownloadRejected("digest-mismatch")
            }
        }
        val staging = partialRootFor(key)
        require(staging.isDirectory) { "download-storage-unavailable" }
        persistReadyMarkerLocked(job.copy(repairing = false), staging)

        val finalRoot = installedRoot.resolve(key.packId).resolve(key.revision)
        requireSafeDirectory(finalRoot, installedRoot)
        finalRoot.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "install-storage-unavailable" } }
        val replacing = finalRoot.exists()
        val backupRoot = if (replacing) {
            finalRoot.resolveSibling(".${finalRoot.name}.replacing-${nowMillis()}")
        } else {
            null
        }
        try {
            if (backupRoot != null) {
                requireSafeDirectory(backupRoot, installedRoot)
                moveDirectory(finalRoot, backupRoot)
            }
            moveDirectory(staging, finalRoot)
        } catch (failure: Throwable) {
            if (backupRoot?.isDirectory == true && !finalRoot.exists()) {
                runCatching { moveDirectory(backupRoot, finalRoot) }
            }
            throw failure
        } finally {
            if (finalRoot.exists()) backupRoot?.deleteRecursively()
        }
        partialRootFor(key).parentFile?.listFiles()?.let { children ->
            if (children.isEmpty()) partialRootFor(key).parentFile?.delete()
        }
    }

    private suspend fun currentJob(key: DealerAsrPackKey): DealerAsrDownloadJob =
        lock.withLock { jobs[key] ?: throw DownloadCancelled() }

    private suspend fun ensureCurrent(key: DealerAsrPackKey) {
        currentCoroutineContext().ensureActive()
        lock.withLock {
            when (jobs[key]?.state) {
                DealerAsrDownloadState.DOWNLOADING -> Unit
                DealerAsrDownloadState.PAUSED -> throw DownloadPaused()
                else -> throw DownloadCancelled()
            }
        }
    }

    private suspend fun updateArtifact(
        key: DealerAsrPackKey,
        index: Int,
        downloadedBytes: Long,
        validator: String?,
        sourceUrl: String?,
        complete: Boolean,
    ) {
        lock.withLock {
            val job = jobs[key] ?: throw DownloadCancelled()
            val artifacts = job.artifacts.toMutableList()
            artifacts[index] = artifacts[index].copy(
                downloadedBytes = downloadedBytes,
                validator = validator,
                sourceUrl = sourceUrl,
                complete = complete,
            )
            val downloaded = artifacts.sumOf { it.downloadedBytes.coerceIn(0, it.bytes) }
            val elapsed = job.startedAtMillis?.let { nowMillis() - it }
            val eta = if (downloaded > 0 && elapsed != null && elapsed > 0) {
                ((job.totalBytes - downloaded).coerceAtLeast(0) * elapsed) / downloaded
            } else {
                null
            }
            jobs[key] = job.copy(artifacts = artifacts, etaMillis = eta)
            persistLocked()
            publishLocked()
        }
    }

    private suspend fun resetArtifact(key: DealerAsrPackKey, index: Int) {
        val job = currentJob(key)
        deleteArtifactFile(key, job.artifacts[index].path)
        updateJob(key) {
            val artifacts = it.artifacts.toMutableList()
            artifacts[index] = artifacts[index].copy(
                downloadedBytes = 0,
                validator = null,
                sourceUrl = null,
                complete = false,
            )
            it.copy(artifacts = artifacts)
        }
    }

    private suspend fun updateJob(
        key: DealerAsrPackKey,
        transform: (DealerAsrDownloadJob) -> DealerAsrDownloadJob,
    ) {
        lock.withLock {
            jobs[key]?.let {
                jobs[key] = transform(it)
                persistLocked()
                publishLocked()
            } ?: throw DownloadCancelled()
        }
    }

    private fun artifactCanonicalUrl(job: DealerAsrDownloadJob, path: String): String? =
        job.artifacts.firstOrNull { it.path == path }?.let { artifact ->
            artifact.canonicalUrl.takeIf { it.startsWith("https://") }
        }

    private fun mirrorArtifactUrl(base: String, path: String): String =
        URI(base).resolve(path).toString()

    private fun hasRoomFor(entry: DealerAsrCatalogEntry, remainingBytes: Long): Boolean {
        return hasRoomFor(
            downloadBytes = entry.downloadBytes,
            temporaryBytes = entry.temporaryBytes,
            installedBytes = entry.installedBytes,
            remainingBytes = remainingBytes,
        )
    }

    private fun hasRoomFor(job: DealerAsrDownloadJob, remainingBytes: Long): Boolean =
        hasRoomFor(
            downloadBytes = job.downloadBytes,
            temporaryBytes = job.temporaryBytes,
            installedBytes = job.installedBytes,
            remainingBytes = remainingBytes,
        )

    private fun hasRoomFor(
        downloadBytes: Long,
        temporaryBytes: Long,
        installedBytes: Long,
        remainingBytes: Long,
    ): Boolean {
        val declared = runCatching {
            Math.addExact(max(downloadBytes, temporaryBytes), installedBytes)
        }.getOrNull() ?: return false
        val remaining = runCatching {
            Math.addExact(remainingBytes.coerceAtLeast(0), installedBytes)
        }.getOrNull() ?: return false
        val required = max(declared, remaining)
        return storage.hasRoom(required)
    }

    private fun loadLocked() {
        val document = if (stateFile.isFile) {
            runCatching { downloadJson.decodeFromString<PersistedDownloadDocument>(stateFile.readText()) }
                .onFailure {
                    managerError = "download-state-invalid"
                    persistenceBlocked = true
                }
                .getOrElse { PersistedDownloadDocument() }
        } else {
            PersistedDownloadDocument()
        }
        if (document.schemaVersion > STATE_SCHEMA_VERSION) {
            managerError = "download-state-unsupported"
            persistenceBlocked = true
        }
        configuredMirrorBaseUrl = document.mirrorBaseUrl?.let {
            runCatching { normalizeDealerAsrMirrorUrl(it) }.getOrNull()
        } ?: configuredMirrorBaseUrl
        defaultPack = document.defaultPack
        jobs = linkedMapOf()
        document.jobs.forEach { job -> jobs[job.key] = job }
        cleanupIncompleteInstalledPacks()
        installedMarkers().forEach { marker ->
            val markerJob = runCatching {
                downloadJson.decodeFromString<DealerAsrDownloadJob>(marker.readText())
            }.getOrNull() ?: return@forEach
            val existing = jobs[markerJob.key]
            jobs[markerJob.key] = when {
                existing == null -> markerJob.copy(state = DealerAsrDownloadState.READY, repairing = false)
                existing.repairing -> existing
                existing.state in setOf(
                    DealerAsrDownloadState.QUEUED,
                    DealerAsrDownloadState.DOWNLOADING,
                ) -> existing.copy(state = DealerAsrDownloadState.READY, repairing = false)
                else -> existing
            }
        }
        jobs = jobs.mapValuesTo(linkedMapOf()) { (_, job) ->
            val normalized = when {
                job.state == DealerAsrDownloadState.DOWNLOADING && isReady(job.key) ->
                    job.copy(state = DealerAsrDownloadState.READY, repairing = false)
                job.state == DealerAsrDownloadState.DOWNLOADING ->
                    job.copy(state = DealerAsrDownloadState.QUEUED)
                job.state == DealerAsrDownloadState.READY && !isHealthy(job) ->
                    job.copy(
                        state = DealerAsrDownloadState.REPAIR_NEEDED,
                        error = if (isReady(job.key)) "pack-digest-mismatch" else "model-pack-not-installed",
                        repairing = false,
                    )
                else -> job
            }
            normalizeJobProfileLocked(normalized)
        }
        nextOrder = max(
            document.nextOrder,
            (jobs.values.maxOfOrNull { it.order } ?: 0L) + 1L,
        )
        if (defaultPack != null && jobs[defaultPack] == null) defaultPack = null
        if (defaultPack == null && !persistenceBlocked) {
            defaultPack = jobs.values
                .filter { it.state == DealerAsrDownloadState.READY && isHealthy(it) }
                .minByOrNull(DealerAsrDownloadJob::order)
                ?.key
        }
        profileStore.setDefault(defaultPack)
        if (!persistenceBlocked) {
            runCatching { persistLocked() }.onFailure {
                managerError = "download-state-migration-failed"
                persistenceBlocked = true
            }
        }
    }

    private fun publishLocked() {
        val ready = jobs.values.filter {
            it.state in setOf(DealerAsrDownloadState.READY, DealerAsrDownloadState.REPAIR_NEEDED)
        }
        state.value = DealerAsrDownloadUiState(
            jobs = jobs.values.sortedBy(DealerAsrDownloadJob::order),
            installed = ready.map { job ->
                DealerAsrInstalledPack(
                    key = job.key,
                    displayName = job.displayName,
                    isDefault = job.key == defaultPack,
                    isActive = job.key in activePacks,
                    profileJson = job.currentProfileJson,
                )
            },
            defaultPack = defaultPack,
            mirrorBaseUrl = configuredMirrorBaseUrl,
            error = managerError,
            activeSessions = profileStore.activeSessionKeys(),
            warmPacks = profileStore.warmPackKeys(),
        )
    }

    private fun normalizeJobProfileLocked(job: DealerAsrDownloadJob): DealerAsrDownloadJob {
        val schemaObject = runCatching { downloadJson.parseToJsonElement(job.profileSchemaJson) as JsonObject }
            .getOrNull() ?: return job
        val schema = runCatching {
            DealerAsrProfileSchema.parse(schemaObject, job.packId, job.revision)
        }.getOrNull() ?: return job
        val defaultProfile = runCatching {
            downloadJson.parseToJsonElement(job.defaultProfileJson) as JsonObject
        }.getOrNull()?.let { raw ->
            when (val validation = schema.validate(raw)) {
                is DealerAsrProfileValidation.Valid -> validation.profile
                is DealerAsrProfileValidation.Invalid -> null
            }
        } ?: schema.defaultProfile()
        val profile = profileStore.ensureProfile(DealerAsrPackKey(job.packId, job.revision), schema, defaultProfile)
        return job.copy(profileJson = profile.json.toString(), profileError = null)
    }

    private fun syncJobProfileLocked(
        job: DealerAsrDownloadJob,
        entry: DealerAsrCatalogEntry,
    ): DealerAsrDownloadJob {
        val key = DealerAsrPackKey(entry.id, entry.revision)
        val schema = entry.profileSchemaModel()
        val profile = profileStore.ensureProfile(key, schema, entry.defaultProfileModel())
        return job.copy(
            displayName = entry.displayName,
            adapter = entry.adapter,
            mode = entry.mode,
            defaultProfileJson = entry.defaultProfile.toString(),
            profileSchemaJson = entry.profileSchema.toString(),
            profileJson = profile.json.toString(),
            profileError = null,
        )
    }

    private fun profileErrorLocked(
        key: DealerAsrPackKey,
        job: DealerAsrDownloadJob?,
        result: DealerAsrProfileSaveResult.Rejected,
    ) {
        job ?: return
        jobs[key] = job.copy(profileError = result.errors.joinToString("; ") { "${it.path}: ${it.reason}" })
        persistLocked()
        publishLocked()
    }

    private fun persistLocked() {
        check(!persistenceBlocked) { "download-state-persistence-failed" }
        val parent = stateFile.parentFile ?: throw DownloadRejected("download-storage-unavailable")
        require(parent.isDirectory || parent.mkdirs()) { "download-storage-unavailable" }
        val document = PersistedDownloadDocument(
            schemaVersion = STATE_SCHEMA_VERSION,
            nextOrder = nextOrder,
            defaultPack = defaultPack,
            mirrorBaseUrl = configuredMirrorBaseUrl,
            jobs = jobs.values.toList(),
        )
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            temporary.writeText(downloadJson.encodeToString(document))
            stateCommit(temporary, stateFile)
        } finally {
            temporary.delete()
        }
    }

    private fun persistReadyMarkerLocked(job: DealerAsrDownloadJob, root: File = installedRootFor(job.key)) {
        require(root.isDirectory || root.mkdirs()) { "install-storage-unavailable" }
        val marker = root.resolve(READY_MARKER)
        val temporary = File.createTempFile("$READY_MARKER.", ".tmp", root)
        try {
            temporary.writeText(downloadJson.encodeToString(job.copy(state = DealerAsrDownloadState.READY)))
            try {
                Files.move(
                    temporary.toPath(),
                    marker.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun cleanupIncompleteInstalledPacks() {
        installedRoot.listFiles()?.filter(File::isDirectory)?.forEach { idDirectory ->
            idDirectory.listFiles()?.filter(File::isDirectory)?.forEach { revisionDirectory ->
                if (!revisionDirectory.resolve(READY_MARKER).isFile) revisionDirectory.deleteRecursively()
            }
            if (idDirectory.listFiles().isNullOrEmpty()) idDirectory.delete()
        }
    }

    private fun installedMarkers(): List<File> = buildList {
        installedRoot.listFiles()?.filter(File::isDirectory)?.forEach { idDirectory ->
            idDirectory.listFiles()?.filter(File::isDirectory)?.forEach { revisionDirectory ->
                revisionDirectory.resolve(READY_MARKER).takeIf(File::isFile)?.let(::add)
            }
        }
    }

    private fun isReady(key: DealerAsrPackKey): Boolean =
        installedRootFor(key).resolve(READY_MARKER).isFile

    private fun isInstalled(key: DealerAsrPackKey): Boolean = isReady(key)

    private fun isHealthy(job: DealerAsrDownloadJob): Boolean {
        if (job.repairing || !isReady(job.key)) return false
        return job.artifacts.all { artifact ->
            runCatching {
                val file = installedRootFor(job.key).resolve(artifact.path)
                requireSafeArtifactFile(file, installedRootFor(job.key))
                isArtifactDigestValid(
                    file,
                    artifact.sha256,
                    artifact.bytes,
                )
            }.getOrDefault(false)
        }
    }

    private fun partialRootFor(key: DealerAsrPackKey): File =
        partialRoot.resolve(key.packId).resolve(key.revision)

    private fun installedRootFor(key: DealerAsrPackKey): File =
        installedRoot.resolve(key.packId).resolve(key.revision)

    private fun partialArtifact(key: DealerAsrPackKey, path: String): File =
        partialRootFor(key).resolve(path)

    private fun deletePartialRoot(key: DealerAsrPackKey) {
        partialRootFor(key).deleteRecursively()
        partialRootFor(key).parentFile?.listFiles()?.let { if (it.isEmpty()) partialRootFor(key).parentFile?.delete() }
    }

    private fun deleteArtifactFile(key: DealerAsrPackKey, path: String) {
        partialArtifact(key, path).delete()
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun requireSafeDirectory(directory: File, root: File) {
        val rootPath = root.canonicalFile.toPath()
        val canonical = directory.canonicalFile.toPath()
        require(canonical.startsWith(rootPath)) { "download-path-invalid" }
        var cursor = rootPath
        rootPath.relativize(directory.toPath().toAbsolutePath().normalize()).forEach { segment ->
            cursor = cursor.resolve(segment.toString())
            require(!Files.isSymbolicLink(cursor)) { "download-path-invalid" }
        }
    }

    private fun requireSafeArtifactFile(file: File, root: File) {
        requireSafeDirectory(file.parentFile ?: root, root)
        val normalized = file.toPath().toAbsolutePath().normalize()
        require(normalized.startsWith(root.toPath().toAbsolutePath().normalize())) {
            "download-path-invalid"
        }
        require(!Files.isSymbolicLink(file.toPath())) { "download-path-invalid" }
    }

    private fun isArtifactDigestValid(file: File, expected: String, bytes: Long): Boolean {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != bytes) return false
        return runCatching { sha256(file) == expected }.getOrDefault(false)
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun parseRangeStart(value: String): Long? =
        RANGE.matchEntire(value.trim())?.groupValues?.get(1)?.toLongOrNull()

    private fun parseRangeTotal(value: String): Long? =
        RANGE.matchEntire(value.trim())?.groupValues?.get(3)?.takeIf { it != "*" }?.toLongOrNull()

    private companion object {
        const val READY_MARKER = ".ready.json"
        val RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")
        val downloadJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class PersistedDownloadDocument(
    val schemaVersion: Int = 1,
    val nextOrder: Long = 1L,
    val defaultPack: DealerAsrPackKey? = null,
    val mirrorBaseUrl: String? = null,
    val jobs: List<DealerAsrDownloadJob> = emptyList(),
)

private class DownloadRejected(val reason: String) : Exception(reason)

private class DownloadPaused : Exception()

private class DownloadCancelled : Exception()

private const val STATE_SCHEMA_VERSION = 2
