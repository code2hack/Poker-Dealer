package com.code2hack.dealer.asr

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

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
        val joinerPath: String,
        val tokensPath: String,
    ) : VerifiedAsrPackSource

    data class Files(
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val tokens: File,
    ) : VerifiedAsrPackSource
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

class DealerAsrRuntime private constructor(
    private val packSource: PackSource,
    private val loadNativeRuntime: () -> Unit,
) {
    constructor(context: Context) : this(
        packSource = FilePackSource(context.noBackupFilesDir.resolve(INSTALLED_PACKS_DIRECTORY)),
        loadNativeRuntime = ::loadSherpaRuntime,
    )

    internal constructor(assets: AssetManager) : this(
        packSource = AssetPackSource(assets),
        loadNativeRuntime = ::loadSherpaRuntime,
    )

    internal constructor(loadNativeRuntime: () -> Unit) : this(
        packSource = UnavailablePackSource,
        loadNativeRuntime = loadNativeRuntime,
    )

    internal constructor(installedPacksRoot: File, loadNativeRuntime: () -> Unit) : this(
        packSource = FilePackSource(installedPacksRoot),
        loadNativeRuntime = loadNativeRuntime,
    )

    private val ownerToken = Any()

    fun startup(pack: VerifiedAsrPack? = null): DealerAsrStartup {
        if (pack == null) return DealerAsrStartup.Unavailable("model-pack-not-installed")
        if (!pack.belongsTo(ownerToken)) {
            return DealerAsrStartup.Unavailable("model-pack-owner-mismatch")
        }
        if (pack.adapter != DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING) {
            return DealerAsrStartup.Unavailable("adapter-not-supported")
        }
        return try {
            loadNativeRuntime()
            DealerAsrStartup.Ready(
                DealerAsrCapabilities(
                    backend = "cpu",
                    sampleRateHz = 16_000,
                    adapters = setOf(DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING),
                ),
            )
        } catch (_: LinkageError) {
            DealerAsrStartup.Unavailable("runtime-load-failed")
        } catch (_: Exception) {
            DealerAsrStartup.Unavailable("runtime-load-failed")
        }
    }

    fun verifyPack(manifest: DealerAsrPackManifest): DealerAsrPackVerification {
        validate(manifest)?.let { return DealerAsrPackVerification.Rejected(it) }
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
            DealerAsrPackVerification.Rejected(rejected.reason)
        } catch (_: Exception) {
            DealerAsrPackVerification.Rejected("pack-unreadable")
        }
    }

    internal fun openParakeetStreaming(pack: VerifiedAsrPack): DealerAsrSession {
        check(pack.belongsTo(ownerToken)) { "ASR pack belongs to another runtime" }
        check(pack.adapter == DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING) {
            "ASR adapter is not implemented"
        }
        val paths = when (val source = pack.source) {
            is VerifiedAsrPackSource.Assets -> RecognizerPaths(
                assetManager = source.assetManager,
                encoder = source.encoderPath,
                decoder = source.decoderPath,
                joiner = source.joinerPath,
                tokens = source.tokensPath,
            )
            is VerifiedAsrPackSource.Files -> RecognizerPaths(
                assetManager = null,
                encoder = source.encoder.path,
                decoder = source.decoder.path,
                joiner = source.joiner.path,
                tokens = source.tokens.path,
            )
        }
        val recognizer = OnlineRecognizer(
            assetManager = paths.assetManager,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128),
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
        return DealerAsrSession(recognizer, recognizer.createStream())
    }

    private fun validate(manifest: DealerAsrPackManifest): String? {
        if (manifest.adapter != DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING) {
            return "adapter-not-supported"
        }
        if (!PACK_ID.matches(manifest.id) || !PACK_ID.matches(manifest.revision)) {
            return "pack-identity-invalid"
        }
        if (!isDataPath(manifest.encoderPath, ".onnx")) return "encoder-path-invalid"
        if (!isDataPath(manifest.decoderPath, ".onnx")) return "decoder-path-invalid"
        if (!isDataPath(manifest.joinerPath, ".onnx")) return "joiner-path-invalid"
        if (!isDataPath(manifest.tokensPath, ".txt")) return "tokens-path-invalid"
        if (listOf(
                manifest.encoderSha256,
                manifest.decoderSha256,
                manifest.joinerSha256,
                manifest.tokensSha256,
            ).any { !SHA256.matches(it) }
        ) {
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
            joinerPath = manifest.joinerPath,
            tokensPath = manifest.tokensPath,
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

        val files = manifest.artifacts().map { artifact ->
            val file = resolveArtifact(packRoot, artifact.path)
            if (sha256(file) != artifact.sha256) {
                throw PackRejected("pack-digest-mismatch")
            }
            file
        }
        return VerifiedAsrPackSource.Files(
            encoder = files[0],
            decoder = files[1],
            joiner = files[2],
            tokens = files[3],
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

private fun DealerAsrPackManifest.artifacts(): List<PackArtifact> = listOf(
    PackArtifact(encoderPath, encoderSha256),
    PackArtifact(decoderPath, decoderSha256),
    PackArtifact(joinerPath, joinerSha256),
    PackArtifact(tokensPath, tokensSha256),
)

private data class RecognizerPaths(
    val assetManager: AssetManager?,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

private class PackRejected(val reason: String) : Exception()

class DealerAsrSession internal constructor(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : Closeable {
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

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.release()
        } finally {
            recognizer.release()
        }
    }

    private fun decodeAvailable() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }
}
