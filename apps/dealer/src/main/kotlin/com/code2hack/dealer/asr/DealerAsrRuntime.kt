package com.code2hack.dealer.asr

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

enum class DealerAsrAdapter {
    PARAKEET_UNIFIED_STREAMING,
    MOONSHINE_V2_OFFLINE,
}

class DealerAsrCapabilities internal constructor(
    val backend: String,
    val sampleRateHz: Int,
    val adapters: Set<DealerAsrAdapter>,
)

sealed interface DealerAsrStartup {
    data class Ready(val capabilities: DealerAsrCapabilities) : DealerAsrStartup

    data class Unavailable(val reason: String) : DealerAsrStartup
}

data class DealerAsrPackManifest(
    val id: String,
    val revision: String,
    val adapter: DealerAsrAdapter,
    val encoderPath: String,
    val encoderSha256: String,
    val decoderPath: String,
    val decoderSha256: String,
    val joinerPath: String,
    val joinerSha256: String,
    val tokensPath: String,
    val tokensSha256: String,
    val vadPath: String? = null,
    val vadSha256: String? = null,
)

sealed interface DealerAsrPackVerification {
    data class Verified(val pack: VerifiedAsrPack) : DealerAsrPackVerification

    data class Rejected(val reason: String) : DealerAsrPackVerification
}

internal sealed interface VerifiedAsrPackSource {
    data class Assets(
        val assetManager: AssetManager,
        val encoderPath: String,
        val decoderPath: String,
        val joinerPath: String?,
        val tokensPath: String,
        val vadPath: String?,
        val expectedSha256: List<String>,
    ) : VerifiedAsrPackSource

    data class Files(
        val encoder: File,
        val decoder: File,
        val joiner: File?,
        val tokens: File,
        val vad: File?,
        val expectedSha256: List<String>,
    ) : VerifiedAsrPackSource

    fun revalidate() {
        val valid = when (this) {
            is Assets -> runCatching {
                listOfNotNull(
                    encoderPath,
                    decoderPath,
                    joinerPath,
                    tokensPath,
                    vadPath,
                )
                    .zip(expectedSha256)
                    .all { (path, expected) -> sha256(assetManager, path) == expected }
            }.getOrDefault(false)
            is Files -> listOfNotNull(encoder, decoder, joiner, tokens, vad)
                .zip(expectedSha256)
                .all { (file, expected) ->
                    java.nio.file.Files.isRegularFile(
                        file.toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    ) && runCatching { sha256(file) == expected }.getOrDefault(false)
                }
        }
        check(valid) { "ASR pack changed after verification" }
    }
}

class VerifiedAsrPack private constructor(
    val id: String,
    val revision: String,
    val adapter: DealerAsrAdapter,
    internal val source: VerifiedAsrPackSource,
    private val ownerToken: Any,
) {
    internal fun belongsTo(owner: Any): Boolean = ownerToken === owner

    internal companion object {
        fun create(
            id: String,
            revision: String,
            adapter: DealerAsrAdapter,
            source: VerifiedAsrPackSource,
            ownerToken: Any,
        ): VerifiedAsrPack = VerifiedAsrPack(
            id = id,
            revision = revision,
            adapter = adapter,
            source = source,
            ownerToken = ownerToken,
        )
    }
}

internal interface DealerAsrRuntimeCallbacks {
    fun onSessionStarted(key: DealerAsrPackKey)

    fun onSessionClosed(key: DealerAsrPackKey)

    fun onPackFailure(key: DealerAsrPackKey, reason: String)
}

private object NoopDealerAsrRuntimeCallbacks : DealerAsrRuntimeCallbacks {
    override fun onSessionStarted(key: DealerAsrPackKey) = Unit

    override fun onSessionClosed(key: DealerAsrPackKey) = Unit

    override fun onPackFailure(key: DealerAsrPackKey, reason: String) = Unit
}

