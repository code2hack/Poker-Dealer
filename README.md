# Poker–Dealer

Poker–Dealer is a private mobile and wearable client for Codex threads running on the user's configured hosts.

`SPEC.md` is the normative product and architecture contract. `AGENTS.md` is the mandatory entry point for every fresh implementation session.

## Fixed architecture

The supported execution hosts are:

- **DGX Spark** — Ubuntu/Linux, ARM64.
- **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.
- **Fold6 Termux** — Android/Termux, ARM64, using a compatible community Termux Codex distribution.

Each host runs a long-lived `codex app-server` managed by an app-server daemon and exposed locally through a Unix control socket.

```text
DGX Spark / u4090
Official Codex TUI ─┐
                     ├─ daemon-managed codex app-server
Dealer ─ Tailscale SSH proxy ┘       │
                                     ▼
                                Codex threads

Fold6 Termux
Termux Codex TUI ───┐
                     ├─ daemon-managed codex app-server
Dealer ─ loopback SSH proxy ┘         │
                                      ▼
                                 Codex threads

Dealer ─ authenticated Dealer↔Poker link ─ Poker on Rokid glasses
```

Dealer uses the same app-server protocol for all hosts. The route and lifecycle adapter differ:

| Host | Distribution | Dealer route | Availability |
| --- | --- | --- | --- |
| DGX Spark | upstream Linux Codex | Tailscale + SSH + proxy | persistent |
| u4090 | upstream Linux Codex | Tailscale + SSH + proxy | persistent |
| Fold6 Termux | community Termux Codex | loopback SSH + proxy | opportunistic |

Dealer MUST NOT attempt to open Termux's private Unix socket directly across Android application sandboxes.

The local Codex TUI, Dealer, and Poker operate on the same host-qualified Codex thread:

```text
(hostId, threadId)
```

A thread remains on its execution host. Switching from workstation or Termux to Dealer or glasses changes the client surface, not the execution environment.

## Product roles

- **Dealer** is a native Android Codex client and the authority for host connections, distribution-aware lifecycle behavior, phone UI, Poker projection, unread state, and wearable input routing.
- **Poker** is the Rokid HUD and lightweight input surface. It reads projected Codex activity and sends reviewed text or explicit semantic actions through Dealer.
- **Codex app-server** is authoritative for threads, turns, items, approvals, command execution, and persisted Codex history.
- **Termux** has two distinct roles:
  - it may be a first-class phone-local Codex host;
  - it remains the full shell/editor/recovery surface for local or remote terminal work.
- **Dealer is not a terminal emulator.**

## Continuity model

Poker–Dealer preserves:

- the same host-qualified Codex thread across host-local TUI, Dealer, and Poker;
- streamed agent output and structured command/file-change activity;
- approvals, steering, interruption, and reconnection;
- a recent local projection for phone and glasses UX.

It does not migrate a live thread among DGX Spark, u4090, and Fold6 Termux. Cross-host continuation requires repository synchronization and an explicit new-thread handoff.

Multiple clients may observe one thread. Poker–Dealer follows a **one active human-control surface per thread** rule to avoid conflicting input or duplicate approval decisions.

## Compatibility policy

- Dealer talks directly to daemon lifecycle commands through SSH.
- Dealer reaches each daemon's Unix socket using `codex app-server proxy` over the SSH stream.
- Production behavior uses the stable app-server API surface by default.
- The wire adapter ignores unknown fields and notifications, preserves unknown payloads for diagnostics, and safely rejects unknown server-initiated requests.
- Hosts may run different Codex versions and distributions as long as each satisfies Dealer's tested stable API subset.
- The daemon is experimental by deliberate product decision; daemon churn is isolated in a small lifecycle adapter.
- Termux support is capability-based. Dealer must not infer daemon/proxy compatibility solely from a package version.
- Upstream Linux and community Termux update mechanisms are handled separately.

## Fold6 Termux host

The Termux host uses:

```text
Dealer Android app
    ↓ loopback SSH
Termux sshd
    ↓ codex app-server proxy
Termux-private Unix control socket
    ↓
daemon-managed Codex app-server
```

Dealer should distinguish at least these recoverable conditions:

- Termux application/process unavailable;
- loopback SSH unavailable;
- app-server daemon stopped;
- proxy or Unix-socket connection failed;
- Android suspended/killed the mobile host;
- installed Termux Codex build lacks a required capability.

The phone-local host is useful for developing and debugging Poker/Dealer and other Android or Rokid work, but it is not expected to have workstation-level always-on reliability.

## Repository state

The repository uses Codex host/thread terminology throughout. The old Rust tmux bridge and scratch planning tree were removed. The previous SSH-supervised WebRTC/tmux proposal is closed as superseded.

The next implementation slice remains DGX Spark first: connect Dealer to one daemon through SSH + `codex app-server proxy`, initialize the app-server connection, list threads, resume one thread, and stream one turn end to end. Add u4090 second and Fold6 Termux third through the same adapter.

## Build and test

Requirements:

- JDK 21.
- Android SDK platform 35.

Run local gates:

```sh
./tooling/check.sh
```

Equivalent command:

```sh
./gradlew test lint
```

Build the developer APKs:

```sh
./gradlew :apps:dealer:assembleDebug :apps:poker:assembleMockDebug
```

On ARM64 Termux, install the native `aapt2` package. `tooling/check.sh` automatically supplies its path to Gradle when available.

## Start a fresh Codex implementation session

1. Read `AGENTS.md`.
2. Read `CONTEXT.md`.
3. Read `SPEC.md`.
4. Read accepted ADRs under `docs/adr/`.
5. Do not use deleted or historical tmux-bridge designs as implementation guidance.
