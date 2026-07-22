package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

const val MAX_CARD_UTF8_BYTES = 512 * 1_024
const val DEFAULT_MAX_REPLY_UTF8_BYTES = 64 * 1_024

@Serializable
data class PaneLocator(
    val hostId: String,
    val tmuxServerId: String,
    val paneId: String,
    val sessionId: String? = null,
    val windowId: String? = null,
    val sessionName: String? = null,
    val windowName: String? = null,
    val paneIndex: Int? = null,
    val paneTitle: String? = null,
    val currentCommand: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val locator: PaneLocator,
    val alias: String,
    val captureProfile: CaptureProfile = CaptureProfile.SCREEN_DIFF,
    val presentationPolicy: PresentationPolicy = PresentationPolicy(),
    val inputPolicy: InputPolicy = InputPolicy(),
    val state: ConversationState,
    val lastSequence: Long,
    val unreadCount: Int,
)

@Serializable
enum class ConversationState { ATTACHING, ONLINE, OFFLINE, STALE, DETACHED, ERROR }

@Serializable
enum class CaptureProfile { RAW_LINES, SCREEN_DIFF, SHELL_OSC133, STRUCTURED_AGENT }

@Serializable
data class Card(
    val id: String,
    val conversationId: String,
    val sequence: Long,
    val revision: Long,
    val groupId: String? = null,
    val partIndex: Int? = null,
    val partCount: Int? = null,
    val role: CardRole,
    val state: CardState,
    val fullText: String,
    val conclusion: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val delivery: DeliveryState? = null,
    val source: CardSource,
)

@Serializable
enum class CardRole { USER, AGENT, TERMINAL, SYSTEM }

@Serializable
enum class CardState { OPEN, COMMITTED, CORRECTED, FAILED }

@Serializable
enum class DeliveryState { LOCAL_PENDING, ACCEPTED, DELIVERED, REJECTED, UNKNOWN }

@Serializable
enum class CardSource { POKER_INPUT, DEALER_INPUT, TMUX_OUTPUT, STRUCTURED_EVENT, SYSTEM }

@Serializable
data class PresentationPolicy(
    val agentDisplayMode: AgentDisplayMode = AgentDisplayMode.CONCLUSION_THEN_FULL,
    val autoOpenOnNewOutput: Boolean = false,
    val markReadWhenVisible: Boolean = true,
    val softWrap: Boolean = true,
    val retainCards: Int = 200,
    val retainHours: Int = 48,
    val retainBytes: Long = 5L * 1_024 * 1_024,
)

@Serializable
enum class AgentDisplayMode { FULL, CONCLUSION_ONLY, CONCLUSION_THEN_FULL }

@Serializable
data class InputPolicy(
    val defaultSubmitMode: SubmitMode = SubmitMode.PASTE_AND_ENTER,
    val requireReviewForAsr: Boolean = true,
    val requireReviewForMorse: Boolean = true,
    val allowMultilineAutoSubmit: Boolean = false,
    val maxInputUtf8Bytes: Int = DEFAULT_MAX_REPLY_UTF8_BYTES,
    val allowedKeyCommands: Set<KeyCommand> = KeyCommand.DEFAULT_ALLOWED,
)

@Serializable
enum class SubmitMode { PASTE_ONLY, PASTE_AND_ENTER }

@Serializable
enum class KeyCommand {
    ENTER,
    ESCAPE,
    TAB,
    BACKSPACE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CONTROL_C,
    CONTROL_D,
    CONTROL_Z,
    PAGE_UP,
    PAGE_DOWN;

    companion object {
        val DEFAULT_ALLOWED = entries.toSet()
    }
}
