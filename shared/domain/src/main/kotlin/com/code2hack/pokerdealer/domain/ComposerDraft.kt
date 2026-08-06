package com.code2hack.pokerdealer.domain

import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val COMPOSER_PHOTO_GLYPH = "📷"

@Serializable
sealed class ComposerElement {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : ComposerElement() {
        init {
            require(value.isNotEmpty()) { "Text elements must not be empty" }
        }
    }

    @Serializable
    @SerialName("photo")
    data class Photo(val assetId: String) : ComposerElement() {
        init {
            require(assetId.isNotBlank()) { "Photo asset ids must not be blank" }
        }
    }
}

@Serializable
data class ComposerDraft(
    val revision: Long = 0L,
    val elements: List<ComposerElement> = emptyList(),
) {
    init {
        require(revision >= 0) { "Draft revision must not be negative" }
    }

    val isEmpty: Boolean
        get() = elements.isEmpty()

    val isSubmittable: Boolean
        get() = elements.any {
            when (it) {
                is ComposerElement.Text -> it.value.isNotBlank()
                is ComposerElement.Photo -> true
            }
        }

    /** Text shown on the HUD. A photo glyph is only a rendering; the element retains its asset id. */
    val displayText: String
        get() = buildString {
            elements.forEach { element ->
                when (element) {
                    is ComposerElement.Text -> append(element.value)
                    is ComposerElement.Photo -> append(COMPOSER_PHOTO_GLYPH)
                }
            }
        }

    val cursorCount: Int
        get() = visibleUnits().size + 1

    fun normalized(): ComposerDraft = copy(elements = normalize(elements))

    fun nextWordStart(cursorPosition: Int): Int {
        validateCursor(cursorPosition)
        val words = wordRanges()
        val current = words.firstOrNull { cursorPosition in it.start until it.endExclusive }
        return if (current != null) {
            words.firstOrNull { it.start >= current.endExclusive }?.start ?: cursorCount - 1
        } else {
            words.firstOrNull { it.start >= cursorPosition }?.start ?: cursorCount - 1
        }
    }

    fun previousWordStart(cursorPosition: Int): Int {
        validateCursor(cursorPosition)
        return wordRanges().lastOrNull { it.start < cursorPosition }?.start ?: 0
    }

    fun deleteThroughNextWord(cursorPosition: Int): ComposerDeletion? {
        validateCursor(cursorPosition)
        val unitCount = cursorCount - 1
        if (cursorPosition == unitCount) return null
        val words = wordRanges()
        val current = words.firstOrNull { cursorPosition in it.start until it.endExclusive }
        val nextStart = if (current != null) {
            words.firstOrNull { it.start >= current.endExclusive }?.start ?: unitCount
        } else {
            words.firstOrNull { it.start >= cursorPosition }?.start ?: unitCount
        }
        if (nextStart <= cursorPosition) return null
        val units = visibleUnits()
        return ComposerDeletion(
            start = cursorPosition,
            endExclusive = nextStart,
            containsPhoto = units.subList(cursorPosition, nextStart).any(ComposerUnit::isPhoto),
        )
    }

    fun replaceUnits(
        start: Int,
        endExclusive: Int,
        replacement: List<ComposerElement> = emptyList(),
    ): ComposerDraft {
        validateRange(start, endExclusive)
        val replacementUnits = ComposerDraft(elements = replacement).visibleUnits()
        val units = visibleUnits()
        return copy(
            elements = unitsToElements(
                units.take(start) + replacementUnits + units.drop(endExclusive),
            ),
        )
    }

    fun insertText(cursorPosition: Int, text: String): ComposerDraft =
        if (text.isEmpty()) this else replaceUnits(cursorPosition, cursorPosition, listOf(ComposerElement.Text(text)))

    fun insertPhoto(cursorPosition: Int, assetId: String): ComposerDraft =
        replaceUnits(cursorPosition, cursorPosition, listOf(ComposerElement.Photo(assetId)))

    fun withRevision(revision: Long): ComposerDraft = copy(revision = revision)

    fun visibleUnits(): List<ComposerUnit> = elements.flatMap { element ->
        when (element) {
            is ComposerElement.Text -> graphemes(element.value).map(ComposerUnit::text)
            is ComposerElement.Photo -> listOf(ComposerUnit.photo(element.assetId))
        }
    }

    private fun wordRanges(): List<ComposerWordRange> {
        val units = visibleUnits()
        val ranges = mutableListOf<ComposerWordRange>()
        var index = 0
        while (index < units.size) {
            if (units[index].isPhoto) {
                ranges += ComposerWordRange(index, index + 1)
                index++
                continue
            }
            val start = index
            while (index < units.size && !units[index].isPhoto) index++
            ranges += textWordRanges(units.subList(start, index)).map { it.shift(start) }
        }
        return ranges
    }

    private fun validateCursor(cursorPosition: Int) {
        require(cursorPosition in 0 until cursorCount) { "Cursor position is outside the draft" }
    }

    private fun validateRange(start: Int, endExclusive: Int) {
        require(start >= 0 && start <= endExclusive && endExclusive < cursorCount) {
            "Draft range is outside the draft"
        }
    }

    companion object {
        fun fromText(text: String, revision: Long = 0L): ComposerDraft = ComposerDraft(
            revision = revision,
            elements = if (text.isEmpty()) emptyList() else listOf(ComposerElement.Text(text)),
        )

        fun fromLegacy(text: String): ComposerDraft = fromText(text)

        private fun normalize(elements: List<ComposerElement>): List<ComposerElement> = buildList {
            elements.forEach { element ->
                when (element) {
                    is ComposerElement.Text -> if (element.value.isNotEmpty()) {
                        val previous = lastOrNull()
                        if (previous is ComposerElement.Text) {
                            removeAt(lastIndex)
                            add(ComposerElement.Text(previous.value + element.value))
                        } else {
                            add(element)
                        }
                    }
                    is ComposerElement.Photo -> add(element)
                }
            }
        }

        private fun graphemes(text: String): List<String> {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(text)
            val result = mutableListOf<String>()
            var start = iterator.first()
            while (start != BreakIterator.DONE) {
                val end = iterator.next()
                if (end == BreakIterator.DONE) break
                result += text.substring(start, end)
                start = end
            }
            return result
        }

        private fun textWordRanges(units: List<ComposerUnit>): List<ComposerWordRange> {
            if (units.isEmpty()) return emptyList()
            val text = units.joinToString(separator = "") { it.text!! }
            val starts = IntArray(units.size)
            val ends = IntArray(units.size)
            var offset = 0
            units.forEachIndexed { index, unit ->
                starts[index] = offset
                offset += unit.text!!.length
                ends[index] = offset
            }
            val iterator = BreakIterator.getWordInstance(Locale.ROOT)
            iterator.setText(text)
            val ranges = mutableListOf<ComposerWordRange>()
            var segmentStart = iterator.first()
            while (segmentStart != BreakIterator.DONE) {
                val segmentEnd = iterator.next()
                if (segmentEnd == BreakIterator.DONE) break
                val segmentUnits = units.indices.filter { index ->
                    starts[index] >= segmentStart && ends[index] <= segmentEnd
                }
                var wordStart: Int? = null
                fun closeWord(endExclusive: Int) {
                    wordStart?.let { ranges += ComposerWordRange(it, endExclusive) }
                    wordStart = null
                }
                segmentUnits.forEach { index ->
                    val value = units[index].text!!
                    when {
                        value.isUnicodeWhitespace() -> closeWord(index)
                        value.isUnicodeWord() -> if (wordStart == null) wordStart = index
                        else -> {
                            closeWord(index)
                            ranges += ComposerWordRange(index, index + 1)
                        }
                    }
                }
                closeWord(segmentUnits.lastOrNull()?.plus(1) ?: 0)
                segmentStart = segmentEnd
            }
            return ranges
        }

        private fun unitsToElements(units: List<ComposerUnit>): List<ComposerElement> = buildList {
            units.forEach { unit ->
                if (unit.isPhoto) {
                    add(ComposerElement.Photo(unit.photoAssetId!!))
                } else {
                    val previous = lastOrNull()
                    if (previous is ComposerElement.Text) {
                        removeAt(lastIndex)
                        add(ComposerElement.Text(previous.value + unit.text!!))
                    } else {
                        add(ComposerElement.Text(unit.text!!))
                    }
                }
            }
        }
    }
}

