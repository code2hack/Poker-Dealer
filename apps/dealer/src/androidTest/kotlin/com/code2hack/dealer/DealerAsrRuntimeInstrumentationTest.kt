package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.dealer.asr.DealerAsrAdapter
import com.code2hack.dealer.asr.DealerAsrPackManifest
import com.code2hack.dealer.asr.DealerAsrPackVerification
import com.code2hack.dealer.asr.DealerAsrRuntime
import com.code2hack.dealer.asr.DealerAsrStartup
import com.k2fsa.sherpa.onnx.WaveReader
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DealerAsrRuntimeInstrumentationTest {
    @Test
    fun arm64RuntimeLoadsAndRecognizesFixedPcmSample() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = Properties().apply {
            context.assets.open("sherpa-smoke.properties").use(::load)
        }
        val wave = WaveReader.readWave(context.assets, fixture.getProperty("sample"))
        assertEquals(16_000, wave.sampleRate)

        val pcm = ByteArray(wave.samples.size * 2)
        wave.samples.forEachIndexed { index, sample ->
            val value = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }

        val runtime = DealerAsrRuntime(context.assets)
        assertEquals(
            DealerAsrStartup.Unavailable("model-pack-not-installed"),
            runtime.startup(),
        )
        val verification = runtime.verifyPack(
            DealerAsrPackManifest(
                id = fixture.getProperty("packId"),
                revision = fixture.getProperty("packRevision"),
                adapter = DealerAsrAdapter.valueOf(fixture.getProperty("adapter")),
                modelPath = fixture.getProperty("model"),
                modelSha256 = fixture.getProperty("modelSha256"),
                tokensPath = fixture.getProperty("tokens"),
                tokensSha256 = fixture.getProperty("tokensSha256"),
                bpeVocabPath = fixture.getProperty("bpe"),
                bpeVocabSha256 = fixture.getProperty("bpeSha256"),
            ),
        )
        assertTrue(verification is DealerAsrPackVerification.Verified)
        val pack = (verification as DealerAsrPackVerification.Verified).pack
        val startup = runtime.startup(pack)
        assertTrue(startup is DealerAsrStartup.Ready)
        assertEquals(
            setOf(DealerAsrAdapter.STREAMING_CTC),
            (startup as DealerAsrStartup.Ready).capabilities.adapters,
        )
        runtime.openStreamingCtc(pack).use { session ->
            session.acceptPcm16(pcm)
            assertTrue("the smoke sample was not recognized", session.finish().isNotBlank())
        }
    }
}
