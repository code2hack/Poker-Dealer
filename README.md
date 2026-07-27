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
Host-local Codex TUI ─┐
                       ├─ daemon-managed codex app-server
Dealer ─ SSH + proxy ──┘               │
                                        ▼
                                   Codex threads

Fold6 Termux
Termux Codex TUI ─────┐
                       ├─ daemon-managed codex app-server
Dealer ─ loopback SSH ┘               │
          + proxy                      ▼
                                  Codex threads

Dealer ─ authenticated Dealer↔Poker link ─ Poker on Rokid glasses
```

Dealer uses the same SSH, proxy, WebSocket, and app-server protocol stack for all hosts. Connectivity and lifecycle details remain behind adapters.

## Workstation connectivity without Android VPN conflict

Dealer embeds a userspace Tailscale node based on `tsnet`. It is not an Android system VPN and does not request the `VpnService` slot.

```text
Hiddify / Clash
└── Android VpnService

Dealer
└── embedded userspace tsnet
    └── SSH to DGX Spark / u4090
```

Only Dealer-owned connections enter this tailnet. Dealer does not route all device traffic and does not act as an exit-node client.

Workstation route priority is:

```text
1. trusted LAN SSH
2. embedded-tsnet SSH
3. optional external-Tailscale SSH fallback
```

The standalone Tailscale Android application is therefore optional for Dealer. It may remain installed for diagnostics or fallback, but Dealer's target architecture does not require it to stay connected.

The embedded Go component will be packaged behind a narrow Android module boundary. SSH and app-server code consume a route-neutral stream or local-proxy abstraction rather than Go networking objects directly.

## Host matrix

| Host | Distribution | Dealer routes | Availability |
| --- | --- | --- | --- |
| DGX Spark | upstream Linux Codex | LAN → embedded tsnet → external Tailscale | persistent |
| u4090 | upstream Linux Codex | LAN → embedded tsnet → external Tailscale | persistent |
| Fold6 Termux | community Termux Codex | loopback SSH | opportunistic |

Dealer MUST NOT attempt to open Termux's private Unix socket directly across Android application sandboxes.

The local Codex TUI, Dealer, and Poker operate on the same host-qualified Codex thread:

```text
(hostId, threadId)
```

A thread remains on its execution host. Switching from workstation or Termux to Dealer or glasses changes the client surface, not the execution environment.

## Product roles

- **Dealer** is a native Android Codex client and the authority for host connections, route selection, embedded-tailnet lifecycle, distribution-aware daemon behavior, phone UI, Poker projection, unread state, and wearable input routing.
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

## Embedded-tailnet boundaries

- The embedded node has its own tailnet identity, expected to be named similarly to `dealer-fold6`.
- Tailnet policy should grant only required workstation services, initially SSH.
- SSH host-key verification remains mandatory over every route.
- Tailscale node state belongs only in Dealer-private storage.
- Auth keys, state keys, proxy credentials, and SSH private keys must never appear in logs or plaintext Room/DataStore columns.
- Any loopback proxy or tunnel exposed by the Go module must bind only to loopback and be authenticated or otherwise process-confined.
- Hiddify/Clash coexistence, direct UDP, DERP fallback, battery behavior, and foreground-service behavior require real Fold6 acceptance tests.

## Compatibility policy

- Dealer talks directly to daemon lifecycle commands through SSH.
- Dealer reaches each daemon's Unix socket using `codex app-server proxy` over the SSH stream.
- Production behavior uses the stable app-server API surface by default.
- The wire adapter ignores unknown fields and notifications, preserves unknown payloads for diagnostics, and safely rejects unknown server-initiated requests.
- Hosts may run different Codex versions and distributions as long as each satisfies Dealer's tested stable API subset.
- The daemon is experimental by deliberate product decision; daemon churn is isolated in a small lifecycle adapter.
- Termux support is capability-based. Dealer must not infer daemon/proxy compatibility solely from a package version.
- Upstream Linux and community Termux update mechanisms are handled separately.
- The embedded Tailscale Go dependency is pinned and updated through Dealer releases.

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

M1 is complete on u4090 through trusted-LAN SSH. The original proof is in `docs/evidence/u4090-m1-2026-07-27.md`; pre-M1T lifecycle hardening is recorded in `docs/evidence/u4090-m1-hardening-2026-07-27.md`.

Dealer's M1 screen accepts the LAN endpoint, SSH user, thread ID, and turn text. It imports an unencrypted SSH private key and pinned `known_hosts` data through Android's document picker, keeps that material in memory only, and renders history plus live card revisions from the shared M1 stack. An active run is owned by a foreground service so Activity recreation does not cancel an accepted turn. The UI and notification can cancel the run, one-shot completion reports `Completed` or `Recovered`, and the submitted user card shows pending, accepted, delivered, or unknown delivery state.

The LAN provider attempts only its configured LAN route. Shared route selection records unsupported, unavailable, disabled, and attempted-route diagnostics without allowing an unimplemented fallback to hide the actionable LAN error. TCP, SSH, proxy, WebSocket, app-server requests, turn inactivity, and reconnect inspection have separate bounds; there is no whole-turn deadline.

The next implementation sequence is:

1. Add the embedded-tsnet Android spike without rewriting SSH or app-server code.
2. Add the remaining workstation host.
3. Add Fold6 Termux through the same app-server adapter.

## Build and test

Requirements:

- JDK 21.
- Android SDK platform 35.
- Android NDK `23.1.7779620`.
- `curl`, `sha256sum`, and `unzip`.

Run local gates:

```sh
./tooling/check.sh
```

Equivalent command:

```sh
./gradlew test lint
```

The opt-in u4090 live test is `U4090LiveM1Test`. It takes the LAN endpoint, SSH username, private-key path, pinned `known_hosts` path, and an idle thread ID from `POKER_DEALER_LIVE_*` environment variables. Concrete private-network endpoints and credentials are intentionally not stored in this public repository.

Build the developer APKs:

```sh
./gradlew :apps:dealer:assembleDebug :apps:poker:assembleMockDebug
```

On ARM64 Termux, install the native `aapt2` package. `tooling/check.sh` automatically supplies its path to Gradle when available.

Dealer's embedded-tailnet AAR is built automatically before Dealer. Go, `gomobile`, and NDK inputs are
pinned in `native/embedded-tailnet/versions.env`; Tailscale is pinned by `go.mod` and `go.sum`. The build
verifies the Go archive SHA-256 and Go module checksums, then produces only
`jni/arm64-v8a/libgojni.so`. `ANDROID_NDK_HOME` may point at the pinned NDK when it is not installed
below `ANDROID_HOME`.

A clean native and Dealer build is:

```sh
rm -rf native/embedded-tailnet/build
./gradlew clean :apps:dealer:verifyEmbeddedTailnetPackaging
```

The verification task assembles Dealer, checks that the ARM64 Go library reached the APK, and rejects a
merged manifest containing Android `VpnService`.

## Start a fresh Codex implementation session

1. Read `AGENTS.md`.
2. Read `CONTEXT.md`.
3. Read `SPEC.md`.
4. Read accepted ADRs under `docs/adr/`.
5. Do not use deleted or historical tmux-bridge designs as implementation guidance.