internal class DealerAsrDownloadLifecycle(
    private val manager: () -> DealerAsrDownloadManager,
) : DealerAsrRuntimeCallbacks {
    private val lock = Any()
    private val sessions = mutableMapOf<DealerAsrPackKey, ArrayDeque<DealerAsrSessionProfile>>()

    override fun onSessionStarted(key: DealerAsrPackKey) {
        synchronized(lock) {
            val profile = runBlocking(Dispatchers.IO) { manager().beginAsrSession(key) }
            sessions.getOrPut(key, ::ArrayDeque).addLast(profile)
        }
    }

    override fun onSessionClosed(key: DealerAsrPackKey) {
        synchronized(lock) {
            val pending = sessions[key] ?: return@synchronized
            val session = pending.lastOrNull() ?: return@synchronized
            runBlocking(Dispatchers.IO) { manager().endAsrSession(session) }
            pending.removeLast()
            if (pending.isEmpty()) sessions.remove(key)
        }
    }

    override fun onPackFailure(key: DealerAsrPackKey, reason: String) {
        synchronized(lock) {
            runBlocking(Dispatchers.IO) { manager().markRepairNeeded(key, reason) }
        }
    }

    fun unloadIdleDefault(key: DealerAsrPackKey) {
        manager().evictIdleRecognizer(key)
    }
}

