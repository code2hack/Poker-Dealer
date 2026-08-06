package com.code2hack.dealer.asr

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrRuntimeTest {
    @Test
    fun startupIsUnavailableWithoutVerifiedPack() {
        assertEquals(
            DealerAsrStartup.Unavailable("model-pack-not-installed"),
            DealerAsrRuntime { throw UnsatisfiedLinkError("must not load without a pack") }.startup(),
        )
    }

    @Test
    fun startupReportsNativeRuntimeFailureAfterPackVerification() {
        val fixture = installedPack()
        try {
            val runtime = DealerAsrRuntime(fixture.root) {
                throw UnsatisfiedLinkError("test")
            }
            val pack = verifiedPack(runtime, fixture.manifest)
            assertEquals(
                DealerAsrStartup.Unavailable("runtime-load-failed"),
                runtime.startup(pack),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun verifierRejectsUnsupportedMoonshineUntilItsRuntimeExists() {
        assertEquals(
            DealerAsrPackVerification.Rejected("adapter-not-supported"),
            DealerAsrRuntime {}.verifyPack(manifest(adapter = DealerAsrAdapter.MOONSHINE_V2_OFFLINE)),
        )
    }

    @Test
    fun verifierRejectsUrlsTraversalAndExecutablePaths() {
        listOf(
            "https://example.invalid/encoder.onnx",
            "../encoder.onnx",
            "encoder.so",
            "encoder.jar",
        ).forEach { path ->
            assertEquals(
                DealerAsrPackVerification.Rejected("encoder-path-invalid"),
                DealerAsrRuntime {}.verifyPack(manifest(encoderPath = path)),
            )
        }
    }

    @Test
    fun fileSourceVerifiesAllDigestsBeforeCreatingOwnerBoundHandle() {
        val fixture = installedPack()
        try {
            val runtime = DealerAsrRuntime(fixture.root) {}
            val pack = verifiedPack(runtime, fixture.manifest)

            val startup = runtime.startup(pack)
            assertTrue(startup is DealerAsrStartup.Ready)
            assertEquals(
                setOf(DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING),
                (startup as DealerAsrStartup.Ready).capabilities.adapters,
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun fileSourceDoesNotCreateHandleBeforePackIsInstalled() {
        val root = Files.createTempDirectory("dealer-asr-empty").toFile()
        try {
            assertEquals(
                DealerAsrPackVerification.Rejected("model-pack-not-installed"),
                DealerAsrRuntime(root) {}.verifyPack(manifest()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileSourceRejectsDigestMismatch() {
        val fixture = installedPack()
        try {
            val runtime = DealerAsrRuntime(fixture.root) {}
            assertEquals(
                DealerAsrPackVerification.Rejected("pack-digest-mismatch"),
                runtime.verifyPack(fixture.manifest.copy(encoderSha256 = "0".repeat(64))),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun fileSourceRejectsSymlinkedArtifact() {
        val fixture = installedPack()
        val outside = Files.createTempDirectory("dealer-asr-outside").toFile()
        try {
            val encoder = fixture.root.resolve("test-pack/r1/encoder.onnx")
            encoder.delete()
            val outsideEncoder = outside.resolve("encoder.onnx")
            Files.write(outsideEncoder.toPath(), "encoder".toByteArray())
            Files.createSymbolicLink(encoder.toPath(), outsideEncoder.toPath())

            assertEquals(
                DealerAsrPackVerification.Rejected("pack-symlink"),
                DealerAsrRuntime(fixture.root) {}.verifyPack(fixture.manifest),
            )
        } finally {
            fixture.root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fileSourceRejectsSymlinkedDirectoryAndTraversal() {
        val fixture = installedPack()
        val outside = Files.createTempDirectory("dealer-asr-outside").toFile()
        try {
            val outsideEncoder = outside.resolve("encoder.onnx")
            Files.write(outsideEncoder.toPath(), "encoder".toByteArray())
            val encoder = fixture.root.resolve("test-pack/r1/encoder.onnx")
            encoder.delete()
            val nested = fixture.root.resolve("test-pack/r1/nested")
            Files.createSymbolicLink(nested.toPath(), outside.toPath())

            assertEquals(
                DealerAsrPackVerification.Rejected("encoder-path-invalid"),
                DealerAsrRuntime(fixture.root) {}.verifyPack(
                    fixture.manifest.copy(encoderPath = "../encoder.onnx"),
                ),
            )
            assertEquals(
                DealerAsrPackVerification.Rejected("pack-symlink"),
                DealerAsrRuntime(fixture.root) {}.verifyPack(
                    fixture.manifest.copy(encoderPath = "nested/encoder.onnx"),
                ),
            )
        } finally {
            fixture.root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fileSourceRejectsSymlinkedInstalledRoot() {
        val fixture = installedPack()
        val parent = Files.createTempDirectory("dealer-asr-root").toFile()
        val link = parent.resolve("packs")
        try {
            Files.createSymbolicLink(link.toPath(), fixture.root.toPath())
            assertEquals(
                DealerAsrPackVerification.Rejected("pack-symlink"),
                DealerAsrRuntime(link) {}.verifyPack(fixture.manifest),
            )
        } finally {
            Files.deleteIfExists(link.toPath())
            fixture.root.deleteRecursively()
            parent.deleteRecursively()
        }
    }

    @Test
    fun verifiedPackCannotBeUsedByAnotherRuntime() {
        val fixture = installedPack()
        try {
            val runtime = DealerAsrRuntime(fixture.root) {}
            val pack = verifiedPack(runtime, fixture.manifest)
            assertEquals(
                DealerAsrStartup.Unavailable("model-pack-owner-mismatch"),
                DealerAsrRuntime(fixture.root) {}.startup(pack),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun verifiedPack(
        runtime: DealerAsrRuntime,
        manifest: DealerAsrPackManifest,
    ): VerifiedAsrPack {
        return (runtime.verifyPack(manifest) as DealerAsrPackVerification.Verified).pack
    }

    private fun installedPack(): InstalledPack {
        val root = Files.createTempDirectory("dealer-asr").toFile()
        val packRoot = root.resolve("test-pack/r1").apply { mkdirs() }
        val bytes = mapOf(
            "encoder.onnx" to "encoder".toByteArray(),
            "decoder.onnx" to "decoder".toByteArray(),
            "joiner.onnx" to "joiner".toByteArray(),
            "tokens.txt" to "tokens".toByteArray(),
        )
        bytes.forEach { (name, content) -> Files.write(packRoot.resolve(name).toPath(), content) }
        return InstalledPack(
            root = root,
            manifest = DealerAsrPackManifest(
                id = "test-pack",
                revision = "r1",
                adapter = DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING,
                encoderPath = "encoder.onnx",
                encoderSha256 = sha256(bytes.getValue("encoder.onnx")),
                decoderPath = "decoder.onnx",
                decoderSha256 = sha256(bytes.getValue("decoder.onnx")),
                joinerPath = "joiner.onnx",
                joinerSha256 = sha256(bytes.getValue("joiner.onnx")),
                tokensPath = "tokens.txt",
                tokensSha256 = sha256(bytes.getValue("tokens.txt")),
            ),
        )
    }

    private fun manifest(
        adapter: DealerAsrAdapter = DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING,
        encoderPath: String = "encoder.onnx",
    ) = DealerAsrPackManifest(
        id = "test-pack",
        revision = "r1",
        adapter = adapter,
        encoderPath = encoderPath,
        encoderSha256 = "0".repeat(64),
        decoderPath = "decoder.onnx",
        decoderSha256 = "0".repeat(64),
        joinerPath = "joiner.onnx",
        joinerSha256 = "0".repeat(64),
        tokensPath = "tokens.txt",
        tokensSha256 = "0".repeat(64),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class InstalledPack(
        val root: File,
        val manifest: DealerAsrPackManifest,
    )
}
