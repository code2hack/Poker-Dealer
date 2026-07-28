package com.code2hack.pokerdealer.domain

import kotlinx.serialization.Serializable

const val MAX_CARD_UTF8_BYTES = 512 * 1_024
const val DEFAULT_MAX_REPLY_UTF8_BYTES = 64 * 1_024

@Serializable
data class CodexHost(
    val id: String,
    val displayName: String,
    val kind: CodexHostKind,
    val architecture: HostArchitecture,
    val distribution: CodexDistribution,
    val connectionRoutes: List<HostConnectionRoute>,
    val activeConnectionRoute: HostConnectionRoute? = null,
    val availabilityClass: HostAvailabilityClass,
    val connectionState: HostConnectionState,
    val daemonState: DaemonState = DaemonState.UNKNOWN,
    val codexVersion: String? = null,
    val appServerVersion: String? = null,
)

@Serializable
enum class CodexHostKind {
    LINUX_WORKSTATION,
    TERMUX_ANDROID,
}

@Serializable
enum class HostArchitecture {
    LINUX_ARM64,
    LINUX_X86_64,
    ANDROID_ARM64,
}

@Serializable
enum class CodexDistribution {
    OPENAI_UPSTREAM,
    TERMUX_COMMUNITY,
}

@Serializable
enum class HostConnectionRoute {
    SSH_LAN,
    SSH_EMBEDDED_TSNET,
    SSH_EXTERNAL_TAILSCALE,
    SSH_LOOPBACK,
}

@Serializable
enum class HostAvailabilityClass {
    PERSISTENT,
    OPPORTUNISTIC,
}

@Serializable
enum class HostConnectionState {
    DISCONNECTED,
    WAITING_FOR_HOST_APP,
    WAITING_FOR_TAILNET_LOGIN,
    STARTING_TAILNET,
    CONNECTING_ROUTE,
    CONNECTING_SSH,
    CHECKING_DAEMON,
    CONNECTING_PROXY,
    INITIALIZING,
    CONNECTED,
    SUSPENDED,
    BACKING_OFF,
    ERROR,
}

@Serializable
enum class DaemonState {
    UNKNOWN,
    STOPPED,
    STARTING,
    READY,
    RESTARTING,
    ERROR,
}

@Serializable
data class CodexThreadLocator(
    val hostId: String,
    val threadId: String,
)

object InitialCodexHosts {
    val spark = CodexHost(
        id = "spark",
        displayName = "DGX Spark",
        kind = CodexHostKind.LINUX_WORKSTATION,
        architecture = HostArchitecture.LINUX_ARM64,
        distribution = CodexDistribution.OPENAI_UPSTREAM,
        connectionRoutes = listOf(
            HostConnectionRoute.SSH_LAN,
            HostConnectionRoute.SSH_EMBEDDED_TSNET,
            HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
        ),
        availabilityClass = HostAvailabilityClass.PERSISTENT,
        connectionState = HostConnectionState.DISCONNECTED,
    )

    val u4090 = CodexHost(
        id = "u4090",
        displayName = "u4090",
        kind = CodexHostKind.LINUX_WORKSTATION,
        architecture = HostArchitecture.LINUX_X86_64,
        distribution = CodexDistribution.OPENAI_UPSTREAM,
        connectionRoutes = listOf(
            HostConnectionRoute.SSH_LAN,
            HostConnectionRoute.SSH_EMBEDDED_TSNET,
            HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
        ),
        availabilityClass = HostAvailabilityClass.PERSISTENT,
        connectionState = HostConnectionState.DISCONNECTED,
    )

    val fold6Termux = CodexHost(
        id = "fold6-termux",
        displayName = "Fold6 Termux",
        kind = CodexHostKind.TERMUX_ANDROID,
        architecture = HostArchitecture.ANDROID_ARM64,
        distribution = CodexDistribution.TERMUX_COMMUNITY,
        connectionRoutes = listOf(HostConnectionRoute.SSH_LOOPBACK),
        availabilityClass = HostAvailabilityClass.OPPORTUNISTIC,
        connectionState = HostConnectionState.DISCONNECTED,
    )

    val workstations = listOf(spark, u4090)
    val all = workstations + fold6Termux
}

@Serializable
data class Conversation(
    val id: String,
    val locator: CodexThreadLocator,
    val alias: String,
    val state: ConversationState,
    val intendedControlSurface: ControlSurface = ControlSurface.NONE,
    val lastSequence: Long,
    val unreadCount: Int,
)

@Serializable
enum class ConversationState {
    NOT_LOADED,
    IDLE,
    ACTIVE,
    OFFLINE,
    SYSTEM_ERROR,
    ARCHIVED,
}

@Serializable
enum class ControlSurface {
    NONE,
    LOCAL_TUI,
    DEALER,
    POKER,
}

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
enum class CardRole {
    USER,
    AGENT,
    TOOL,
    SYSTEM,
}

@Serializable
enum class CardState {
    OPEN,
    COMMITTED,
    CORRECTED,
    FAILED,
}

@Serializable
enum class DeliveryState {
    LOCAL_PENDING,
    ACCEPTED,
    DELIVERED,
    REJECTED,
    UNKNOWN,
}

@Serializable
enum class CardSource {
    POKER_INPUT,
    DEALER_INPUT,
    CODEX_USER_MESSAGE,
    CODEX_AGENT_MESSAGE,
    CODEX_PLAN,
    CODEX_REASONING,
    CODEX_COMMAND,
    CODEX_FILE_CHANGE,
    CODEX_APPROVAL,
    SYSTEM,
}

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
enum class AgentDisplayMode {
    FULL,
    CONCLUSION_ONLY,
    CONCLUSION_THEN_FULL,
}

@Serializable
data class InputPolicy(
    val requireReviewForAsr: Boolean = true,
    val requireReviewForMorse: Boolean = true,
    val allowTurnStart: Boolean = true,
    val allowTurnSteer: Boolean = true,
    val allowTurnInterrupt: Boolean = true,
    val allowApprovalDecision: Boolean = true,
    val maxInputUtf8Bytes: Int = DEFAULT_MAX_REPLY_UTF8_BYTES,
)
