# Poker–Dealer current context

Poker–Dealer provides mobile and wearable client surfaces for Codex work that continues to execute on one of the user's configured hosts.

## Supported Codex hosts

- **DGX Spark** — Ubuntu/Linux, ARM64, upstream Codex distribution.
- **u4090** — Ubuntu/Linux, x86-64 with RTX 4090, upstream Codex distribution.
- **Fold6 Termux** — Android/Termux, ARM64, compatible community Termux Codex distribution.

Each host owns its repositories, filesystem state, tools, model/provider configuration, `CODEX_HOME`, and Codex threads. Each host runs a long-lived daemon-managed `codex app-server` on a Unix control socket.

The workstation hosts are expected to be persistent. Fold6 Termux is an opportunistic mobile host because Android may suspend or stop Termux processes.

## Product surfaces

- **Host-local Codex TUI:** full interface connected to that host's daemon-backed app-server.
- **Dealer:** native Android client on the Fold6.
  - For DGX Spark and u4090 it selects among trusted LAN SSH, embedded-tsnet SSH, and optional external-Tailscale SSH.
  - For Fold6 Termux it uses loopback SSH + `codex app-server proxy`.
- **Poker:** Rokid HUD and lightweight input endpoint connected only to Dealer.
- **Termux terminal workflow:** full shell/editor/recovery surface. Termux may simultaneously be a first-class Codex host, but Dealer still does not become a terminal emulator.

Dealer cannot directly open Termux's private Unix socket because Dealer and Termux are separate Android applications. The common host abstraction remains SSH plus the official proxy stream.

## Embedded tailnet

Dealer embeds a userspace Tailscale node based on `tsnet` for remote workstation connections.

```text
Karing / third-party VPN
└── owns Android VpnService

Dealer
└── embedded userspace tsnet node
    └── Dealer-owned SSH connections only
        ├── DGX Spark
        └── u4090
```

The embedded node:

- does not request Android `VpnService`;
- does not route all Fold6 traffic;
- does not act as an exit-node client;
- has its own tailnet identity;
- stores state only in Dealer-private storage;
- remains subject to real-device compatibility testing with the user's current third-party VPN configuration.

The standalone Tailscale Android app is an optional fallback, not a runtime requirement for Dealer.

## Workstation route priority

Dealer treats connection routing separately from SSH and app-server protocol logic.

```text
1. SSH_LAN
2. SSH_EMBEDDED_TSNET
3. SSH_EXTERNAL_TAILSCALE
```

Fold6 Termux uses only:

```text
SSH_LOOPBACK
```

M1 used trusted-LAN SSH to u4090 and introduced the route-neutral stream boundary required by embedded `tsnet`. The live proof completed on 2026-07-27; see `docs/evidence/u4090-m1-2026-07-27.md`. Before M1T, issue #5 hardened that boundary with capability-aware route filtering, phase-specific timeouts, active cancellation, truthful one-shot states, and one-card user-message delivery reconciliation; see `docs/evidence/u4090-m1-hardening-2026-07-27.md`. Fold6 direct routing and Karing coexistence were proven on 2026-07-28; see `docs/evidence/fold6-m1t-routing-2026-07-28.md`. Activity/service recreation, process restart, idle/doze recovery, fallback, tunnel interruption, UI and notification cancellation, a real DERP-relayed turn, and unplugged idle/direct/relayed battery observations are recorded in `docs/evidence/fold6-m1t-lifecycle-2026-07-28.md`. Those checks complete the M1T hardware evidence while retaining the documented single-device and short-duration limits.

For Fold6 development diagnostics, keep wireless ADB enabled and prefer it over USB ADB. USB remains a bootstrap/recovery fallback. ADB is diagnostic tooling only and is not a Dealer product transport.

The same LAN app-server slice now passes against daemon-backed local-TUI threads on both Spark and u4090, and Dealer requires an explicit host-qualified soft control claim before sending; see `docs/evidence/workstations-m2-2026-07-28.md`. A Fold6 run retained a VPN-routed LAN failure, selected embedded tsnet, completed a Spark turn, and reconciled one delivered user card. Both hosts ran Codex `0.145.0`, so live mixed-version behavior remains partial M2 evidence rather than M2 completion.

## Core identity

A durable Poker–Dealer conversation reference is:

```text
(hostId, threadId)
```

`threadId` alone is insufficient because all three hosts have separate Codex homes and thread stores.

## Continuity

Switching surfaces means attaching another client to the same host-qualified Codex thread:

```text
host-local TUI → Dealer on phone → Poker on glasses
```

Examples:

```text
spark / thr_123
u4090 / thr_456
fold6-termux / thr_789
```

The execution host does not change. Cross-host continuation requires repository synchronization and an explicit handoff into a different thread locator.

## Authority

- Codex app-server owns threads, turns, items, approvals, command/file-change state, and persisted Codex history.
- Dealer owns configured hosts, route priority, embedded-tailnet lifecycle, distribution-specific daemon behavior, mobile presentation, recent projection/cache, unread state, control-surface intent, and Poker synchronization.
- Poker owns only its viewport, composition state, and explicitly persisted pending input.

## Control rule

Several clients may observe one thread. Only one human-control surface should actively submit turns, steer, interrupt, or resolve an approval at a time.

## Host distinctions

| Host | Kind | Architecture | Dealer routes | Availability |
| --- | --- | --- | --- | --- |
| DGX Spark | Linux workstation | ARM64 | LAN → embedded tsnet → external Tailscale | persistent |
| u4090 | Linux workstation | x86-64 | LAN → embedded tsnet → external Tailscale | persistent |
| Fold6 Termux | Android/Termux | ARM64 | loopback SSH | opportunistic |

Termux installation and update behavior is distribution-specific. Dealer must capability-test daemon status, Unix-socket binding, proxy operation, initialization, thread APIs, and reconnect rather than assuming complete parity from a version string.

## Terminology

- **Host:** DGX Spark, u4090, or Fold6 Termux.
- **Host kind:** Linux workstation or Android/Termux.
- **Codex distribution:** upstream Linux or community Termux.
- **Connection route:** LAN SSH, embedded-tsnet SSH, external-Tailscale SSH, or loopback SSH.
- **Embedded tailnet:** Dealer-private userspace Tailscale node used only for Dealer sockets.
- **Thread:** durable Codex conversation/work context stored on one host.
- **Turn:** one user request and Codex execution/response cycle.
- **Item:** structured user input or Codex output within a turn, such as an agent message, command execution, file change, plan, or approval request.
- **Client connection:** disposable initialized JSON-RPC connection to app-server.
- **Dealer projection:** Dealer's local, UI-oriented representation of thread/turn/item events.
- **Card:** a Poker-readable presentation unit derived from the Dealer projection. Cards are not the authoritative Codex record.
- **Control surface:** the client currently intended to accept human actions for a thread, usually local TUI, Dealer, or Poker-through-Dealer.

## Explicitly abandoned terminology

Do not use `PaneLocator`, tmux pane attachment, capture profiles, screen diffs, terminal-output inference, or the Rust bridge as current product concepts.