class DealerAsrRuntime private constructor(
    private val packSource: PackSource,
    private val loadNativeRuntime: () -> Unit,
    private val callbacks: DealerAsrRuntimeCallbacks = NoopDealerAsrRuntimeCallbacks,
) {
    constructor(context: Context) : this(
        packSource = FilePackSource(context.noBackupFilesDir.resolve(INSTALLED_PACKS_DIRECTORY)),
        loadNativeRuntime = ::loadSherpaRuntime,
    )

    internal constructor(context: Context, callbacks: DealerAsrRuntimeCallbacks) : this(
        packSource = FilePackSource(context.noBackupFilesDir.resolve(INSTALLED_PACKS_DIRECTORY)),
        loadNativeRuntime = ::loadSherpaRuntime,
        callbacks = callbacks,
    )

    internal constructor(assets: AssetManager) : this(
        packSource = AssetPackSource(assets),
        loadNativeRuntime = ::loadSherpaRuntime,
    )

    internal constructor(loadNativeRuntime: () -> Unit) : this(
        packSource = UnavailablePackSource,
        loadNativeRuntime = loadNativeRuntime,
    )

    internal constructor(
        loadNativeRuntime: () -> Unit,
        callbacks: DealerAsrRuntimeCallbacks,
    ) : this(
        packSource = UnavailablePackSource,
        loadNativeRuntime = loadNativeRuntime,
        callbacks = callbacks,
    )

    internal constructor(installedPacksRoot: File, loadNativeRuntime: () -> Unit) : this(
        packSource = FilePackSource(installedPacksRoot),
        loadNativeRuntime = loadNativeRuntime,
    )

    internal constructor(
        installedPacksRoot: File,
        loadNativeRuntime: () -> Unit,
        callbacks: DealerAsrRuntimeCallbacks,
    ) : this(
        packSource = FilePackSource(installedPacksRoot),
        loadNativeRuntime = loadNativeRuntime,
        callbacks = callbacks,
    )

    private val ownerToken = Any()

    fun startup(pack: VerifiedAsrPack? = null): DealerAsrStartup {
        if (pack == null) return DealerAsrStartup.Unavailable("model-pack-not-installed")
        if (!pack.belongsTo(ownerToken)) {
            return DealerAsrStartup.Unavailable("model-pack-owner-mismatch")
        }
        return try {
            loadNativeRuntime()
            DealerAsrStartup.Ready(
                DealerAsrCapabilities(
                    backend = "cpu",
                    sampleRateHz = 16_000,
                    adapters = DealerAsrAdapter.entries.toSet(),
                ),
            )
        } catch (_: LinkageError) {
            notifyPackFailure(pack, "runtime-load-failed")
            DealerAsrStartup.Unavailable("runtime-load-failed")
        } catch (_: Exception) {
            notifyPackFailure(pack, "runtime-load-failed")
            DealerAsrStartup.Unavailable("runtime-load-failed")
        }
    }

    fun verifyPack(manifest: DealerAsrPackManifest): DealerAsrPackVerification {
        validate(manifest)?.let {
            notifyManifestFailure(manifest, it)
            return DealerAsrPackVerification.Rejected(it)
        }
        return try {
            DealerAsrPackVerification.Verified(
                VerifiedAsrPack.create(
                    id = manifest.id,
                    revision = manifest.revision,
                    adapter = manifest.adapter,
                    source = packSource.verify(manifest),
                    ownerToken = ownerToken,
                ),
            )
        } catch (rejected: PackRejected) {
            notifyManifestFailure(manifest, rejected.reason)
            DealerAsrPackVerification.Rejected(rejected.reason)
        } catch (_: Exception) {
            notifyManifestFailure(manifest, "pack-unreadable")
            DealerAsrPackVerification.Rejected("pack-unreadable")
        }
    }

    internal fun checkInstalledPack(job: DealerAsrDownloadJob): String? {
        val manifest = job.runtimeManifest() ?: return "pack-manifest-invalid"
        return when (val verification = verifyPack(manifest)) {
            is DealerAsrPackVerification.Rejected -> verification.reason
            is DealerAsrPackVerification.Verified -> when (val startup = startup(verification.pack)) {
                is DealerAsrStartup.Ready -> null
                is DealerAsrStartup.Unavailable -> startup.reason
            }
        }
    }

    internal fun openParakeetStreaming(pack: VerifiedAsrPack): DealerAsrSession =
        openStreaming(pack, featureDim = 128, profile = null)

    internal fun openParakeetStreaming(
        pack: VerifiedAsrPack,
        profile: DealerAsrProfile,
    ): DealerAsrSession {
        check(profile.packId == pack.id && profile.revision == pack.revision) {
            "ASR profile does not match pack"
        }
        return openStreaming(pack, featureDim = 128, profile = profile)
    }

    internal fun openMoonshineOffline(
        pack: VerifiedAsrPack,
        profile: DealerAsrProfile,
        spoolStore: DealerAsrOfflineSpoolStore,
    ): DealerAsrRecognizer {
        check(profile.packId == pack.id && profile.revision == pack.revision) {
            "ASR profile does not match pack"
        }
        check(pack.belongsTo(ownerToken)) { "ASR pack belongs to another runtime" }
        check(pack.adapter == DealerAsrAdapter.MOONSHINE_V2_OFFLINE) {
            "ASR adapter is not implemented"
        }
        val source = pack.source
        val vadPath = when (source) {
            is VerifiedAsrPackSource.Assets -> source.vadPath
            is VerifiedAsrPackSource.Files -> source.vad?.path
        } ?: error("ASR VAD artifact is missing")
        val paths = when (source) {
            is VerifiedAsrPackSource.Assets -> OfflineRecognizerPaths(
                assetManager = source.assetManager,
                encoder = source.encoderPath,
                decoder = source.decoderPath,
                tokens = source.tokensPath,
                vad = vadPath,
            )
            is VerifiedAsrPackSource.Files -> OfflineRecognizerPaths(
                assetManager = null,
                encoder = source.encoder.path,
                decoder = source.decoder.path,
                tokens = source.tokens.path,
                vad = vadPath,
            )
        }
        pack.source.revalidate()
        var offlineRecognizer: OfflineRecognizer? = null
        var vad: Vad? = null
        var spool: DealerAsrOfflineSpool? = null
        return try {
            offlineRecognizer = OfflineRecognizer(
                assetManager = paths.assetManager,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            encoder = paths.encoder,
                            mergedDecoder = paths.decoder,
                        ),
                        tokens = paths.tokens,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                ),
            )
            vad = Vad(
                assetManager = paths.assetManager,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = paths.vad,
                        threshold = 0.2f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 15f,
                    ),
                    sampleRate = 16_000,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            spool = spoolStore.open(pack.id)
            DealerAsrOfflineSession(
                recognizer = requireNotNull(offlineRecognizer),
                vad = requireNotNull(vad),
                spool = requireNotNull(spool),
            )
        } catch (failure: Throwable) {
            spool?.close()
            vad?.release()
            offlineRecognizer?.release()
            throw failure
        }
    }

    /** Test-only compact transducer fixture; it is not a production adapter capability. */
    internal fun openInstrumentationStreamingFixture(pack: VerifiedAsrPack): DealerAsrSession =
        openStreaming(pack, featureDim = 80, profile = null)

    private fun openStreaming(
        pack: VerifiedAsrPack,
        featureDim: Int,
        profile: DealerAsrProfile?,
    ): DealerAsrSession {
        check(pack.belongsTo(ownerToken)) { "ASR pack belongs to another runtime" }
        check(pack.adapter == DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING) {
            "ASR adapter is not implemented"
        }
        val key = DealerAsrPackKey(pack.id, pack.revision)
        var sessionStarted = false
        var recognizer: OnlineRecognizer? = null
        return try {
            pack.source.revalidate()
            callbacks.onSessionStarted(key)
            sessionStarted = true
            val paths = when (val source = pack.source) {
                is VerifiedAsrPackSource.Assets -> RecognizerPaths(
                    assetManager = source.assetManager,
                    encoder = source.encoderPath,
                    decoder = source.decoderPath,
                    joiner = requireNotNull(source.joinerPath),
                    tokens = source.tokensPath,
                )
                is VerifiedAsrPackSource.Files -> RecognizerPaths(
                    assetManager = null,
                    encoder = source.encoder.path,
                    decoder = source.decoder.path,
                    joiner = requireNotNull(source.joiner).path,
                    tokens = source.tokens.path,
                )
            }
            val createdRecognizer = OnlineRecognizer(
                assetManager = paths.assetManager,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = featureDim),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder,
                            decoder = paths.decoder,
                            joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                    enableEndpoint = true,
                ),
            )
            recognizer = createdRecognizer
            val session = DealerAsrSession(
                recognizer = createdRecognizer,
                stream = createdRecognizer.createStream(),
                profile = profile,
                onClosed = { callbacks.onSessionClosed(key) },
            )
            session
        } catch (failure: Throwable) {
            recognizer?.let {
                try {
                    it.release()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            if (sessionStarted) {
                try {
                    callbacks.onSessionClosed(key)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            try {
                callbacks.onPackFailure(key, "pack-unloadable")
            } catch (notificationFailure: Throwable) {
                failure.addSuppressed(notificationFailure)
            }
            throw failure
        }
    }

    private fun notifyManifestFailure(manifest: DealerAsrPackManifest, reason: String) {
        if (PACK_ID.matches(manifest.id) && PACK_ID.matches(manifest.revision)) {
            notifyPackFailure(DealerAsrPackKey(manifest.id, manifest.revision), reason)
        }
    }

    private fun notifyPackFailure(pack: VerifiedAsrPack, reason: String) {
        notifyPackFailure(DealerAsrPackKey(pack.id, pack.revision), reason)
    }

    private fun notifyPackFailure(key: DealerAsrPackKey, reason: String) {
        callbacks.onPackFailure(key, reason)
    }

    private fun validate(manifest: DealerAsrPackManifest): String? {
        if (!PACK_ID.matches(manifest.id) || !PACK_ID.matches(manifest.revision)) {
            return "pack-identity-invalid"
        }
        val modelSuffix = when (manifest.adapter) {
            DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING -> ".onnx"
            DealerAsrAdapter.MOONSHINE_V2_OFFLINE -> ".ort"
        }
        if (!isDataPath(manifest.encoderPath, modelSuffix)) return "encoder-path-invalid"
        if (!isDataPath(manifest.decoderPath, modelSuffix)) return "decoder-path-invalid"
        if (manifest.adapter == DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING &&
            !isDataPath(manifest.joinerPath, ".onnx")
        ) {
            return "joiner-path-invalid"
        }
        if (!isDataPath(manifest.tokensPath, ".txt")) return "tokens-path-invalid"
        val digests = when (manifest.adapter) {
            DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING -> {
                if (manifest.vadPath != null || manifest.vadSha256 != null) return "vad-path-invalid"
                listOf(
                    manifest.encoderSha256,
                    manifest.decoderSha256,
                    manifest.joinerSha256,
                    manifest.tokensSha256,
                )
            }
            DealerAsrAdapter.MOONSHINE_V2_OFFLINE -> {
                if (manifest.vadPath == null || !isDataPath(manifest.vadPath, ".onnx") ||
                    manifest.vadSha256 == null
                ) {
                    return "vad-path-invalid"
                }
                listOf(
                    manifest.encoderSha256,
                    manifest.decoderSha256,
                    manifest.tokensSha256,
                    manifest.vadSha256,
                )
            }
        }
        if (digests.any { !SHA256.matches(it) }) {
            return "pack-digest-invalid"
        }
        return null
    }

    private fun isDataPath(path: String, suffix: String): Boolean {
        if (path.isBlank() || path.length > 256 || !path.endsWith(suffix)) return false
        if (path.startsWith('/') || '\\' in path || "://" in path || '\u0000' in path) return false
        return path.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." && ':' !in segment
        }
    }

    private companion object {
        const val INSTALLED_PACKS_DIRECTORY = "asr-packs"
        val PACK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SHA256 = Regex("[0-9a-f]{64}")

        fun loadSherpaRuntime() {
            Class.forName(OnlineRecognizer::class.java.name)
        }

    }
}

private fun sha256(source: AssetManager, path: String): String = source.open(path).use(::sha256)

private fun sha256(file: File): String = FileInputStream(file).use(::sha256)

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count <= 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private sealed interface PackSource {
    fun verify(manifest: DealerAsrPackManifest): VerifiedAsrPackSource
}

private object UnavailablePackSource : PackSource {
    override fun verify(manifest: DealerAsrPackManifest): VerifiedAsrPackSource {
        throw PackRejected("pack-source-unavailable")
    }
}

private class AssetPackSource(private val assets: AssetManager) : PackSource {
    override fun verify(manifest: DealerAsrPackManifest): VerifiedAsrPackSource {
        val artifacts = manifest.artifacts()
        if (artifacts.any { artifact -> sha256(assets, artifact.path) != artifact.sha256 }) {
            throw PackRejected("pack-digest-mismatch")
        }
        return VerifiedAsrPackSource.Assets(
            assetManager = assets,
            encoderPath = manifest.encoderPath,
            decoderPath = manifest.decoderPath,
            joinerPath = manifest.joinerPath.takeIf(String::isNotBlank),
            tokensPath = manifest.tokensPath,
            vadPath = manifest.vadPath,
            expectedSha256 = artifacts.map(PackArtifact::sha256),
        )
    }
}

private class FilePackSource(private val installedPacksRoot: File) : PackSource {
    override fun verify(manifest: DealerAsrPackManifest): VerifiedAsrPackSource {
        val configuredRoot = installedPacksRoot.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(configuredRoot)) throw PackRejected("pack-symlink")
        val root = installedPacksRoot.canonicalFile
        val rootPath = root.toPath()

        val idPath = rootPath.resolve(manifest.id)
        val revisionPath = idPath.resolve(manifest.revision)
        if (Files.isSymbolicLink(idPath) || Files.isSymbolicLink(revisionPath)) {
            throw PackRejected("pack-symlink")
        }
        val packRoot = revisionPath.toFile().canonicalFile
        val packRootPath = packRoot.toPath()
        if (!packRootPath.startsWith(rootPath)) throw PackRejected("pack-path-invalid")
        if (!packRoot.isDirectory) throw PackRejected("model-pack-not-installed")

        val artifacts = manifest.artifacts()
        val files = artifacts.map { artifact ->
            val file = resolveArtifact(packRoot, artifact.path)
            if (sha256(file) != artifact.sha256) {
                throw PackRejected("pack-digest-mismatch")
            }
            file
        }
        val byPath = artifacts.mapIndexed { index, artifact -> artifact.path to files[index] }.toMap()
        return VerifiedAsrPackSource.Files(
            encoder = byPath.getValue(manifest.encoderPath),
            decoder = byPath.getValue(manifest.decoderPath),
            joiner = manifest.joinerPath.takeIf(String::isNotBlank)?.let(byPath::getValue),
            tokens = byPath.getValue(manifest.tokensPath),
            vad = manifest.vadPath?.let(byPath::getValue),
            expectedSha256 = artifacts.map(PackArtifact::sha256),
        )
    }

    private fun resolveArtifact(packRoot: File, relativePath: String): File {
        val packRootPath = packRoot.toPath()
        val lexical = packRootPath.resolve(relativePath).normalize()
        if (!lexical.startsWith(packRootPath)) throw PackRejected("pack-path-invalid")

        var cursor = packRootPath
        relativePath.split('/').forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.isSymbolicLink(cursor)) throw PackRejected("pack-symlink")
        }

        val canonical = lexical.toFile().canonicalFile
        if (!canonical.toPath().startsWith(packRootPath)) throw PackRejected("pack-path-invalid")
        if (!Files.isRegularFile(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw PackRejected("pack-unreadable")
        }
        return canonical
    }
}

