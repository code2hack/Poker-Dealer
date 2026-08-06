package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.domain.ComposerDraft

internal fun insertDealerAsrText(
    draft: ComposerDraft,
    cursorPosition: Int,
    text: String,
): ComposerDraft = draft.insertText(cursorPosition, text).withRevision(draft.revision + 1)

internal fun insertDealerAsrText(
    currentText: String,
    cursorPosition: Int,
    text: String,
): String {
    val units = ComposerDraft.fromText(currentText).visibleUnits()
    val charOffset = units.take(cursorPosition).sumOf { it.text?.length ?: 0 }
    return currentText.substring(0, charOffset) + text + currentText.substring(charOffset)
}

internal fun dealerAsrCursorAfter(cursorPosition: Int, text: String): Int =
    cursorPosition + ComposerDraft.fromText(text).cursorCount - 1
