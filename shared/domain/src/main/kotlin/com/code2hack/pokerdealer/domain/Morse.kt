package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

const val DEFAULT_MORSE_QUIET_INTERVAL_MS = 700L
const val MIN_MORSE_QUIET_INTERVAL_MS = 300L
const val MAX_MORSE_QUIET_INTERVAL_MS = 2_000L
const val MORSE_QUIET_INTERVAL_STEP_MS = 50L

@Serializable
data class MorseModeTarget(
    val locator: CodexThreadLocator,
    val surface: ComposerSurface,
    val requestLocator: ServerRequestLocator? = null,
    val questionId: String? = null,
    val requestFingerprint: String? = null,
    val revision: Long,
    val cursorPosition: Int,
    val controlGeneration: Long,
    val connectionEpoch: Long,
    val bindingModeSession: String,
    val modeSession: String,
) {
    init {
        require(revision >= 0) { "Morse target revision must not be negative" }
        require(cursorPosition >= 0) { "Morse target cursor must not be negative" }
        require(controlGeneration >= 0) { "Morse target control generation must not be negative" }
        require(connectionEpoch >= 0) { "Morse target connection epoch must not be negative" }
        require(bindingModeSession.isNotBlank()) { "Morse binding mode session must not be blank" }
        require(modeSession.isNotBlank()) { "Morse mode session must not be blank" }
        when (surface) {
            ComposerSurface.THREAD_COMPOSER -> require(
                requestLocator == null && questionId == null && requestFingerprint == null,
            ) { "Composer Morse target cannot include a request" }
            ComposerSurface.REQUEST_PANEL -> {
                require(requestLocator != null) { "Request Morse target requires a request locator" }
                require(!questionId.isNullOrBlank()) { "Request Morse target requires a question" }
                require(!requestFingerprint.isNullOrBlank()) {
                    "Request Morse target requires a request fingerprint"
                }
                require(requestLocator.hostId == locator.hostId) {
                    "Request Morse target host must match its thread"
                }
            }
        }
    }
}

data class MorseDictionaryEntry(
    val word: String,
    val commonness: Int,
) {
    init {
        require(word.isNotBlank() && word.all(Char::isAsciiLatinLetter)) {
            "Morse dictionary words must be Latin words"
        }
        require(commonness > 0) { "Morse dictionary commonness must be positive" }
    }
}

data class MorseCompletionCandidate(
    val word: String,
    val suffix: String,
)

data class MorseCompletionHint(
    val target: MorseModeTarget,
    val prefix: String,
    val suffix: String,
) {
    init {
        require(prefix.length >= 2) { "Morse completion prefixes need two letters" }
        require(prefix.all(Char::isAsciiLatinLetter)) {
            "Morse completion prefixes must be Latin letters"
        }
        require(suffix.isNotEmpty()) { "Morse completion suffixes must not be empty" }
        require(suffix.all(Char::isAsciiLatinLetter)) {
            "Morse completion suffixes must be Latin letters"
        }
    }
}

object MorseCompletionEngine {
    fun isEligiblePrefix(prefix: String): Boolean =
        prefix.length >= 2 && prefix.all(Char::isAsciiLatinLetter)

    fun suggest(
        prefix: String,
        dictionary: Iterable<MorseDictionaryEntry>,
    ): MorseCompletionCandidate? {
        if (!isEligiblePrefix(prefix)) return null
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        return dictionary.asSequence()
            .filter {
                it.word.length > prefix.length &&
                    it.word.lowercase(Locale.ROOT).startsWith(normalizedPrefix)
            }
            .map { it to it.word.substring(prefix.length) }
            .minWithOrNull(
                compareBy<Pair<MorseDictionaryEntry, String>> { it.first.commonness }
                    .thenBy { it.second.length }
                    .thenBy { it.first.word.lowercase(Locale.ROOT) }
                    .thenBy { it.first.word },
            )
            ?.let { (entry, suffix) -> MorseCompletionCandidate(entry.word, suffix) }
    }
}

object MorseCompletionDictionary {
    fun parse(lines: Sequence<String>): List<MorseDictionaryEntry> {
        val commonnessByWord = mutableMapOf<String, Int>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEach
            val fields = line.split('\t', limit = 2)
            require(fields.size == 2) { "Malformed Morse dictionary entry" }
            val commonness = fields[0].toIntOrNull()
                ?: error("Malformed Morse dictionary commonness")
            val word = fields[1]
            require(word.isNotBlank() && word.all { it in 'a'..'z' }) {
                "Malformed Morse dictionary word"
            }
            commonnessByWord[word] = minOf(commonnessByWord[word] ?: Int.MAX_VALUE, commonness)
        }
        return commonnessByWord
            .map { (word, commonness) -> MorseDictionaryEntry(word, commonness) }
            .sortedBy(MorseDictionaryEntry::word)
    }
}

