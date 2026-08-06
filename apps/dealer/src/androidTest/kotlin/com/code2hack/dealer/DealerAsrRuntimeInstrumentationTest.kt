package com.code2hack.dealer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.code2hack.dealer.asr.DealerAsrRuntime
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
        assertTrue(runtime.startup() is com.code2hack.dealer.asr.DealerAsrStartup.Ready)
        runtime.openStreamingCtc(
            modelPath = fixture.getProperty("model"),
            tokensPath = fixture.getProperty("tokens"),
            bpeVocabPath = fixture.getProperty("bpe"),
        ).use { session ->
            session.acceptPcm16(pcm)
            assertTrue("the smoke sample was not recognized", session.finish().isNotBlank())
        }
    }
}
