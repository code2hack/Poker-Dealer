# ADR 0004: Embed a userspace Tailscale node in Dealer

- **Status:** Accepted
- **Date:** 2026-07-27
- **Amends:** ADR 0002 workstation connectivity

## Context

Dealer must reach DGX Spark and u4090 from the Fold6 while the user may also run Hiddify, Clash, or another Android VPN service. Android normally permits one active `VpnService` owner, so requiring the standalone Tailscale Android application to remain connected would conflict with that workflow.

The product needs tailnet reachability only for Dealer-owned sockets, principally SSH to the two workstation hosts. It does not need to route the whole phone, provide an exit node, or expose a system-wide VPN.

Tailscale's Go `tsnet` library can run a userspace tailnet node inside one process without creating a TUN interface or Android `VpnService`. Android integration still requires custom engineering because there is no first-party Kotlin `tsnet` SDK.

## Decision

1. Dealer will embed a userspace Tailscale node based on `tsnet` as its primary remote route to DGX Spark and u4090.
2. The embedded node MUST NOT create or request Android `VpnService` ownership.
3. Only Dealer-owned workstation connections enter the embedded tailnet. Dealer MUST NOT become a general device VPN, exit-node client, or default-route provider.
4. The embedded Go component will be packaged for Android behind a narrow AAR/JNI boundary, initially using `gomobile bind` unless a better maintained integration becomes available.
5. Kotlin networking code MUST consume a small route-neutral TCP abstraction. Go `net.Conn` objects MUST NOT leak into Compose or app-server protocol code.
6. The first implementation MAY expose either:
   - an authenticated loopback SOCKS5 endpoint owned by Dealer; or
   - a per-destination loopback TCP tunnel.
   The choice MUST be isolated behind the same Kotlin dialer interface.
7. Dealer's workstation route priority is:
   1. trusted LAN SSH;
   2. embedded-tsnet SSH;
   3. optional external-Tailscale SSH fallback.
8. Fold6 Termux remains loopback SSH and does not use the embedded tailnet route.
9. The embedded node has its own tailnet identity, expected to be named similarly to `dealer-fold6`, and MUST use least-privilege tailnet policy permitting only required workstation services such as SSH.
10. Node identity and engine state are stored only in Dealer-private storage. Long-lived auth keys or reusable enrollment secrets MUST NOT be stored as plaintext in Room or DataStore.
11. Interactive browser enrollment is the default onboarding path. One-time or ephemeral auth-key enrollment MAY be supported later with explicit secret-handling rules.
12. The standalone Tailscale Android application is not required for Dealer operation, but remains an optional fallback and diagnostic tool.
13. Underlying Hiddify/Clash routing may still affect UDP, coordination, or DERP reachability. Compatibility MUST be proven on the Fold6 with the user's real VPN configuration.
14. Embedded `tsnet` work follows the initial transport-neutral workstation app-server slice. The first slice MUST isolate SSH behind a dialer/stream interface so adding `tsnet` does not rewrite SSH or app-server layers.

## Security requirements

- Dealer MUST pin or explicitly approve SSH host keys independently of Tailscale identity.
- Tailnet identity is an additional private-routing boundary, not a replacement for SSH authentication.
- The Go bridge MUST bind any local proxy or tunnel only to loopback and use unguessable or authenticated access where another process could reach it.
- Logs MUST NOT contain Tailscale state keys, auth keys, OAuth tokens, private SSH keys, or proxy credentials.
- Tailnet logout and node reset MUST be explicit user actions.
- Dealer MUST surface the embedded node name, login state, and connection state in diagnostics.

## Consequences

### Positive

- Hiddify or Clash may retain Android's `VpnService` slot.
- Dealer no longer depends on the standalone Tailscale app being connected.
- Only Dealer traffic joins the tailnet.
- Workstation SSH remains private without exposing public ports.
- LAN, embedded-tailnet, and external-tailnet routes share one SSH/app-server stack.

### Negative

- The Android build gains Go, `gomobile`, native libraries, and ABI packaging complexity.
- The APK becomes larger.
- Updating the embedded Tailscale engine requires a Dealer release.
- There is no turnkey first-party Kotlin `tsnet` SDK.
- Android lifecycle, foreground-service, direct-UDP, DERP, and third-party-VPN coexistence require real-device testing.

### Mitigations

- Keep all Tailscale code in one module and expose a narrow Kotlin interface.
- Pin and regularly update the Tailscale Go dependency.
- Add reproducible Android ARM64 build checks and native-library packaging tests.
- Prefer LAN at home to reduce mobile tailnet overhead.
- Preserve external Tailscale as a fallback while embedded integration matures.
- Treat loss of direct UDP as degraded connectivity when DERP still works, not as silent success or permanent failure.

## Rejected alternatives

### Require the standalone Tailscale Android app

Rejected as the primary route because it occupies Android's single VPN-service slot and conflicts with the user's other VPN workflow.

### Reuse Termux userspace `tailscaled`

Rejected as Dealer's primary route because Dealer connectivity would depend on Termux, its background lifecycle, a cross-app localhost proxy, and separate operational recovery. It may remain a manual diagnostic fallback.

### Route all phone traffic through embedded Tailscale

Rejected because Dealer only requires private application sockets and must not become a system VPN.

### Expose workstation SSH publicly

Rejected because it weakens the private-network boundary and is unnecessary.