@Serializable
data class MorseMutationTarget(
    val mode: MorseModeTarget,
    val operationId: String,
) {
    init {
        require(operationId.isNotBlank()) { "Morse operation id must not be blank" }
    }
}

@Serializable
enum class MorseMutationKind {
    COMMIT_WORD,
    DELETE_COMMITTED_WORD,
}

data class MorseMutationIntent(
    val target: MorseMutationTarget,
    val kind: MorseMutationKind,
    val text: String? = null,
    val deleteStart: Int? = null,
    val deleteEndExclusive: Int? = null,
    val expectedText: String? = null,
) {
    init {
        when (kind) {
            MorseMutationKind.COMMIT_WORD -> require(!text.isNullOrBlank()) {
                "Morse commit text must not be blank"
            }
            MorseMutationKind.DELETE_COMMITTED_WORD -> {
                require(text == null) { "Morse delete cannot include commit text" }
                require(deleteStart != null && deleteEndExclusive != null) {
                    "Morse delete requires a range"
                }
                require(deleteStart >= 0 && deleteStart < deleteEndExclusive) {
                    "Morse delete range is invalid"
                }
                require(expectedText != null) { "Morse delete requires expected text" }
            }
        }
    }
}

data class MorseCommittedWord(
    val text: String,
    val start: Int,
    val endExclusive: Int,
)

data class MorseModeState(
    val target: MorseModeTarget? = null,
    val dotDashBuffer: String = "",
    val word: String = "",
    val committedWords: List<MorseCommittedWord> = emptyList(),
    val deadlineAtMs: Long? = null,
    val held: Boolean = false,
    val pendingMutation: MorseMutationTarget? = null,
    val completion: MorseCompletionHint? = null,
)

sealed interface MorseInputEvent {
    data class CharacterFinished(val character: Char?) : MorseInputEvent

    data object CharacterDeleted : MorseInputEvent

    data object WordCleared : MorseInputEvent

    data object MutationAcknowledged : MorseInputEvent

    data class MutationRequested(val intent: MorseMutationIntent) : MorseInputEvent

    data class Exited(val forced: Boolean = false) : MorseInputEvent

    data object Interrupted : MorseInputEvent

    data object Ignored : MorseInputEvent
}

@Serializable
enum class MorseMutationOutcome {
    ACKNOWLEDGED,
    REJECTED,
    UNCERTAIN,
}

