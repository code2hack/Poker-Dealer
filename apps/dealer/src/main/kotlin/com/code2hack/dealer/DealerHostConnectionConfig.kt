package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.CodexDistribution
import com.code2hack.pokerdealer.domain.CodexHost
import com.code2hack.pokerdealer.domain.CodexHostKind
import com.code2hack.pokerdealer.domain.HostArchitecture
import com.code2hack.pokerdealer.domain.HostAvailabilityClass
import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.domain.HostConnectionState

data class DealerHostConnectionConfig(
    val hostId: String,
    val lanHost: String,
    val tailnetHost: String,
    val sshUser: String,
    val loopbackSshPort: Int = 0,
    val displayName: String = hostId,
    val kind: CodexHostKind = if (loopbackSshPort > 0) {
        CodexHostKind.TERMUX_ANDROID
    } else {
        CodexHostKind.LINUX_WORKSTATION
    },
    val architecture: HostArchitecture = if (loopbackSshPort > 0) {
        HostArchitecture.ANDROID_ARM64
    } else {
        HostArchitecture.LINUX_X86_64
    },
    val distribution: CodexDistribution = if (loopbackSshPort > 0) {
        CodexDistribution.TERMUX_COMMUNITY
    } else {
        CodexDistribution.OPENAI_UPSTREAM
    },
    val availabilityClass: HostAvailabilityClass = if (loopbackSshPort > 0) {
        HostAvailabilityClass.OPPORTUNISTIC
    } else {
        HostAvailabilityClass.PERSISTENT
    },
) {
    init {
        require(hostId.isNotBlank()) { "Codex host ID is required" }
        require(displayName.isNotBlank()) { "Codex host display name is required" }
        require(sshUser.isNotBlank()) { "SSH user is required" }
        require(loopbackSshPort in 0..65_535) { "Invalid loopback SSH port" }
    }

    fun codexHost(): CodexHost = CodexHost(
        id = hostId,
        displayName = displayName,
        kind = kind,
        architecture = architecture,
        distribution = distribution,
        connectionRoutes = if (loopbackSshPort > 0) {
            listOf(HostConnectionRoute.SSH_LOOPBACK)
        } else {
            listOf(
                HostConnectionRoute.SSH_LAN,
                HostConnectionRoute.SSH_EMBEDDED_TSNET,
                HostConnectionRoute.SSH_EXTERNAL_TAILSCALE,
            )
        },
        availabilityClass = availabilityClass,
        connectionState = HostConnectionState.DISCONNECTED,
    )
}
