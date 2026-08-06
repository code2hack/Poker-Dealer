package com.code2hack.pokerdealer.protocol

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

const val POKER_FONT_SCALE_CAPABILITY = "font-scale.v1"
const val POKER_FONT_SCALE_TYPE = "font.scale"
const val POKER_FONT_SCALE_ACK_TYPE = "font.scale.ack"
const val POKER_TRANSIENT_NOTICE_TYPE = "notice.transient"
const val POKER_DIAGNOSTICS_CAPABILITY = "diagnostics.v1"
const val POKER_DIAGNOSTICS_TYPE = "diagnostics"

const val POKER_FONT_SCALE_MIN_PERCENT = 75
const val POKER_FONT_SCALE_MAX_PERCENT = 200
const val POKER_FONT_SCALE_STEP_PERCENT = 5
const val POKER_FONT_SCALE_DEFAULT_PERCENT = 100

@Serializable
data class PokerFontScaleState(
    val revision: Long = 0,
    @SerialName("percent") val percent: Int = POKER_FONT_SCALE_DEFAULT_PERCENT,
) {
    init {
        require(revision >= 0) { "Font revision must not be negative" }
        require(percent in POKER_FONT_SCALE_MIN_PERCENT..POKER_FONT_SCALE_MAX_PERCENT) {
            "Font scale must be between 75% and 200%"
        }
        require((percent - POKER_FONT_SCALE_MIN_PERCENT) % POKER_FONT_SCALE_STEP_PERCENT == 0) {
            "Font scale must use five-percent steps"
        }
    }

    val factor: Float
        get() = percent / 100f
}

@Serializable
enum class PokerFontScaleInstallResult {
    INSTALLED,
    DUPLICATE,
    STALE,
    CONFLICT,
    REJECTED,
}

@Serializable
data class PokerFontScaleAcknowledgement(
    val state: PokerFontScaleState,
    val result: PokerFontScaleInstallResult,
)

/** Applies the Dealer-owned revision fence without allowing equal-revision conflicts. */
class PokerFontScaleController(
    initial: PokerFontScaleState = PokerFontScaleState(),
) {
    @Volatile
    private var current = initial

    val state: PokerFontScaleState
        @Synchronized get() = current

    @Synchronized
    fun update(percent: Int): PokerFontScaleState {
        PokerFontScaleState(percent = percent)
        if (percent == current.percent) return current
        return PokerFontScaleState(current.revision + 1, percent).also { current = it }
    }

    @Synchronized
    fun install(candidate: PokerFontScaleState): PokerFontScaleInstallResult {
        val result = when {
            candidate.revision < current.revision -> PokerFontScaleInstallResult.STALE
            candidate.revision == current.revision && candidate == current ->
                PokerFontScaleInstallResult.DUPLICATE
            candidate.revision == current.revision -> PokerFontScaleInstallResult.CONFLICT
            else -> PokerFontScaleInstallResult.INSTALLED
        }
        if (result == PokerFontScaleInstallResult.INSTALLED) current = candidate
        return result
    }
}

object PokerFontScaleProtocol {
    fun updatePayload(state: PokerFontScaleState): JsonObject = PokerProtocolJson
        .encodeToJsonElement(PokerFontScaleState.serializer(), state)
        .jsonObject

    fun decodeUpdate(envelope: ProtocolEnvelope): PokerFontScaleState = decode(
        envelope,
        POKER_FONT_SCALE_TYPE,
        PokerFontScaleState.serializer(),
    )

    fun acknowledgementPayload(
        state: PokerFontScaleState,
        result: PokerFontScaleInstallResult,
    ): JsonObject = PokerProtocolJson
        .encodeToJsonElement(
            PokerFontScaleAcknowledgement.serializer(),
            PokerFontScaleAcknowledgement(state, result),
        )
        .jsonObject

    fun decodeAcknowledgement(envelope: ProtocolEnvelope): PokerFontScaleAcknowledgement = decode(
        envelope,
        POKER_FONT_SCALE_ACK_TYPE,
        PokerFontScaleAcknowledgement.serializer(),
    )

    private fun <T> decode(
        envelope: ProtocolEnvelope,
        type: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        require(envelope.protocol == POKER_PROTOCOL_NAME) { "Unexpected Poker protocol" }
        require(envelope.version == POKER_PROTOCOL_VERSION) {
            "Unexpected Poker protocol version: ${envelope.version}"
        }
        require(envelope.type == type) { "Expected $type, got ${envelope.type}" }
        return PokerProtocolJson.decodeFromJsonElement(serializer, envelope.payload)
    }
}

