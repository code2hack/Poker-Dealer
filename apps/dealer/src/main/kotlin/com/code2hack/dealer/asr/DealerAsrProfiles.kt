package com.code2hack.dealer.asr

import java.io.Closeable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal enum class DealerAsrProfileValueType {
    BOOLEAN,
    INTEGER,
    NUMBER,
    STRING,
}

internal data class DealerAsrProfileError(
    val path: String,
    val reason: String,
)

internal sealed interface DealerAsrProfileValidation {
    data class Valid(val profile: DealerAsrProfile) : DealerAsrProfileValidation

    data class Invalid(val errors: List<DealerAsrProfileError>) : DealerAsrProfileValidation
}

internal data class DealerAsrProfileField(
    val name: String,
    val type: DealerAsrProfileValueType,
    val defaultValue: JsonPrimitive,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val allowedValues: List<JsonElement> = emptyList(),
)

internal data class DealerAsrProfileSchema(
    val packId: String,
    val revision: String,
    val schemaVersion: Int,
    val fields: List<DealerAsrProfileField>,
) {
    val key: DealerAsrProfileSchemaKey
        get() = DealerAsrProfileSchemaKey(packId, revision, schemaVersion)

    val defaultSettings: JsonObject
        get() = JsonObject(fields.associate { it.name to it.defaultValue })

    fun defaultProfile(): DealerAsrProfile = DealerAsrProfile(
        packId = packId,
        revision = revision,
        schemaVersion = schemaVersion,
        settings = defaultSettings,
    )

    fun validate(raw: String): DealerAsrProfileValidation = validate(
        runCatching { strictProfileJson.parseToJsonElement(raw) }.getOrElse {
            return DealerAsrProfileValidation.Invalid(
                listOf(DealerAsrProfileError("$", "malformed-json")),
            )
        },
    )

    fun validate(raw: JsonElement): DealerAsrProfileValidation {
        val errors = mutableListOf<DealerAsrProfileError>()
        val objectValue = raw as? JsonObject
        if (objectValue == null) {
            return DealerAsrProfileValidation.Invalid(
                listOf(DealerAsrProfileError("$", "profile-object-required")),
            )
        }

        val requiredKeys = setOf("packId", "revision", "schemaVersion", "settings")
        (objectValue.keys - requiredKeys).forEach { key ->
            errors += DealerAsrProfileError(key, "unknown-field")
        }
        (requiredKeys - objectValue.keys).forEach { key ->
            errors += DealerAsrProfileError(key, "missing-field")
        }

        val packId = objectValue.stringValue("packId")
        if (packId != this.packId) errors += DealerAsrProfileError("packId", "inapplicable-pack")
        val revision = objectValue.stringValue("revision")
        if (revision != this.revision) errors += DealerAsrProfileError("revision", "inapplicable-revision")
        val schemaVersion = objectValue["schemaVersion"]?.asInt()
        if (schemaVersion != this.schemaVersion) {
            errors += DealerAsrProfileError("schemaVersion", "schema-version-mismatch")
        }

        val settings = objectValue["settings"] as? JsonObject
        if (settings == null) {
            errors += DealerAsrProfileError("settings", "object-required")
        } else {
            val fieldNames = fields.mapTo(mutableSetOf(), DealerAsrProfileField::name)
            (settings.keys - fieldNames).forEach { name ->
                errors += DealerAsrProfileError("settings.$name", "unknown-or-inapplicable-field")
            }
            (fieldNames - settings.keys).forEach { name ->
                errors += DealerAsrProfileError("settings.$name", "missing-field")
            }
            fields.forEach { field ->
                val value = settings[field.name] ?: return@forEach
                validateValue(field, value, "settings.${field.name}", errors)
            }
        }

        if (errors.isNotEmpty()) return DealerAsrProfileValidation.Invalid(errors)
        return DealerAsrProfileValidation.Valid(
            DealerAsrProfile(
                packId = packId!!,
                revision = revision!!,
                schemaVersion = schemaVersion!!,
                settings = settings!!,
            ),
        )
    }

    companion object {
        fun parse(
            schema: JsonObject,
            expectedPackId: String,
            expectedRevision: String,
        ): DealerAsrProfileSchema {
            require(schema.stringValue("packId") == expectedPackId) { "profile-schema-pack-mismatch" }
            require(schema.stringValue("revision") == expectedRevision) { "profile-schema-revision-mismatch" }
            val schemaVersion = requireNotNull(schema["schemaVersion"]?.asInt()) {
                "profile-schema-version-invalid"
            }
            require(schemaVersion > 0) { "profile-schema-version-invalid" }
            val fields = requireNotNull(schema["fields"] as? JsonArray) {
                "profile-schema-fields-invalid"
            }.map { element ->
                val field = element as? JsonObject ?: error("profile-schema-field-invalid")
                val name = field.stringValue("name")
                require(name != null && PROFILE_FIELD.matches(name)) { "profile-schema-field-invalid" }
                val type = when (field.stringValue("type")) {
                    "boolean" -> DealerAsrProfileValueType.BOOLEAN
                    "integer" -> DealerAsrProfileValueType.INTEGER
                    "number" -> DealerAsrProfileValueType.NUMBER
                    "string" -> DealerAsrProfileValueType.STRING
                    else -> error("profile-schema-field-invalid")
                }
                val default = field["default"] as? JsonPrimitive
                    ?: error("profile-schema-field-invalid")
                val minimum = field.number("minimum")?.also {
                    require(type == DealerAsrProfileValueType.INTEGER || type == DealerAsrProfileValueType.NUMBER) {
                        "profile-schema-range-invalid"
                    }
                }
                val maximum = field.number("maximum")?.also {
                    require(type == DealerAsrProfileValueType.INTEGER || type == DealerAsrProfileValueType.NUMBER) {
                        "profile-schema-range-invalid"
                    }
                }
                require(minimum == null || maximum == null || minimum <= maximum) {
                    "profile-schema-range-invalid"
                }
                val allowedKeys = listOf("allowedValues", "values", "enum").filter(field::containsKey)
                require(allowedKeys.size <= 1) { "profile-schema-values-invalid" }
                val allowedValues = allowedKeys.firstOrNull()?.let { key ->
                    requireNotNull(field[key] as? JsonArray) { "profile-schema-values-invalid" }.toList()
                }.orEmpty()
                require(allowedValues.all { it is JsonPrimitive }) {
                    "profile-schema-values-invalid"
                }
                val parsed = DealerAsrProfileField(
                    name = name,
                    type = type,
                    defaultValue = default,
                    minimum = minimum,
                    maximum = maximum,
                    allowedValues = allowedValues,
                )
                val defaultErrors = mutableListOf<DealerAsrProfileError>()
                validateValue(parsed, default, "settings.$name", defaultErrors)
                require(defaultErrors.isEmpty()) { "profile-schema-default-invalid" }
                allowedValues.forEachIndexed { index, value ->
                    val valueErrors = mutableListOf<DealerAsrProfileError>()
                    validateValue(parsed, value, "values[$index]", valueErrors)
                    require(valueErrors.isEmpty()) { "profile-schema-values-invalid" }
                }
                parsed
            }
            require(fields.isNotEmpty() && fields.map(DealerAsrProfileField::name).toSet().size == fields.size) {
                "profile-schema-fields-invalid"
            }
            val warm = fields.singleOrNull { it.name == WARM_RETENTION_SECONDS }
            require(warm?.type == DealerAsrProfileValueType.INTEGER &&
                warm.defaultValue.longOrNull == DEFAULT_WARM_RETENTION_SECONDS &&
                (warm.minimum == null || warm.minimum <= 0.0) &&
                (warm.maximum == null || warm.maximum >= DEFAULT_WARM_RETENTION_SECONDS)
            ) { "profile-schema-warm-retention-invalid" }
            return DealerAsrProfileSchema(
                packId = expectedPackId,
                revision = expectedRevision,
                schemaVersion = schemaVersion,
                fields = fields,
            )
        }

        private const val WARM_RETENTION_SECONDS = "warmRetentionSeconds"
        private const val DEFAULT_WARM_RETENTION_SECONDS = 300L
        private val PROFILE_FIELD = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    }
}

