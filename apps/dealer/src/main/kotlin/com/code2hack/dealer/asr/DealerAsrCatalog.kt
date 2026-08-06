package com.code2hack.dealer.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

internal enum class DealerAsrMode {
    STREAMING,
    OFFLINE,
}

internal data class DealerAsrCatalogArtifact(
    val path: String,
    val bytes: Long,
    val sha256: String,
    private val repository: String,
    private val revision: String,
) {
    val canonicalUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$path"
}

internal data class DealerAsrCatalogEntry(
    val id: String,
    val revision: String,
    val displayName: String,
    val family: String,
    val adapter: DealerAsrAdapter,
    val sourceRepository: String,
    val sourceRevision: String,
    val artifacts: List<DealerAsrCatalogArtifact>,
    val downloadBytes: Long,
    val temporaryBytes: Long,
    val installedBytes: Long,
    val languages: List<String>,
    val licenses: List<String>,
    val backend: String,
    val mode: DealerAsrMode,
    val defaultProfile: JsonObject,
    val profileSchema: JsonObject,
) {
    val searchableText: String
        get() = listOf(displayName, family, id, languages.joinToString(" "))
            .joinToString(" ")
            .lowercase(Locale.ROOT)
}

internal data class DealerAsrCatalog(
    val schemaVersion: Int,
    val runtimeBackend: String,
    val entries: List<DealerAsrCatalogEntry>,
) {
    fun filtered(
        search: String = "",
        language: String? = null,
        mode: DealerAsrMode? = null,
    ): List<DealerAsrCatalogEntry> {
        val query = search.trim().lowercase(Locale.ROOT)
        return entries
            .asSequence()
            .filter { query.isEmpty() || query in it.searchableText }
            .filter { language == null || it.languages.any { value -> value.equals(language, true) } }
            .filter { mode == null || it.mode == mode }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
            .toList()
    }

    companion object {
        val DEFAULT_SUPPORTED_ADAPTERS: Set<DealerAsrAdapter> = DealerAsrAdapter.entries.toSet()

        internal fun empty() = DealerAsrCatalog(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            runtimeBackend = CPU_ONNX_BACKEND,
            entries = emptyList(),
        )

        internal fun parse(
            raw: String,
            supportedAdapters: Set<DealerAsrAdapter> = DEFAULT_SUPPORTED_ADAPTERS,
        ): DealerAsrCatalog {
            val document = runCatching {
                catalogJson.decodeFromString(CatalogDocument.serializer(), raw)
            }.getOrElse { throw CatalogRejected("catalog-schema-invalid") }
            require(document.schemaVersion == CATALOG_SCHEMA_VERSION) {
                "catalog-schema-unsupported"
            }
            require(document.runtime.backend == CPU_ONNX_BACKEND &&
                document.runtime.engine == ONNX_RUNTIME_ENGINE &&
                document.runtime.adapters.isNotEmpty()
            ) { "catalog-runtime-invalid" }

            val declaredAdapters = document.runtime.adapters.mapNotNull { value ->
                runCatching { DealerAsrAdapter.valueOf(value) }.getOrNull()
            }.toSet()
            val compatibleAdapters = declaredAdapters intersect supportedAdapters
            val entries = document.entries.mapNotNull { candidate ->
                val adapter = runCatching { DealerAsrAdapter.valueOf(candidate.adapter) }.getOrNull()
                    ?: return@mapNotNull null
                if (adapter !in compatibleAdapters || candidate.backend != CPU_ONNX_BACKEND) {
                    return@mapNotNull null
                }
                toEntry(candidate, adapter)
            }
            require(entries.map { it.id to it.revision }.toSet().size == entries.size) {
                "catalog-entry-duplicate"
            }
            requiredEntries(supportedAdapters).forEach { (adapter, id) ->
                require(entries.any { it.adapter == adapter && it.id == id }) {
                    "catalog-required-entry-missing"
                }
            }
            return DealerAsrCatalog(
                schemaVersion = document.schemaVersion,
                runtimeBackend = document.runtime.backend,
                entries = entries,
            )
        }

        private fun toEntry(
            candidate: CatalogEntry,
            adapter: DealerAsrAdapter,
        ): DealerAsrCatalogEntry {
            require(PACK_ID.matches(candidate.id) && REVISION.matches(candidate.revision)) {
                "catalog-entry-identity-invalid"
            }
            require(candidate.displayName.isNotBlank() && candidate.family.isNotBlank()) {
                "catalog-entry-label-invalid"
            }
            require(candidate.languages.isNotEmpty() && candidate.languages.all(String::isNotBlank)) {
                "catalog-entry-language-invalid"
            }
            require(candidate.licenses.isNotEmpty() && candidate.licenses.all(String::isNotBlank)) {
                "catalog-entry-license-invalid"
            }
            require(candidate.mode in setOf("STREAMING", "OFFLINE")) {
                "catalog-entry-mode-invalid"
            }
            val source = candidate.source
            require(HF_REPOSITORY.matches(source.repository) && source.revision == candidate.revision) {
                "catalog-source-invalid"
            }
            require(candidate.artifacts.isNotEmpty()) { "catalog-artifacts-empty" }
            val artifacts = candidate.artifacts.map { artifact ->
                require(isArtifactPath(artifact.path)) { "catalog-artifact-path-invalid" }
                require(artifact.bytes > 0) { "catalog-artifact-size-invalid" }
                require(SHA256.matches(artifact.sha256)) { "catalog-artifact-digest-invalid" }
                DealerAsrCatalogArtifact(
                    path = artifact.path,
                    bytes = artifact.bytes,
                    sha256 = artifact.sha256,
                    repository = source.repository,
                    revision = source.revision,
                )
            }
            val artifactBytes = artifacts.fold(0L) { total, artifact ->
                Math.addExact(total, artifact.bytes)
            }
            require(candidate.downloadBytes >= artifactBytes &&
                candidate.temporaryBytes >= candidate.installedBytes &&
                candidate.installedBytes >= artifactBytes
            ) { "catalog-space-invalid" }
            validateProfile(candidate)
            return DealerAsrCatalogEntry(
                id = candidate.id,
                revision = candidate.revision,
                displayName = candidate.displayName,
                family = candidate.family,
                adapter = adapter,
                sourceRepository = source.repository,
                sourceRevision = source.revision,
                artifacts = artifacts,
                downloadBytes = candidate.downloadBytes,
                temporaryBytes = candidate.temporaryBytes,
                installedBytes = candidate.installedBytes,
                languages = candidate.languages,
                licenses = candidate.licenses,
                backend = candidate.backend,
                mode = DealerAsrMode.valueOf(candidate.mode),
                defaultProfile = candidate.defaultProfile,
                profileSchema = candidate.profileSchema,
            )
        }

        private fun validateProfile(candidate: CatalogEntry) {
            val profile = candidate.defaultProfile
            require(profile.text("packId") == candidate.id &&
                profile.text("revision") == candidate.revision &&
                profile.int("schemaVersion") != null
            ) { "catalog-default-profile-invalid" }
            val schema = candidate.profileSchema
            require(schema.text("packId") == candidate.id &&
                schema.text("revision") == candidate.revision &&
                schema.int("schemaVersion") != null
            ) { "catalog-profile-schema-invalid" }
            val fields = schema["fields"] as? JsonArray
                ?: throw CatalogRejected("catalog-profile-schema-invalid")
            require(fields.isNotEmpty()) { "catalog-profile-schema-invalid" }
            val names = fields.map { field ->
                val fieldObject = field as? JsonObject
                    ?: throw CatalogRejected("catalog-profile-field-invalid")
                val name = fieldObject.text("name")
                    ?: throw CatalogRejected("catalog-profile-field-invalid")
                require(PROFILE_FIELD.matches(name) && fieldObject.text("type") in PROFILE_TYPES) {
                    "catalog-profile-field-invalid"
                }
                require(fieldObject["default"] != null) { "catalog-profile-field-invalid" }
                name
            }
            require(names.toSet().size == names.size) { "catalog-profile-field-duplicate" }
            val settings = profile["settings"] as? JsonObject
                ?: throw CatalogRejected("catalog-default-profile-invalid")
            require(settings.keys == names.toSet()) { "catalog-default-profile-incomplete" }
        }

        private fun requiredEntries(
            supportedAdapters: Set<DealerAsrAdapter>,
        ): Map<DealerAsrAdapter, String> = mapOf(
            DealerAsrAdapter.PARAKEET_UNIFIED_STREAMING to
                "parakeet-unified-en-0.6b-int8-streaming-560ms",
            DealerAsrAdapter.MOONSHINE_V2_OFFLINE to
                "moonshine-v2-tiny-en-quantized",
        ).filterKeys(supportedAdapters::contains)

        private fun isArtifactPath(path: String): Boolean {
            if (path.isBlank() || path.length > 256 || path.startsWith('/') ||
                '\\' in path || ':' in path || '?' in path || '#' in path || "//" in path
            ) {
                return false
            }
            return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        }

        private fun JsonObject.text(name: String): String? =
            (this[name] as? JsonPrimitive)?.content

        private fun JsonObject.int(name: String): Int? =
            (this[name] as? JsonPrimitive)?.intOrNull

        private val catalogJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        private val PACK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val REVISION = Regex("[0-9a-f]{40}")
        private val HF_REPOSITORY = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
        private val PROFILE_FIELD = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private val PROFILE_TYPES = setOf("boolean", "integer", "number", "string")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class DealerAsrCatalogLoad(
    val catalog: DealerAsrCatalog,
    val error: String? = null,
)

internal data class DealerAsrCatalogRefresh(
    val catalog: DealerAsrCatalog,
    val updated: Boolean,
    val error: String? = null,
)

internal data class DealerAsrCatalogUiState(
    val catalog: DealerAsrCatalog = DealerAsrCatalog.empty(),
    val refreshing: Boolean = false,
    val error: String? = null,
)

internal class DealerAsrCatalogStore(
    private val catalogFile: File,
    private val embeddedCatalog: () -> String,
    private val fetchCatalog: suspend () -> String,
    private val supportedAdapters: Set<DealerAsrAdapter> = DealerAsrCatalog.DEFAULT_SUPPORTED_ADAPTERS,
) {
    constructor(context: Context) : this(
        catalogFile = context.noBackupFilesDir.resolve(CATALOG_FILE),
        embeddedCatalog = {
            context.assets.open(EMBEDDED_ASSET).bufferedReader().use { it.readText() }
        },
        fetchCatalog = { fetchRemoteCatalog(DEFAULT_REMOTE_URL) },
    )

    suspend fun load(): DealerAsrCatalogLoad = withContext(Dispatchers.IO) {
        val persisted = catalogFile.isFile
        val raw = runCatching {
            if (persisted) catalogFile.readText() else embeddedCatalog()
        }.getOrElse {
            return@withContext DealerAsrCatalogLoad(
                catalog = DealerAsrCatalog.empty(),
                error = "catalog-read-failed",
            )
        }
        val parsed = runCatching { DealerAsrCatalog.parse(raw, supportedAdapters) }
        if (parsed.isSuccess) {
            val catalog = parsed.getOrThrow()
            if (!persisted) {
                val persistFailure = runCatching { writeAtomically(raw) }.exceptionOrNull()
                return@withContext DealerAsrCatalogLoad(
                    catalog = catalog,
                    error = persistFailure?.let { "catalog-persist-failed" },
                )
            }
            return@withContext DealerAsrCatalogLoad(catalog)
        }
        if (!persisted) {
            return@withContext DealerAsrCatalogLoad(
                catalog = DealerAsrCatalog.empty(),
                error = catalogError(parsed.exceptionOrNull()!!),
            )
        }
        val fallback = runCatching {
            DealerAsrCatalog.parse(embeddedCatalog(), supportedAdapters)
        }
        return@withContext fallback.fold(
            onSuccess = { catalog ->
                DealerAsrCatalogLoad(catalog, catalogError(parsed.exceptionOrNull()!!))
            },
            onFailure = { failure ->
                DealerAsrCatalogLoad(DealerAsrCatalog.empty(), catalogError(failure))
            },
        )
    }

    suspend fun refresh(): DealerAsrCatalogRefresh = withContext(Dispatchers.IO) {
        val prior = load()
        runCatching {
            val raw = fetchCatalog()
            val catalog = DealerAsrCatalog.parse(raw, supportedAdapters)
            writeAtomically(raw)
            DealerAsrCatalogRefresh(catalog = catalog, updated = true)
        }.getOrElse {
            DealerAsrCatalogRefresh(
                catalog = prior.catalog,
                updated = false,
                error = catalogError(it),
            )
        }
    }

    private fun writeAtomically(raw: String) {
        val parent = catalogFile.parentFile ?: error("catalog parent unavailable")
        require(parent.isDirectory || parent.mkdirs()) { "catalog storage unavailable" }
        val temporary = File.createTempFile("${catalogFile.name}.", ".tmp", parent)
        try {
            temporary.writeText(raw)
            try {
                Files.move(
                    temporary.toPath(),
                    catalogFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    catalogFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun catalogError(failure: Throwable): String = when (failure) {
        is CatalogRejected -> failure.reason
        else -> "catalog-update-failed"
    }
}

private fun fetchRemoteCatalog(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }
    return try {
        require(connection.responseCode in 200..299) { "catalog-http-${connection.responseCode}" }
        connection.inputStream.use(::readCatalog)
    } finally {
        connection.disconnect()
    }
}

private fun readCatalog(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= MAX_CATALOG_BYTES) { "catalog-too-large" }
    }
    return output.toString(Charsets.UTF_8.name())
}

private class CatalogRejected(val reason: String) : IllegalArgumentException(reason)

@Serializable
private data class CatalogDocument(
    val schemaVersion: Int,
    val runtime: CatalogRuntime,
    val entries: List<CatalogEntry>,
)

@Serializable
private data class CatalogRuntime(
    val backend: String,
    val engine: String,
    val adapters: List<String>,
)

@Serializable
private data class CatalogEntry(
    val id: String,
    val revision: String,
    val displayName: String,
    val family: String,
    val adapter: String,
    val source: CatalogSource,
    val artifacts: List<CatalogArtifact>,
    val downloadBytes: Long,
    val temporaryBytes: Long,
    val installedBytes: Long,
    val languages: List<String>,
    val licenses: List<String>,
    val backend: String,
    val mode: String,
    val defaultProfile: JsonObject,
    val profileSchema: JsonObject,
)

@Serializable
private data class CatalogSource(
    val repository: String,
    val revision: String,
)

@Serializable
private data class CatalogArtifact(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

private const val CATALOG_SCHEMA_VERSION = 1
private const val CPU_ONNX_BACKEND = "cpu"
private const val ONNX_RUNTIME_ENGINE = "onnxruntime"
private const val CATALOG_FILE = "asr/catalog-v1.json"
private const val EMBEDDED_ASSET = "asr/catalog-v1.json"
private const val DEFAULT_REMOTE_URL =
    "https://raw.githubusercontent.com/code2hack/Poker-Dealer/main/" +
        "apps/dealer/src/main/assets/asr/catalog-v1.json"
private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