@Serializable
data class PokerTransientNotice(
    val text: String,
    @SerialName("duration_ms") val durationMs: Long,
) {
    init {
        require(text.isNotBlank()) { "Transient notice text must not be blank" }
        require(durationMs == 500L || durationMs == 1_000L) {
            "Transient notices last 500ms or 1000ms"
        }
    }
}

object PokerTransientNoticeProtocol {
    fun payload(notice: PokerTransientNotice): JsonObject = PokerProtocolJson
        .encodeToJsonElement(PokerTransientNotice.serializer(), notice)
        .jsonObject

    fun decode(envelope: ProtocolEnvelope): PokerTransientNotice {
        require(envelope.protocol == POKER_PROTOCOL_NAME) { "Unexpected Poker protocol" }
        require(envelope.version == POKER_PROTOCOL_VERSION) {
            "Unexpected Poker protocol version: ${envelope.version}"
        }
        require(envelope.type == POKER_TRANSIENT_NOTICE_TYPE) {
            "Expected $POKER_TRANSIENT_NOTICE_TYPE, got ${envelope.type}"
        }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerTransientNotice.serializer(),
            envelope.payload,
        )
    }
}

/** A single replacement slot. Expiring an older notice cannot clear a newer one. */
class PokerTransientNoticeSlot {
    data class Entry(val token: Long, val notice: PokerTransientNotice)

    private var nextToken = 0L
    private var current: Entry? = null

    val value: Entry?
        @Synchronized get() = current

    @Synchronized
    fun show(notice: PokerTransientNotice): Entry = Entry(++nextToken, notice).also { current = it }

    @Synchronized
    fun expire(token: Long): Boolean = if (current?.token == token) {
        current = null
        true
    } else false
}

@Serializable
enum class PokerWakeCapability {
    AVAILABLE,
    KEYGUARD_BLOCKED,
    OVERLAY_PERMISSION_REQUIRED,
    UNKNOWN,
}

/** Content-free status sent by Poker so Dealer diagnostics never need card data. */
@Serializable
data class PokerClientDiagnostics(
    val unreadCount: Int = 0,
    val wakeCapability: PokerWakeCapability = PokerWakeCapability.UNKNOWN,
    val font: PokerFontScaleState = PokerFontScaleState(),
) {
    init {
        require(unreadCount >= 0) { "Unread count must not be negative" }
    }
}

object PokerDiagnosticsProtocol {
    fun payload(value: PokerClientDiagnostics): JsonObject = PokerProtocolJson
        .encodeToJsonElement(PokerClientDiagnostics.serializer(), value)
        .jsonObject

    fun decode(envelope: ProtocolEnvelope): PokerClientDiagnostics {
        require(envelope.protocol == POKER_PROTOCOL_NAME) { "Unexpected Poker protocol" }
        require(envelope.version == POKER_PROTOCOL_VERSION) {
            "Unexpected Poker protocol version: ${envelope.version}"
        }
        require(envelope.type == POKER_DIAGNOSTICS_TYPE) {
            "Expected $POKER_DIAGNOSTICS_TYPE, got ${envelope.type}"
        }
        return PokerProtocolJson.decodeFromJsonElement(
            PokerClientDiagnostics.serializer(),
            envelope.payload,
        )
    }
}

@Serializable
private data class PokerFontScaleRecord(val state: PokerFontScaleState)

/** Atomic, content-free Poker-owned persistence for the last acknowledged font revision. */
class FilePokerFontScaleStore(
    private val file: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun load(): PokerFontScaleState {
        if (!file.exists()) return PokerFontScaleState()
        return try {
            json.decodeFromString(PokerFontScaleRecord.serializer(), file.readText()).state
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            PokerFontScaleState()
        }
    }

    fun save(state: PokerFontScaleState) {
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create font-scale storage"
        }
        val temporary = File.createTempFile(
            "${file.name}.".padEnd(3, '_'),
            ".tmp",
            parent ?: file.absoluteFile.parentFile,
        )
        try {
            temporary.writeText(json.encodeToString(PokerFontScaleRecord.serializer(), PokerFontScaleRecord(state)))
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