internal data class DealerAsrProfile(
    val packId: String,
    val revision: String,
    val schemaVersion: Int,
    val settings: JsonObject,
) {
    val json: JsonObject
        get() = JsonObject(
            linkedMapOf(
                "packId" to JsonPrimitive(packId),
                "revision" to JsonPrimitive(revision),
                "schemaVersion" to JsonPrimitive(schemaVersion),
                "settings" to settings,
            ),
        )

    val warmRetentionSeconds: Int
        get() = settings["warmRetentionSeconds"]?.asInt()
            ?: error("validated profile lacks warmRetentionSeconds")

    fun matches(key: DealerAsrPackKey): Boolean =
        packId == key.packId && revision == key.revision
}

internal data class DealerAsrProfileSchemaKey(
    val packId: String,
    val revision: String,
    val schemaVersion: Int,
)

internal data class DealerAsrSessionProfile(
    val key: DealerAsrPackKey,
    val profile: DealerAsrProfile,
)

internal sealed interface DealerAsrProfileSaveResult {
    data class Saved(val profile: DealerAsrProfile) : DealerAsrProfileSaveResult

    data class Rejected(val errors: List<DealerAsrProfileError>) : DealerAsrProfileSaveResult
}

internal class DealerAsrProfileStore(
    private val stateFile: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Closeable {
    private data class WarmRecognizer(
        val key: DealerAsrPackKey,
        val profile: DealerAsrProfile,
        val recognizer: Closeable,
        var expiresAtMillis: Long,
    )

    private val records = linkedMapOf<DealerAsrProfileSchemaKey, String>()
    private val activeSessions = linkedMapOf<DealerAsrPackKey, Int>()
    private val warmRecognizers = linkedMapOf<DealerAsrPackKey, WarmRecognizer>()
    private var started = false
    private var defaultPack: DealerAsrPackKey? = null

    fun start() = synchronized(this) {
        if (started) return@synchronized
        if (stateFile.isFile) {
            runCatching {
                strictProfileJson.decodeFromString<PersistedProfileDocument>(stateFile.readText())
            }.getOrNull()?.profiles?.forEach { record ->
                records[DealerAsrProfileSchemaKey(record.packId, record.revision, record.schemaVersion)] =
                    record.profileJson
            }
        }
        started = true
    }

    fun ensureProfile(
        key: DealerAsrPackKey,
        schema: DealerAsrProfileSchema,
        defaultProfile: DealerAsrProfile = schema.defaultProfile(),
    ): DealerAsrProfile = synchronized(this) {
        start()
        require(schema.key == DealerAsrProfileSchemaKey(key.packId, key.revision, schema.schemaVersion)) {
            "profile-schema-pack-mismatch"
        }
        records[schema.key]?.let { stored ->
            when (val validation = schema.validate(stored)) {
                is DealerAsrProfileValidation.Valid -> return@synchronized validation.profile
                is DealerAsrProfileValidation.Invalid -> Unit
            }
        }
        require(schema.validate(defaultProfile.json) is DealerAsrProfileValidation.Valid) {
            "profile-default-invalid"
        }
        records[schema.key] = strictProfileJson.encodeToString(defaultProfile.json)
        persistLocked()
        defaultProfile
    }

    fun save(
        key: DealerAsrPackKey,
        schema: DealerAsrProfileSchema,
        raw: String,
    ): DealerAsrProfileSaveResult = synchronized(this) {
        start()
        if (schema.packId != key.packId || schema.revision != key.revision) {
            return@synchronized DealerAsrProfileSaveResult.Rejected(
                listOf(DealerAsrProfileError("profile", "profile-schema-pack-mismatch")),
            )
        }
        if ((activeSessions[key] ?: 0) > 0) {
            return@synchronized DealerAsrProfileSaveResult.Rejected(
                listOf(DealerAsrProfileError("profile", "active-session")),
            )
        }
        when (val validation = schema.validate(raw)) {
            is DealerAsrProfileValidation.Invalid -> DealerAsrProfileSaveResult.Rejected(validation.errors)
            is DealerAsrProfileValidation.Valid -> {
                require(validation.profile.matches(key)) { "profile-schema-pack-mismatch" }
                records[schema.key] = strictProfileJson.encodeToString(validation.profile.json)
                invalidateIdleWarmLocked(key)
                persistLocked()
                DealerAsrProfileSaveResult.Saved(validation.profile)
            }
        }
    }

    fun beginSession(
        key: DealerAsrPackKey,
        schema: DealerAsrProfileSchema,
    ): DealerAsrSessionProfile = synchronized(this) {
        val profile = ensureProfile(key, schema)
        activeSessions[key] = (activeSessions[key] ?: 0) + 1
        DealerAsrSessionProfile(key, profile)
    }

    fun endSession(session: DealerAsrSessionProfile) = synchronized(this) {
        val count = activeSessions[session.key] ?: return@synchronized
        if (count <= 1) activeSessions.remove(session.key) else activeSessions[session.key] = count - 1
        if ((activeSessions[session.key] ?: 0) == 0) {
            warmRecognizers[session.key]?.expiresAtMillis =
                nowMillis() + session.profile.warmRetentionSeconds * 1_000L
        }
        evictWarmLocked(nowMillis())
    }

    fun retainWarmRecognizer(
        key: DealerAsrPackKey,
        profile: DealerAsrProfile,
        recognizer: Closeable,
    ) = synchronized(this) {
        start()
        evictWarmLocked(nowMillis())
        warmRecognizers.remove(key)?.recognizer?.close()
        val keep = profile.warmRetentionSeconds > 0 &&
            defaultPack == key
        if (!keep) {
            recognizer.close()
            return@synchronized
        }
        val expiresAt = if ((activeSessions[key] ?: 0) > 0) {
            Long.MAX_VALUE
        } else {
            nowMillis() + profile.warmRetentionSeconds * 1_000L
        }
        warmRecognizers[key] = WarmRecognizer(key, profile, recognizer, expiresAt)
    }

    fun setDefault(key: DealerAsrPackKey?) = synchronized(this) {
        defaultPack = key
        warmRecognizers.values.toList().forEach { warm ->
            if (warm.key != key && (activeSessions[warm.key] ?: 0) == 0) {
                warmRecognizers.remove(warm.key)?.recognizer?.close()
            }
        }
    }

    fun evictWarmRecognizers() = synchronized(this) {
        evictWarmLocked(nowMillis())
    }

    fun activeSessionKeys(): Set<DealerAsrPackKey> = synchronized(this) {
        activeSessions.keys.toSet()
    }

    fun warmPackKeys(): Set<DealerAsrPackKey> = synchronized(this) {
        evictWarmLocked(nowMillis())
        warmRecognizers.keys.toSet()
    }

    override fun close() = synchronized(this) {
        warmRecognizers.values.forEach { it.recognizer.close() }
        warmRecognizers.clear()
        activeSessions.clear()
    }

    private fun invalidateIdleWarmLocked(key: DealerAsrPackKey) {
        if ((activeSessions[key] ?: 0) == 0) {
            warmRecognizers.remove(key)?.recognizer?.close()
        }
    }

    private fun evictWarmLocked(now: Long) {
        warmRecognizers.values.toList().forEach { warm ->
            val idle = (activeSessions[warm.key] ?: 0) == 0
            if (idle && (warm.key != defaultPack || warm.expiresAtMillis <= now)) {
                warmRecognizers.remove(warm.key)?.recognizer?.close()
            }
        }
    }

    private fun persistLocked() {
        val parent = stateFile.parentFile ?: error("profile-storage-unavailable")
        require(parent.isDirectory || parent.mkdirs()) { "profile-storage-unavailable" }
        val document = PersistedProfileDocument(
            profiles = records.map { (key, raw) ->
                PersistedProfile(
                    packId = key.packId,
                    revision = key.revision,
                    schemaVersion = key.schemaVersion,
                    profileJson = raw,
                )
            },
        )
        val temporary = File.createTempFile("${stateFile.name}.", ".tmp", parent)
        try {
            temporary.writeText(strictProfileJson.encodeToString(document))
            try {
                Files.move(
                    temporary.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    @Serializable
    private data class PersistedProfile(
        val packId: String,
        val revision: String,
        val schemaVersion: Int,
        val profileJson: String,
    )

    @Serializable
    private data class PersistedProfileDocument(
        val profiles: List<PersistedProfile> = emptyList(),
    )

}

internal fun DealerAsrCatalogEntry.profileSchemaModel(): DealerAsrProfileSchema =
    DealerAsrProfileSchema.parse(profileSchema, id, revision)

internal fun DealerAsrCatalogEntry.defaultProfileModel(): DealerAsrProfile =
    profileSchemaModel().let { schema ->
        val profile = when (val validation = schema.validate(defaultProfile)) {
            is DealerAsrProfileValidation.Valid -> validation.profile
            is DealerAsrProfileValidation.Invalid -> error("catalog-default-profile-invalid")
        }
        require(profile.settings == schema.defaultSettings) { "catalog-default-profile-invalid" }
        profile
    }

internal fun prettyDealerAsrProfile(raw: String): String = runCatching {
    profileJsonPretty.encodeToString(Json.parseToJsonElement(raw))
}.getOrElse { raw }

private fun validateValue(
    field: DealerAsrProfileField,
    value: JsonElement,
    path: String,
    errors: MutableList<DealerAsrProfileError>,
) {
    val primitive = value as? JsonPrimitive
    val typeMatches = when (field.type) {
        DealerAsrProfileValueType.BOOLEAN -> primitive?.booleanOrNull != null && !primitive.isString
        DealerAsrProfileValueType.INTEGER -> primitive?.longOrNull != null && !primitive.isString
        DealerAsrProfileValueType.NUMBER -> primitive?.doubleOrNull != null && !primitive.isString
        DealerAsrProfileValueType.STRING -> primitive?.isString == true
    }
    if (!typeMatches) {
        errors += DealerAsrProfileError(path, "type-mismatch")
        return
    }
    if (field.allowedValues.isNotEmpty() && value !in field.allowedValues) {
        errors += DealerAsrProfileError(path, "value-not-allowed")
    }
    val number = when (field.type) {
        DealerAsrProfileValueType.INTEGER,
        DealerAsrProfileValueType.NUMBER,
        -> primitive?.doubleOrNull
        else -> null
    }
    if (field.minimum != null && (number == null || number < field.minimum)) {
        errors += DealerAsrProfileError(path, "below-minimum")
    }
    if (field.maximum != null && (number == null || number > field.maximum)) {
        errors += DealerAsrProfileError(path, "above-maximum")
    }
}

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(name: String): Double? =
    this[name]?.let { requireNotNull(it.asDouble()) { "profile-schema-range-invalid" } }

private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)
    ?.takeIf { !it.isString }
    ?.intOrNull

private fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)
    ?.takeIf { !it.isString }
    ?.doubleOrNull

private val profileJsonPretty = Json {
    prettyPrint = true
    explicitNulls = false
}

private val strictProfileJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    explicitNulls = false
}
