package com.code2hack.dealer.asr

import java.io.Closeable
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerAsrProfilesTest {
    @Test
    fun defaultsCoverEverySchemaFieldAndProfileIsStrict() {
        val schema = schema()
        val profile = schema.defaultProfile()

        assertEquals(
            setOf("warmRetentionSeconds", "beamSize", "temperature", "useEndpoint", "language"),
            profile.settings.keys,
        )
        assertEquals(300, profile.warmRetentionSeconds)
        assertTrue(schema.validate(profile.json) is DealerAsrProfileValidation.Valid)
        assertTrue(
            schema.validate(
                profile.json.toMutableMapJson("artifactPaths" to JsonPrimitive("forbidden")),
            ) is DealerAsrProfileValidation.Invalid,
        )
    }

    @Test
    fun malformedUnknownInapplicableAndOutOfRangeEditsKeepPriorProfile() {
        val root = Files.createTempDirectory("dealer-asr-profiles").toFile()
        try {
            val schema = schema()
            val key = DealerAsrPackKey(schema.packId, schema.revision)
            val store = DealerAsrProfileStore(root.resolve("profiles.json"))
            val initial = store.ensureProfile(key, schema)
            val valid = initial.json.withSettings(
                "beamSize" to JsonPrimitive(8),
                "temperature" to JsonPrimitive(0.4),
                "useEndpoint" to JsonPrimitive(false),
                "language" to JsonPrimitive("en"),
                "warmRetentionSeconds" to JsonPrimitive(0),
            )
            assertTrue(store.save(key, schema, valid.toString()) is DealerAsrProfileSaveResult.Saved)

            val validSettings = valid["settings"] as JsonObject
            val invalid = listOf(
                "not-json",
                valid.toMutableMapJson("settings" to JsonObject(validSettings + ("unknown" to JsonPrimitive(1)))),
                valid.toMutableMapJson("settings" to JsonObject(validSettings - "beamSize")),
                valid.toMutableMapJson("settings" to JsonObject(validSettings + ("beamSize" to JsonPrimitive(99)))),
                valid.toMutableMapJson("settings" to JsonObject(validSettings + ("temperature" to JsonPrimitive("0.4")))),
                valid.toMutableMapJson("backend" to JsonPrimitive("cpu")),
            )
            invalid.forEach { raw ->
                val result = store.save(key, schema, raw.toString())
                assertTrue(result is DealerAsrProfileSaveResult.Rejected)
            }
            assertEquals(valid, store.ensureProfile(key, schema).json)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun activeSessionsBlockEditingAndSnapshotsIgnoreLaterEdits() {
        val root = Files.createTempDirectory("dealer-asr-profiles").toFile()
        try {
            val schema = schema()
            val key = DealerAsrPackKey(schema.packId, schema.revision)
            val store = DealerAsrProfileStore(root.resolve("profiles.json"))
            val first = store.beginSession(key, schema)
            val edited = first.profile.json.withSettings("beamSize" to JsonPrimitive(12))

            val blocked = store.save(key, schema, edited.toString()) as DealerAsrProfileSaveResult.Rejected
            assertEquals("active-session", blocked.errors.single().reason)
            assertEquals(300, first.profile.warmRetentionSeconds)

            store.endSession(first)
            assertTrue(store.save(key, schema, edited.toString()) is DealerAsrProfileSaveResult.Saved)
            val second = store.beginSession(key, schema)
            assertEquals(12, second.profile.settings["beamSize"]?.let { (it as JsonPrimitive).int })
            assertEquals(300, first.profile.warmRetentionSeconds)
            store.endSession(second)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restartRetainsProfileAndSchemaVersionStartsASeparateDefault() {
        val root = Files.createTempDirectory("dealer-asr-profiles").toFile()
        try {
            val firstSchema = schema(version = 1)
            val key = DealerAsrPackKey(firstSchema.packId, firstSchema.revision)
            val first = DealerAsrProfileStore(root.resolve("profiles.json"))
            first.ensureProfile(key, firstSchema)
            val edited = firstSchema.defaultProfile().json.withSettings("beamSize" to JsonPrimitive(9))
            assertTrue(first.save(key, firstSchema, edited.toString()) is DealerAsrProfileSaveResult.Saved)
            first.close()

            val restarted = DealerAsrProfileStore(root.resolve("profiles.json"))
            assertEquals(9, restarted.ensureProfile(key, firstSchema).settings["beamSize"]?.let { (it as JsonPrimitive).int })
            val nextSchema = schema(version = 2)
            val fresh = restarted.ensureProfile(key, nextSchema)
            assertEquals(4, fresh.settings["beamSize"]?.let { (it as JsonPrimitive).int })
            assertEquals(9, restarted.ensureProfile(key, firstSchema).settings["beamSize"]?.let { (it as JsonPrimitive).int })
            restarted.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun warmRetentionHonorsZeroDefaultOnlyAndExpiryAndProfileSaveInvalidation() {
        val root = Files.createTempDirectory("dealer-asr-profiles").toFile()
        try {
            var now = 1_000L
            val schema = schema()
            val secondSchema = schema(packId = "second-pack")
            val firstKey = DealerAsrPackKey(schema.packId, schema.revision)
            val secondKey = DealerAsrPackKey(secondSchema.packId, secondSchema.revision)
            val store = DealerAsrProfileStore(root.resolve("profiles.json")) { now }
            val first = store.ensureProfile(firstKey, schema)
            val second = store.ensureProfile(secondKey, secondSchema)
            store.setDefault(firstKey)

            val firstRecognizer = CloseableCounter()
            store.retainWarmRecognizer(firstKey, first, firstRecognizer)
            assertEquals(setOf(firstKey), store.warmPackKeys())

            store.setDefault(secondKey)
            assertEquals(1, firstRecognizer.closed)
            assertTrue(store.warmPackKeys().isEmpty())

            val secondRecognizer = CloseableCounter()
            store.retainWarmRecognizer(secondKey, second, secondRecognizer)
            now += 301_000L
            store.evictWarmRecognizers()
            assertEquals(1, secondRecognizer.closed)

            store.setDefault(firstKey)
            val zeroProfile = first.json.withSettings("warmRetentionSeconds" to JsonPrimitive(0))
            assertTrue(store.save(firstKey, schema, zeroProfile.toString()) is DealerAsrProfileSaveResult.Saved)
            val zeroRecognizer = CloseableCounter()
            store.retainWarmRecognizer(firstKey, store.ensureProfile(firstKey, schema), zeroRecognizer)
            assertEquals(1, zeroRecognizer.closed)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun schema(
        packId: String = "test-pack",
        version: Int = 1,
    ): DealerAsrProfileSchema = DealerAsrProfileSchema.parse(
        Json.parseToJsonElement(
            """
            {
              "packId": "$packId",
              "revision": "${"a".repeat(40)}",
              "schemaVersion": $version,
              "fields": [
                {"name":"warmRetentionSeconds","type":"integer","default":300,"minimum":0,"maximum":3600},
                {"name":"beamSize","type":"integer","default":4,"minimum":1,"maximum":16},
                {"name":"temperature","type":"number","default":0.2,"minimum":0,"maximum":1},
                {"name":"useEndpoint","type":"boolean","default":true},
                {"name":"language","type":"string","default":"en","allowedValues":["en","zh"]}
              ]
            }
            """.trimIndent(),
        ) as JsonObject,
        expectedPackId = packId,
        expectedRevision = "a".repeat(40),
    )

    private fun JsonObject.withSettings(vararg values: Pair<String, JsonPrimitive>): JsonObject =
        toMutableMapJson(
            "settings" to JsonObject((this["settings"] as JsonObject).toMutableMap().apply {
                values.forEach { (key, value) -> put(key, value) }
            }),
        )

    private fun JsonObject.toMutableMapJson(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(toMutableMap().apply { values.forEach { (key, value) -> put(key, value) } })

    private val JsonPrimitive.int: Int
        get() = intOrNull ?: error("expected integer")

    private class CloseableCounter : Closeable {
        var closed = 0

        override fun close() {
            closed++
        }
    }
}
