package com.code2hack.dealer.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.Closeable

enum class DealerAsrAdapter {
    PARAKEET_UNIFIED_STREAMING,
    MOONSHINE_V2_OFFLINE,
}

data class DealerAsrCapabilities(
    val backend: String = "cpu",
    val sampleRateHz: Int = 16_000,
    val adapters: Set<DealerAsrAdapter> = setOf(
        DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING,
        DealerAsrAdapter.MOONSHINE_V2_OFFLINE,
    ),
)

sealed interface DealerAsrStartup {
    data class Ready(val capabilities: DealerAsrCapabilities) : DealerAsrStartup

    data class Unavailable(val reason: String) : DealerAsrStartup
}

class DealerAsrRuntime(
    private val assets: AssetManager,
) {
    fun startup(): DealerAsrStartup = try {
        Class.forName(OnlineRecognizer::class.java.name)
        DealerAsrStartup.Ready(DealerAsrCapabilities())
    } catch (_: LinkageError) {
        DealerAsrStartup.Unavailable("runtime-load-failed")
    } catch (_: Exception) {
        DealerAsrStartup.Unavailable("runtime-load-failed")
    }

    internal fun openStreamingCtc(
        modelPath: String,
        tokensPath: String,
        bpeVocabPath: String? = null,
    ): DealerAsrSession {
        val model = checkedAssetPath(modelPath)
        val tokens = checkedAssetPath(tokensPath)
        val bpeVocab = bpeVocabPath?.takeIf(String::isNotBlank)?.let(::checkedAssetPath)
        val recognizer = OnlineRecognizer(
            assetManager = assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = model),
                    tokens = tokens,
                    numThreads = 1,
                    provider = "cpu",
                    modelingUnit = if (bpeVocab == null) "" else "bpe",
                    bpeVocab = bpeVocab.orEmpty(),
                ),
                enableEndpoint = true,
            ),
        )
        return DealerAsrSession(recognizer, recognizer.createStream())
    }

    private fun checkedAssetPath(path: String): String {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                "://" !in path &&
                '\\' !in path,
        ) { "ASR asset path must be a relative data path" }
        require(path.split('/').none { it == ".." }) { "ASR asset path escapes its pack" }
        return path
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