private data class PackArtifact(val path: String, val sha256: String)

internal fun DealerAsrDownloadJob.runtimeManifest(): DealerAsrPackManifest? {
    if (artifacts.isEmpty()) return null
    fun artifact(prefix: String, suffix: String): DealerAsrDownloadArtifact? =
        artifacts.firstOrNull { artifact ->
            val name = artifact.path.substringAfterLast('/')
            name.startsWith(prefix) && name.endsWith(suffix)
        }
    val tokens = artifacts.firstOrNull { it.path.substringAfterLast('/') == "tokens.txt" } ?: return null
    return when (adapter) {
        DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING -> {
            val encoder = artifact("encoder", ".onnx") ?: return null
            val decoder = artifact("decoder", ".onnx") ?: return null
            val joiner = artifact("joiner", ".onnx") ?: return null
            DealerAsrPackManifest(
                id = packId,
                revision = revision,
                adapter = adapter,
                encoderPath = encoder.path,
                encoderSha256 = encoder.sha256,
                decoderPath = decoder.path,
                decoderSha256 = decoder.sha256,
                joinerPath = joiner.path,
                joinerSha256 = joiner.sha256,
                tokensPath = tokens.path,
                tokensSha256 = tokens.sha256,
            )
        }
        DealerAsrAdapter.MOONSHINE_V2_OFFLINE -> {
            val encoder = artifact("encoder_model", ".ort") ?: return null
            val decoder = artifact("decoder_model_merged", ".ort") ?: return null
            val vad = artifacts.firstOrNull { it.path.substringAfterLast('/') == "silero_vad.onnx" }
                ?: return null
            DealerAsrPackManifest(
                id = packId,
                revision = revision,
                adapter = adapter,
                encoderPath = encoder.path,
                encoderSha256 = encoder.sha256,
                decoderPath = decoder.path,
                decoderSha256 = decoder.sha256,
                joinerPath = "",
                joinerSha256 = "",
                tokensPath = tokens.path,
                tokensSha256 = tokens.sha256,
                vadPath = vad.path,
                vadSha256 = vad.sha256,
            )
        }
    }
}