/** Handles the invisible Morse buffer and the exact operation fences around it. */
class MorseInputController(
    private val quietIntervalMs: Long = DEFAULT_MORSE_QUIET_INTERVAL_MS,
    private val longPressTimeoutMs: Long = 500L,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class HeldInteraction(
        val startedAtMs: Long,
        val operation: PokerOperation?,
        val remainingMs: Long?,
        val ignored: Boolean,
    )

    private var mode = MorseModeState()
    private var held: HeldInteraction? = null
    private var pendingIntent: MorseMutationIntent? = null
    private var lastEventTimeMs = 0L

    init {
        require(quietIntervalMs in MIN_MORSE_QUIET_INTERVAL_MS..MAX_MORSE_QUIET_INTERVAL_MS) {
            "Morse quiet interval must be between 300 and 2000 ms"
        }
        require(quietIntervalMs % MORSE_QUIET_INTERVAL_STEP_MS == 0L) {
            "Morse quiet interval must use 50 ms steps"
        }
        require(longPressTimeoutMs >= 0) { "Morse long-press timeout must not be negative" }
    }

    val isActive: Boolean
        get() = mode.target != null

    val nextDeadlineAtMs: Long?
        get() = mode.deadlineAtMs

    fun state(): MorseModeState = mode

    fun pendingTarget(): MorseMutationTarget? = mode.pendingMutation

    fun begin(target: MorseModeTarget, atMs: Long = 0L): MorseModeState {
        require(atMs >= 0) { "Morse start time must not be negative" }
        require(!isActive) { "Morse mode is already active" }
        mode = MorseModeState(target = target)
        held = null
        pendingIntent = null
        lastEventTimeMs = atMs
        return mode
    }

    fun advance(atMs: Long): MorseInputEvent? {
        if (!isActive || atMs < lastEventTimeMs || mode.held || mode.pendingMutation != null) {
            return null
        }
        lastEventTimeMs = atMs
        val deadline = mode.deadlineAtMs ?: return null
        if (atMs < deadline) return null
        val character = MorseCode.decode(mode.dotDashBuffer)
        mode = mode.copy(
            dotDashBuffer = "",
            word = mode.word + character?.lowercaseChar()?.toString().orEmpty(),
            deadlineAtMs = null,
            completion = null,
        )
        return MorseInputEvent.CharacterFinished(character)
    }

    fun reduce(interaction: PokerInteraction): MorseInputEvent? {
        if (!isActive) return null
        if (interaction.eventTimeMs < lastEventTimeMs) return MorseInputEvent.Ignored
        if (interaction.phase == PokerInteractionPhase.BEGIN) {
            advance(interaction.eventTimeMs)
        }
        lastEventTimeMs = interaction.eventTimeMs
        return when (interaction.phase) {
            PokerInteractionPhase.BEGIN -> beginInteraction(interaction)
            PokerInteractionPhase.UPDATE -> updateInteraction(interaction)
            PokerInteractionPhase.RELEASE -> releaseInteraction(interaction)
            PokerInteractionPhase.CANCEL -> cancelInteraction(interaction)
        }
    }

    fun abort(): MorseInputEvent? = if (isActive) {
        mode = MorseModeState()
        held = null
        pendingIntent = null
        MorseInputEvent.Exited(forced = true)
    } else {
        null
    }

    fun applyMutation(
        target: MorseMutationTarget,
        outcome: MorseMutationOutcome,
        fieldRevision: Long?,
        cursorPosition: Int?,
    ): MorseInputEvent? {
        val intent = pendingIntent?.takeIf { it.target == target } ?: return null
        pendingIntent = null
        if (outcome != MorseMutationOutcome.ACKNOWLEDGED ||
            fieldRevision == null ||
            cursorPosition == null
        ) {
            mode = MorseModeState()
            held = null
            return MorseInputEvent.Interrupted
        }
        require(fieldRevision >= 0) { "Morse result revision must not be negative" }
        require(cursorPosition >= 0) { "Morse result cursor must not be negative" }
        val currentTarget = mode.target ?: return MorseInputEvent.Ignored
        val nextTarget = currentTarget.copy(
            revision = fieldRevision,
            cursorPosition = cursorPosition,
        )
        mode = when (intent.kind) {
            MorseMutationKind.COMMIT_WORD -> {
                val text = intent.text.orEmpty()
                val units = ComposerDraft.fromText(text).visibleUnits().size
                mode.copy(
                    target = nextTarget,
                    word = "",
                    committedWords = mode.committedWords + MorseCommittedWord(
                        text = text,
                        start = target.mode.cursorPosition,
                        endExclusive = target.mode.cursorPosition + units,
                    ),
                    pendingMutation = null,
                )
            }
            MorseMutationKind.DELETE_COMMITTED_WORD -> mode.copy(
                target = nextTarget,
                committedWords = mode.committedWords.dropLast(1),
                pendingMutation = null,
            )
        }
        return MorseInputEvent.MutationAcknowledged
    }

    fun applyCompletion(
        target: MorseModeTarget,
        prefix: String,
        suffix: String?,
    ): Boolean {
        val currentTarget = mode.target ?: return false
        if (target != currentTarget || prefix != mode.word || mode.dotDashBuffer.isNotEmpty()) {
            return false
        }
        mode = mode.copy(
            completion = suffix?.takeIf(String::isNotEmpty)?.let {
                MorseCompletionHint(target, prefix, it)
            },
        )
        return true
    }

    private fun beginInteraction(interaction: PokerInteraction): MorseInputEvent? {
        if (held != null) return MorseInputEvent.Ignored
        val remaining = mode.deadlineAtMs?.let { deadline ->
            (deadline - interaction.eventTimeMs).coerceAtLeast(0L)
        }
        mode = mode.copy(deadlineAtMs = null, held = true)
        held = HeldInteraction(
            startedAtMs = interaction.eventTimeMs,
            operation = interaction.operation,
            remainingMs = remaining,
            ignored = mode.pendingMutation != null,
        )
        return null
    }

    private fun updateInteraction(interaction: PokerInteraction): MorseInputEvent? {
        val current = held ?: return MorseInputEvent.Ignored
        held = current.copy(operation = interaction.operation ?: current.operation)
        return null
    }

    private fun cancelInteraction(interaction: PokerInteraction): MorseInputEvent {
        val current = held ?: return MorseInputEvent.Ignored
        held = null
        restoreDeadline(current, interaction.eventTimeMs)
        return MorseInputEvent.Ignored
    }

    private fun releaseInteraction(interaction: PokerInteraction): MorseInputEvent? {
        val current = held ?: return MorseInputEvent.Ignored
        held = null
        val operation = interaction.operation ?: current.operation
        if (current.ignored || pendingIntent != null ||
            (mode.dotDashBuffer.isNotEmpty() && operation in setOf(
                PokerOperation.TAPTAP,
                PokerOperation.DOWN,
                PokerOperation.UP,
                PokerOperation.FN,
            ))
        ) {
            restoreDeadline(current, interaction.eventTimeMs)
            return MorseInputEvent.Ignored
        }
        return when (operation) {
            PokerOperation.TAP -> releaseTap(interaction.durationMs, interaction.eventTimeMs)
            PokerOperation.TAPTAP -> deleteCharacterOrIgnore()
            PokerOperation.DOWN -> commitWord(useCompletion = true)
            PokerOperation.UP -> commitWord()
            PokerOperation.FN -> releaseFunction(interaction.durationMs)
            PokerOperation.LEFT,
            PokerOperation.RIGHT,
            null,
            -> MorseInputEvent.Ignored
        }
    }

    private fun releaseTap(durationMs: Long, atMs: Long): MorseInputEvent {
        if (durationMs < 0) return MorseInputEvent.Ignored
        val symbol = if (durationMs < longPressTimeoutMs) "." else "-"
        mode = mode.copy(
            dotDashBuffer = mode.dotDashBuffer + symbol,
            deadlineAtMs = safeAdd(atMs, quietIntervalMs),
            held = false,
            completion = null,
        )
        return MorseInputEvent.Ignored
    }

    private fun deleteCharacterOrIgnore(): MorseInputEvent {
        if (mode.dotDashBuffer.isNotEmpty()) return MorseInputEvent.Ignored
        if (mode.word.isEmpty()) return MorseInputEvent.Ignored
        mode = mode.copy(word = mode.word.dropLast(1), completion = null)
        return MorseInputEvent.CharacterDeleted
    }

    private fun releaseFunction(durationMs: Long): MorseInputEvent {
        if (durationMs >= longPressTimeoutMs) {
            mode = MorseModeState()
            pendingIntent = null
            return MorseInputEvent.Exited()
        }
        if (mode.word.isNotEmpty()) {
            mode = mode.copy(word = "", completion = null)
            return MorseInputEvent.WordCleared
        }
        val committed = mode.committedWords.lastOrNull() ?: return MorseInputEvent.Ignored
        return deleteCommittedWord(committed)
    }

    private fun commitWord(useCompletion: Boolean = false): MorseInputEvent {
        if (mode.word.isEmpty() || pendingIntent != null) return MorseInputEvent.Ignored
        val completion = mode.completion?.takeIf {
            useCompletion &&
                it.target == mode.target &&
                it.prefix == mode.word &&
                mode.dotDashBuffer.isEmpty()
        }
        val target = MorseMutationTarget(
            mode = checkNotNull(mode.target),
            operationId = sessionId().also { require(it.isNotBlank()) },
        )
        val intent = MorseMutationIntent(
            target = target,
            kind = MorseMutationKind.COMMIT_WORD,
            text = mode.word + completion?.suffix.orEmpty() + " ",
        )
        pendingIntent = intent
        mode = mode.copy(pendingMutation = target, completion = null)
        return MorseInputEvent.MutationRequested(intent)
    }

    private fun deleteCommittedWord(committed: MorseCommittedWord): MorseInputEvent {
        val target = MorseMutationTarget(
            mode = checkNotNull(mode.target),
            operationId = sessionId().also { require(it.isNotBlank()) },
        )
        val intent = MorseMutationIntent(
            target = target,
            kind = MorseMutationKind.DELETE_COMMITTED_WORD,
            deleteStart = committed.start,
            deleteEndExclusive = committed.endExclusive,
            expectedText = committed.text,
        )
        pendingIntent = intent
        mode = mode.copy(pendingMutation = target)
        return MorseInputEvent.MutationRequested(intent)
    }

    private fun restoreDeadline(held: HeldInteraction, atMs: Long) {
        if (held.remainingMs != null && mode.dotDashBuffer.isNotEmpty()) {
            mode = mode.copy(
                deadlineAtMs = safeAdd(atMs, held.remainingMs),
                held = false,
            )
        } else {
            mode = mode.copy(held = false)
        }
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

private fun Char.isAsciiLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** The written-character table from ITU-R M.1677-1; procedural signals are intentionally absent. */
object MorseCode {
    private val sequenceToCharacter = mapOf(
        ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
        "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
        "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
        ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
        "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
        "--.." to 'Z', "..-.." to 'É',
        ".----" to '1', "..---" to '2', "...--" to '3', "....-" to '4', "....." to '5',
        "-...." to '6', "--..." to '7', "---.." to '8', "----." to '9', "-----" to '0',
        ".-.-.-" to '.', "--..--" to ',', "---..." to ':', "..--.." to '?',
        ".----." to '’', "-....-" to '–', "-..-." to '/', "-.--." to '(',
        "-.--.-" to ')', ".-..-." to '"', "-...-" to '=', ".-.-." to '+',
        ".--.-." to '@',
    )

    val printableCharacters: Set<Char>
        get() = sequenceToCharacter.values.toSet()

    fun decode(sequence: String): Char? = sequenceToCharacter[sequence]
}