data class ComposerUnit(
    val text: String?,
    val photoAssetId: String?,
) {
    init {
        require((text == null) xor (photoAssetId == null)) { "A composer unit is text or a photo" }
    }

    val isPhoto: Boolean
        get() = photoAssetId != null

    companion object {
        fun text(value: String) = ComposerUnit(value, null)
        fun photo(assetId: String) = ComposerUnit(null, assetId)
    }
}

data class ComposerDeletion(
    val start: Int,
    val endExclusive: Int,
    val containsPhoto: Boolean,
)

private data class ComposerWordRange(
    val start: Int,
    val endExclusive: Int,
) {
    fun shift(offset: Int) = copy(start = start + offset, endExclusive = endExclusive + offset)
}

private fun String.isUnicodeWhitespace(): Boolean =
    isNotEmpty() && codePoints().allMatch { Character.isWhitespace(it) || Character.isSpaceChar(it) }

private fun String.isUnicodeWord(): Boolean {
    if (isEmpty()) return false
    var hasBase = false
    for (codePoint in codePoints().toArray()) {
        val type = Character.getType(codePoint)
        if (Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
        ) {
            hasBase = true
        } else {
            return false
        }
    }
    return hasBase
}

object ComposerDraftCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(draft: ComposerDraft): String = json.encodeToString(draft.normalized())

    fun decode(raw: String): ComposerDraft? = runCatching {
        json.decodeFromString<ComposerDraft>(raw).normalized()
    }.getOrNull()

    fun decodeOrLegacy(raw: String): ComposerDraft = decode(raw) ?: ComposerDraft.fromLegacy(raw)
}

