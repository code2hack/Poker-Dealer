package com.code2hack.dealer.asr

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrCatalogTest {
    @Test
    fun embeddedCatalogContainsRequiredBaselinesAndImmutableSources() = runBlocking {
        val root = Files.createTempDirectory("dealer-asr-catalog").toFile()
        try {
            val loaded = DealerAsrCatalogStore(
                catalogFile = root.resolve("catalog.json"),
                embeddedCatalog = ::embeddedCatalog,
                fetchCatalog = { error("not used") },
            ).load()

            assertEquals(null, loaded.error)
            assertEquals(
                setOf(
                    "parakeet-unified-en-0.6b-int8-streaming-560ms",
                    "moonshine-v2-tiny-en-quantized",
                ),
                loaded.catalog.entries.map { it.id }.toSet(),
            )
            loaded.catalog.entries.forEach { entry ->
                assertEquals(entry.revision, entry.sourceRevision)
                assertTrue(entry.artifacts.all { it.canonicalUrl.startsWith("https://") })
                assertTrue(entry.installedBytes > 0)
                assertTrue(entry.profileSchema["fields"] != null)
            }
            assertEquals(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                loaded.catalog.entries.single { it.adapter == DealerAsrAdapter.MOONSHINE_V2_OFFLINE }
                    .artifacts.single().canonicalUrl,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filtersNameFamilyLanguageAndModeAndSortsByModelName() {
        val catalog = DealerAsrCatalog.parse(embeddedCatalog())

        assertEquals(
            listOf("Moonshine v2 Tiny · quantized"),
            catalog.filtered(search = "moonshine").map { it.displayName },
        )
        assertEquals(
            listOf("Parakeet Unified INT8 · 560 ms"),
            catalog.filtered(language = "EN", mode = DealerAsrMode.STREAMING)
                .map { it.displayName },
        )
        assertEquals(
            listOf(
                "Moonshine v2 Tiny · quantized",
                "Parakeet Unified INT8 · 560 ms",
            ),
            catalog.filtered().map { it.displayName },
        )
    }

    @Test
    fun incompatibleAdaptersAreExcludedWithoutAnUnavailableRow() {
        val catalog = DealerAsrCatalog.parse(
            embeddedCatalog(),
            supportedAdapters = setOf(DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING),
        )

        assertEquals(
            listOf(DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING),
            catalog.entries.map { it.adapter },
        )
    }

    @Test
    fun refreshFailureRetainsCatalogAndInstalledState() = runBlocking {
        val root = Files.createTempDirectory("dealer-asr-catalog").toFile()
        val catalogFile = root.resolve("catalog.json")
        val installedState = root.resolve("installed-pack.marker").apply {
            writeText("keep")
        }
        try {
            var remote = "not json"
            val store = DealerAsrCatalogStore(
                catalogFile = catalogFile,
                embeddedCatalog = ::embeddedCatalog,
                fetchCatalog = { remote },
            )
            val before = store.load().catalog
            val result = store.refresh()

            assertFalse(result.updated)
            assertNotNull(result.error)
            assertEquals(before.entries.map { it.id }, result.catalog.entries.map { it.id })
            assertEquals("keep", installedState.readText())
            assertEquals(before.entries.map { it.id }, store.load().catalog.entries.map { it.id })

            remote = embeddedCatalog().replace(
                "Moonshine v2 Tiny · quantized",
                "Moonshine v2 Tiny · refreshed",
            )
            assertTrue(store.refresh().updated)
            assertEquals(
                "Moonshine v2 Tiny · refreshed",
                store.load().catalog.entries.single { it.adapter == DealerAsrAdapter.MOONSHINE_V2_OFFLINE }
                    .displayName,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidPersistedCatalogFallsBackWithoutDeletingIt() = runBlocking {
        val root = Files.createTempDirectory("dealer-asr-catalog").toFile()
        val catalogFile = root.resolve("catalog.json").apply { writeText("{\"schemaVersion\":99}") }
        try {
            val loaded = DealerAsrCatalogStore(
                catalogFile = catalogFile,
                embeddedCatalog = ::embeddedCatalog,
                fetchCatalog = { error("not used") },
            ).load()

            assertNotNull(loaded.error)
            assertEquals(2, loaded.catalog.entries.size)
            assertEquals("{\"schemaVersion\":99}", catalogFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedSupportedEntryRejectsTheCompleteCatalog() {
        val malformed = embeddedCatalog().replace("\"sha256\": \"${"a".repeat(64)}\"", "\"sha256\": \"bad\"")
        val failure = runCatching { DealerAsrCatalog.parse(malformed) }.exceptionOrNull()

        assertNotNull(failure)
    }

    private fun embeddedCatalog(): String = """
        {
          "schemaVersion": 1,
          "runtime": {
            "backend": "cpu",
            "engine": "onnxruntime",
            "adapters": ["PARAKEET_UNIFIED_STREAMING", "MOONSHINE_V2_OFFLINE"]
          },
          "entries": [
            ${entry(
                id = "parakeet-unified-en-0.6b-int8-streaming-560ms",
                displayName = "Parakeet Unified INT8 · 560 ms",
                family = "Parakeet Unified",
                adapter = "PARAKEET_UNIFIED_STREAMING",
                mode = "STREAMING",
            )},
            ${entry(
                id = "moonshine-v2-tiny-en-quantized",
                displayName = "Moonshine v2 Tiny · quantized",
                family = "Moonshine v2",
                adapter = "MOONSHINE_V2_OFFLINE",
                mode = "OFFLINE",
            )}
          ]
        }
    """.trimIndent()

    private fun entry(
        id: String,
        displayName: String,
        family: String,
        adapter: String,
        mode: String,
    ): String {
        val revision = "a".repeat(40)
        val digest = "a".repeat(64)
        val url = if (adapter == "MOONSHINE_V2_OFFLINE") {
            ", " + "\"canonicalUrl\": \"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx\""
        } else {
            ""
        }
        return """
            {
              "id": "$id",
              "revision": "$revision",
              "displayName": "$displayName",
              "family": "$family",
              "adapter": "$adapter",
              "source": {"repository": "test/repo", "revision": "$revision"},
              "artifacts": [{"path": "model.onnx", "bytes": 1, "sha256": "$digest"$url}],
              "downloadBytes": 1,
              "temporaryBytes": 1,
              "installedBytes": 1,
              "languages": ["en"],
              "licenses": ["Test"],
              "backend": "cpu",
              "mode": "$mode",
              "defaultProfile": {
                "packId": "$id",
                "revision": "$revision",
                "schemaVersion": 1,
                "settings": {"warmRetentionSeconds": 300}
              },
              "profileSchema": {
                "packId": "$id",
                "revision": "$revision",
                "schemaVersion": 1,
                "fields": [{"name": "warmRetentionSeconds", "type": "integer", "default": 300}]
              }
            }
        """.trimIndent()
    }
}
