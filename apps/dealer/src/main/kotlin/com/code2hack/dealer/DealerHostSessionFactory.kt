package com.code2hack.dealer

import com.code2hack.pokerdealer.domain.HostConnectionRoute
import com.code2hack.pokerdealer.protocol.appserver.HostSessionConnectionConfig
import com.code2hack.pokerdealer.protocol.appserver.TermuxCommunityCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.UpstreamCodexDaemon
import com.code2hack.pokerdealer.protocol.appserver.USER_INPUT_REQUEST_METHOD
import com.code2hack.pokerdealer.protocol.host.JschHostSshClient
import com.code2hack.pokerdealer.protocol.host.RouteCapability
import com.code2hack.pokerdealer.protocol.host.RouteEndpoint
import com.code2hack.pokerdealer.protocol.host.SocketHostTcpDialer
import com.code2hack.pokerdealer.protocol.host.SshHostAuthentication

/**
 * Minimal extraction-era host configuration bridge.
 *
 * The retained donor host catalog is used only to preserve already-validated distribution metadata
 * while the later Dealer host-management UI is redesigned. Product code outside this bridge uses
 * host IDs and Codex-host capabilities rather than hardware nicknames.
 */
internal class DealerHostSessionFactory(
    private val profiles: DealerHostConnectionProfileStore,
    private val embeddedTailnet: EmbeddedTailnetController,
) {
    suspend fun create(hostId: String): HostSessionConnectionConfig {
        val stored = profiles.load(hostId)
        val config = stored.config
        val host = config.codexHost()

        val endpoints = buildMap {
            if (config.lanHost.isNotBlank()) {
                put(hostId to HostConnectionRoute.SSH_LAN, RouteEndpoint(config.lanHost))
            }
            if (config.tailnetHost.isNotBlank()) {
                put(hostId to HostConnectionRoute.SSH_EXTERNAL_TAILSCALE, RouteEndpoint(config.tailnetHost))
            }
            if (config.loopbackSshPort > 0) {
                put(
                    hostId to HostConnectionRoute.SSH_LOOPBACK,
                    RouteEndpoint("127.0.0.1", config.loopbackSshPort),
                )
            }
        }
        val direct = SocketHostTcpDialer(
            endpoints = endpoints,
            capabilities = endpoints.keys.associateWith { RouteCapability.SUPPORTED_CONFIGURED },
        )
        val tailnet = EmbeddedTailnetHostTcpDialer(
            engine = embeddedTailnet.engine,
            destinations = config.tailnetHost.takeIf(String::isNotBlank)?.let { mapOf(hostId to it) }.orEmpty(),
            state = embeddedTailnet::connectionState,
        )
        return HostSessionConnectionConfig(
            host = host,
            dialer = DealerHostRouteDialer(direct, tailnet),
            sshClient = JschHostSshClient(
                mapOf(
                    hostId to SshHostAuthentication(
                        username = config.sshUser,
                        privateKey = stored.privateKey,
                        knownHosts = stored.knownHosts,
                    ),
                ),
            ),
            daemon = if (host.kind == com.code2hack.pokerdealer.domain.CodexHostKind.TERMUX_ANDROID) {
                TermuxCommunityCodexDaemon()
            } else {
                UpstreamCodexDaemon()
            },
            qualifiedDescendantFilterVersions = setOf("0.146.0"),
            qualifiedServerRequestVersions = mapOf(USER_INPUT_REQUEST_METHOD to setOf("0.146.0")),
        )
    }
}