@Serializable
enum class ComposerSurface {
    THREAD_COMPOSER,
    REQUEST_PANEL,
}

@Serializable
data class ComposerEditTarget(
    val locator: CodexThreadLocator,
    val draftRevision: Long,
    val cursorPosition: Int,
    val controlGeneration: Long,
    val connectionEpoch: Long,
    val modeSession: String,
    val operationId: String,
    val surface: ComposerSurface = ComposerSurface.THREAD_COMPOSER,
) {
    init {
        require(draftRevision >= 0) { "Draft revision must not be negative" }
        require(cursorPosition >= 0) { "Cursor position must not be negative" }
        require(controlGeneration >= 0) { "Control generation must not be negative" }
        require(connectionEpoch >= 0) { "Connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Mode session must not be blank" }
        require(operationId.isNotBlank()) { "Operation id must not be blank" }
    }
}

data class PendingComposerTextDeletion(
    val target: ComposerEditTarget,
    val deletion: ComposerDeletion,
    val before: ComposerDraft,
    val optimistic: ComposerDraft,
)

sealed interface ComposerEditResult {
    val editor: ComposerEditorState

    data class Started(
        override val editor: ComposerEditorState,
        val mutation: PendingComposerTextDeletion,
    ) : ComposerEditResult

    data class NoChange(override val editor: ComposerEditorState) : ComposerEditResult

    data class PhotoTokenBoundary(
        override val editor: ComposerEditorState,
        val deletion: ComposerDeletion,
    ) : ComposerEditResult
}