private fun DealerAsrPackManifest.artifacts(): List<PackArtifact> = when (adapter) {
    DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING -> listOf(
        PackArtifact(encoderPath, encoderSha256),
        PackArtifact(decoderPath, decoderSha256),
        PackArtifact(joinerPath, joinerSha256),
        PackArtifact(tokensPath, tokensSha256),
    )
    DealerAsrAdapter.MOONSHINE_V2_OFFLINE -> listOf(
        PackArtifact(encoderPath, encoderSha256),
        PackArtifact(decoderPath, decoderSha256),
        PackArtifact(tokensPath, tokensSha256),
        PackArtifact(requireNotNull(vadPath), requireNotNull(vadSha256)),
    )
}

private data class RecognizerPaths(
    val assetManager: AssetManager?,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

private data class OfflineRecognizerPaths(
    val assetManager: AssetManager?,
    val encoder: String,
    val decoder: String,
    val tokens: String,
    val vad: String,
)

private class PackRejected(val reason: String) : Exception()

class DealerAsrSession internal constructor(
    private val recognizer: OnlineRecognizer,
    stream: OnlineStream,
    internal val profile: DealerAsrProfile? = null,
    private val onClosed: () -> Unit = {},
) : Closeable {
    private var stream = stream
    private var closed = false
    private var finished = false

    fun acceptPcm16(pcm: ByteArray) {
        check(!closed) { "ASR session is closed" }
        check(!finished) { "ASR session is finished" }
        require(pcm.size % 2 == 0) { "PCM16 data must contain complete samples" }
        if (pcm.isEmpty()) return

        val samples = FloatArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768.0f
        }
        stream.acceptWaveform(samples, sampleRate = 16_000)
        decodeAvailable()
    }

    fun provisionalText(): String {
        check(!closed) { "ASR session is closed" }
        return recognizer.getResult(stream).text
    }

    fun finish(): String {
        check(!closed) { "ASR session is closed" }
        if (!finished) {
            stream.inputFinished()
            decodeAvailable()
            finished = true
        }
        return provisionalText()
    }

    internal fun commitSlice(): String {
        val text = finish()
        resetStream()
        return text
    }

    internal fun discardSlice() {
        check(!closed) { "ASR session is closed" }
        resetStream()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.release()
        } finally {
            try {
                recognizer.release()
            } finally {
                onClosed()
            }
        }
    }

    private fun decodeAvailable() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    private fun resetStream() {
        stream.release()
        stream = recognizer.createStream()
        finished = false
    }
}
