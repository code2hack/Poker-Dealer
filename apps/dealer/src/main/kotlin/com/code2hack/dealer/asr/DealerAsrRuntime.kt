package com.code2hack.dealer.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.Closeable
import java.security.MessageDigest

enum class DealerAsrAdapter {
    STREAMING_CTC,
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
    val modelPath: String,
    val modelSha256: String,
    val tokensPath: String,
    val tokensSha256: String,
    val bpeVocabPath: String? = null,
    val bpeVocabSha256: String? = null,
)

sealed interface DealerAsrPackVerification {
    data class Verified(val pack: VerifiedAsrPack) : DealerAsrPackVerification

    data class Rejected(val reason: String) : DealerAsrPackVerification
}

class VerifiedAsrPack private constructor(
    val id: String,
    val revision: String,
    val adapter: DealerAsrAdapter,
    internal val modelPath: String,
    internal val tokensPath: String,
    internal val bpeVocabPath: String?,
    private val ownerToken: Any,
) {
    internal fun belongsTo(owner: Any): Boolean = ownerToken === owner

    internal companion object {
        fun create(
            id: String,
            revision: String,
            adapter: DealerAsrAdapter,
            modelPath: String,
            tokensPath: String,
            bpeVocabPath: String?,
            ownerToken: Any,
        ): VerifiedAsrPack = VerifiedAsrPack(
            id = id,
            revision = revision,
            adapter = adapter,
            modelPath = modelPath,
            tokensPath = tokensPath,
            bpeVocabPath = bpeVocabPath,
            ownerToken = ownerToken,
        )
    }
}

class DealerAsrRuntime private constructor(
    private val assets: AssetManager?,
    private val loadNativeRuntime: () -> Unit,
) {
    constructor(assets: AssetManager) : this(assets, ::loadSherpaRuntime)

    internal constructor(loadNativeRuntime: () -> Unit) : this(null, loadNativeRuntime)

    private val ownerToken = Any()

    fun startup(pack: VerifiedAsrPack? = null): DealerAsrStartup = try {
        loadNativeRuntime()
        when {
            pack == null -> DealerAsrStartup.Unavailable("model-pack-not-installed")
            !pack.belongsTo(ownerToken) -> DealerAsrStartup.Unavailable("model-pack-owner-mismatch")
            else -> DealerAsrStartup.Ready(
                DealerAsrCapabilities(
                    backend = "cpu",
                    sampleRateHz = 16_000,
                    adapters = setOf(pack.adapter),
                ),
            )
        }
    } catch (_: LinkageError) {
        DealerAsrStartup.Unavailable("runtime-load-failed")
    } catch (_: Exception) {
        DealerAsrStartup.Unavailable("runtime-load-failed")
    }

    fun verifyPack(manifest: DealerAsrPackManifest): DealerAsrPackVerification {
        validate(manifest)?.let { return DealerAsrPackVerification.Rejected(it) }
        val source = assets ?: return DealerAsrPackVerification.Rejected("asset-source-unavailable")
        val paths = buildList {
            add(manifest.modelPath to manifest.modelSha256)
            add(manifest.tokensPath to manifest.tokensSha256)
            if (manifest.bpeVocabPath != null) {
                add(manifest.bpeVocabPath to requireNotNull(manifest.bpeVocabSha256))
            }
        }
        return try {
            if (paths.any { (path, expected) -> sha256(source, path) != expected }) {
                DealerAsrPackVerification.Rejected("pack-digest-mismatch")
            } else {
                DealerAsrPackVerification.Verified(
                    VerifiedAsrPack.create(
                        id = manifest.id,
                        revision = manifest.revision,
                        adapter = manifest.adapter,
                        modelPath = manifest.modelPath,
                        tokensPath = manifest.tokensPath,
                        bpeVocabPath = manifest.bpeVocabPath,
                        ownerToken = ownerToken,
                    ),
                )
            }
        } catch (_: Exception) {
            DealerAsrPackVerification.Rejected("pack-unreadable")
        }
    }

    internal fun openStreamingCtc(pack: VerifiedAsrPack): DealerAsrSession {
        check(pack.belongsTo(ownerToken)) { "ASR pack belongs to another runtime" }
        check(pack.adapter == DealerAsrAdapter.STREAMING_CTC) { "ASR adapter is not implemented" }
        val source = requireNotNull(assets) { "ASR asset source is unavailable" }
        val recognizer = OnlineRecognizer(
            assetManager = source,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = pack.modelPath),
                    tokens = pack.tokensPath,
                    numThreads = 1,
                    provider = "cpu",
                    modelingUnit = if (pack.bpeVocabPath == null) "" else "bpe",
                    bpeVocab = pack.bpeVocabPath.orEmpty(),
                ),
                enableEndpoint = true,
            ),
        )
        return DealerAsrSession(recognizer, recognizer.createStream())
    }

    private fun validate(manifest: DealerAsrPackManifest): String? {
        if (manifest.adapter != DealerAsrAdapter.STREAMING_CTC) return "adapter-not-supported"
        if (!PACK_ID.matches(manifest.id) || !PACK_ID.matches(manifest.revision)) {
            return "pack-identity-invalid"
        }
        if (!isDataPath(manifest.modelPath, ".onnx")) return "model-path-invalid"
        if (!isDataPath(manifest.tokensPath, ".txt")) return "tokens-path-invalid"
        if (!SHA256.matches(manifest.modelSha256) || !SHA256.matches(manifest.tokensSha256)) {
            return "pack-digest-invalid"
        }
        if ((manifest.bpeVocabPath == null) != (manifest.bpeVocabSha256 == null)) {
            return "bpe-manifest-incomplete"
        }
        if (manifest.bpeVocabPath != null &&
            (!isDataPath(manifest.bpeVocabPath, ".model") ||
                !SHA256.matches(requireNotNull(manifest.bpeVocabSha256)))
        ) {
            return "bpe-path-invalid"
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
        val PACK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SHA256 = Regex("[0-9a-f]{64}")

        fun loadSherpaRuntime() {
            Class.forName(OnlineRecognizer::class.java.name)
        }

        fun sha256(source: AssetManager, path: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            source.open(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }
    }
}

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