data class ComposerEditorState(
    val locator: CodexThreadLocator,
    val draft: ComposerDraft,
    val cursorPosition: Int,
    val controlGeneration: Long,
    val connectionEpoch: Long,
    val modeSession: String,
    val pendingMutation: PendingComposerTextDeletion? = null,
) {
    init {
        require(cursorPosition in 0 until draft.cursorCount) { "Cursor position is outside the draft" }
        require(controlGeneration >= 0) { "Control generation must not be negative" }
        require(connectionEpoch >= 0) { "Connection epoch must not be negative" }
        require(modeSession.isNotBlank()) { "Mode session must not be blank" }
    }

    fun move(direction: ComposerWordDirection): ComposerEditorState {
        if (pendingMutation != null) return this
        val target = when (direction) {
            ComposerWordDirection.NEXT -> draft.nextWordStart(cursorPosition)
            ComposerWordDirection.PREVIOUS -> draft.previousWordStart(cursorPosition)
        }
        return copy(cursorPosition = target)
    }

    fun beginTextDeletion(target: ComposerEditTarget): ComposerEditResult {
        require(target.surface == ComposerSurface.THREAD_COMPOSER) {
            "Composer edits cannot target a server-request panel"
        }
        require(pendingMutation == null) { "Reconcile the previous composer mutation first" }
        require(target.locator == locator) { "Composer target thread does not match" }
        require(target.draftRevision == draft.revision) { "Composer target revision is stale" }
        require(target.cursorPosition == cursorPosition) { "Composer target cursor is stale" }
        require(target.controlGeneration == controlGeneration) { "Composer control generation is stale" }
        require(target.connectionEpoch == connectionEpoch) { "Composer connection epoch is stale" }
        require(target.modeSession == modeSession) { "Composer mode session is stale" }

        val deletion = draft.deleteThroughNextWord(cursorPosition)
            ?: return ComposerEditResult.NoChange(this)
        if (deletion.containsPhoto) {
            return ComposerEditResult.PhotoTokenBoundary(this, deletion)
        }
        val optimistic = draft
            .replaceUnits(deletion.start, deletion.endExclusive)
            .withRevision(draft.revision + 1)
        val mutation = PendingComposerTextDeletion(target, deletion, draft, optimistic)
        return ComposerEditResult.Started(
            copy(
                draft = optimistic,
                cursorPosition = deletion.start,
                pendingMutation = mutation,
            ),
            mutation,
        )
    }

    fun acknowledge(target: ComposerEditTarget, authoritative: ComposerDraft): ComposerEditorState {
        val pending = pendingMutation ?: error("No composer mutation is pending")
        require(pending.target == target) { "Composer acknowledgment target does not match" }
        val committed = authoritative.normalized()
        return copy(
            draft = committed,
            cursorPosition = pending.deletion.start.coerceAtMost(committed.cursorCount - 1),
            pendingMutation = null,
        )
    }

    fun rejectOrUncertain(
        target: ComposerEditTarget,
        authoritative: ComposerDraft,
    ): ComposerEditorState {
        val pending = pendingMutation ?: error("No composer mutation is pending")
        require(pending.target == target) { "Composer reconciliation target does not match" }
        return installAuthoritative(authoritative)
    }

    /** Installs a remote revision without attempting a cursor merge. */
    fun installAuthoritative(authoritative: ComposerDraft): ComposerEditorState {
        val installed = authoritative.normalized()
        return copy(
            draft = installed,
            cursorPosition = installed.cursorCount - 1,
            pendingMutation = null,
        )
    }

    companion object {
        fun atEnd(
            locator: CodexThreadLocator,
            draft: ComposerDraft,
            controlGeneration: Long,
            connectionEpoch: Long,
            modeSession: String,
        ) = ComposerEditorState(
            locator = locator,
            draft = draft.normalized(),
            cursorPosition = draft.cursorCount - 1,
            controlGeneration = controlGeneration,
            connectionEpoch = connectionEpoch,
            modeSession = modeSession,
        )
    }
}

enum class ComposerWordDirection {
    NEXT,
    PREVIOUS,
}
