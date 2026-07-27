# ADR 0003: Add Fold6 Termux as a first-class Codex host

- **Status:** Accepted
- **Date:** 2026-07-27
- **Amends:** ADR 0002
- **Related:** ADR 0004 defines Dealer's workstation route strategy

## Context

The initial app-server architecture named DGX Spark and u4090 as the two execution hosts. The user also performs meaningful development directly inside Termux on the Fold6, especially Android and Rokid application development and debugging.

A compatible community Termux Codex distribution can provide the Codex CLI and app-server behavior in Android's Termux userspace. Treating Termux only as a terminal fallback would prevent Dealer and Poker from continuing phone-local Codex threads through the same structured thread/turn/item interface used for the workstations.

Dealer and Termux run as separate Android applications. Dealer therefore cannot assume direct access to Termux-private files or Unix-domain sockets.

## Decision

1. Fold6 Termux is the third supported Codex host.
2. The host identifier is stable and Dealer-assigned; the recommended initial ID is `fold6-termux`.
3. Termux uses a compatible community Codex distribution rather than claiming support from the upstream Linux binary.
4. Termux runs a long-lived daemon-managed app-server on a Termux-private Unix control socket.
5. Dealer connects to a Termux `sshd` listener through loopback and runs `codex app-server proxy` inside Termux.
6. Dealer uses the same app-server JSON-RPC adapter for Termux, DGX Spark, and u4090.
7. Distribution-specific lifecycle commands, installation, updates, and capability observations remain isolated in the host lifecycle adapter.
8. Termux support is capability-based. A build is usable only after live checks confirm daemon lifecycle, Unix-socket binding, proxy operation, initialization, required thread/turn methods, and reconnect behavior.
9. Fold6 Termux is an opportunistic mobile host. Dealer must treat Android suspension, process death, missing `sshd`, and a stopped daemon as recoverable host states.
10. Termux threads remain bound to the Fold6. Moving work to Spark or u4090 requires repository synchronization and an explicit new-thread handoff.
11. Termux remains the full terminal/editor/recovery surface. Adding it as a backend does not make Dealer a terminal emulator.
12. The embedded tailnet in ADR 0004 is for remote workstation reachability; it is not part of the Termux loopback path.

## Connection shape

```text
Dealer Android app
    │
    │ SSH to loopback
    ▼
Termux sshd
    │
    │ codex app-server proxy
    ▼
Termux-private Unix socket
    │
    ▼
daemon-managed codex app-server
    │
    ▼
Fold6-local Codex threads and repositories
```

## Domain implications

`CodexHost` must distinguish:

- host kind: Linux workstation or Android/Termux;
- architecture: Linux ARM64, Linux x86-64, or Android ARM64;
- distribution: upstream Codex or community Termux Codex;
- an ordered route list and current active route;
- availability class: persistent or opportunistic.

Fold6 Termux's required route list contains only `SSH_LOOPBACK`. Workstation hosts may have the LAN, embedded-tsnet, and external-Tailscale routes defined by ADR 0004.

Durable thread identity remains `(hostId, threadId)`.

## Consequences

### Positive

- Phone-local Android and Rokid development becomes visible in Dealer and Poker without terminal scraping.
- All three hosts share one structured Codex client architecture.
- Dealer can switch among Spark, u4090, and Termux threads from one thread dashboard.
- Termux-specific failures remain explicit instead of being confused with remote network failures.

### Negative

- The community Termux distribution may not match every upstream release or daemon behavior.
- Android lifecycle and battery policy can stop the host unexpectedly.
- Dealer must maintain a loopback SSH path and Termux-specific onboarding/update UX.
- Workstation-grade always-on guarantees are not possible for this host.

## Rejected alternatives

### Keep Termux only as a terminal fallback

Rejected because it would exclude genuine phone-local Codex work from Dealer and Poker continuity.

### Let Dealer access the Termux Unix socket directly

Rejected because Android application sandbox boundaries make direct private-socket coupling brittle and non-portable.

### Reintroduce tmux as the Termux backend

Rejected because the product backend remains Codex app-server on every host. tmux may still preserve arbitrary terminal processes, but it is not a Poker–Dealer conversation source.
