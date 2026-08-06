package com.code2hack.dealer.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class DealerAsrRuntimeTest {
    @Test
    fun startupIsUnavailableWithoutVerifiedPack() {
        assertEquals(
            DealerAsrStartup.Unavailable("model-pack-not-installed"),
            DealerAsrRuntime {}.startup(),
        )
    }

    @Test
    fun startupReportsNativeRuntimeFailure() {
        assertEquals(
            DealerAsrStartup.Unavailable("runtime-load-failed"),
            DealerAsrRuntime { throw UnsatisfiedLinkError("test") }.startup(),
        )
    }

    @Test
    fun verifierRejectsUnsupportedAdapter() {
        assertEquals(
            DealerAsrPackVerification.Rejected("adapter-not-supported"),
            DealerAsrRuntime {}.verifyPack(manifest(adapter = DealerAsrAdapter.MOONSHINE_V2_OFFLINE)),
        )
    }

    @Test
    fun verifierRejectsUrlsAndExecutablePaths() {
        listOf(
            "https://example.invalid/model.onnx",
            "../model.onnx",
            "model.so",
            "model.jar",
        ).forEach { path ->
            assertEquals(
                DealerAsrPackVerification.Rejected("model-path-invalid"),
                DealerAsrRuntime {}.verifyPack(manifest(modelPath = path)),
            )
        }
    }

    private fun manifest(
        adapter: DealerAsrAdapter = DealerAsrAdapter.STREAMING_CTC,
        modelPath: String = "model.onnx",
    ) = DealerAsrPackManifest(
        id = "test-pack",
        revision = "r1",
        adapter = adapter,
        modelPath = modelPath,
        modelSha256 = "0".repeat(64),
        tokensPath = "tokens.txt",
        tokensSha256 = "0".repeat(64),
    )
}
