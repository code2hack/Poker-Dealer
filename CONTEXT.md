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
  - It connects to DGX Spark and u4090 through Tailscale + SSH + `codex app-server proxy`.
  - It connects to Fold6 Termux through loopback SSH + `codex app-server proxy`.
- **Poker:** Rokid HUD and lightweight input endpoint connected only to Dealer.
- **Termux terminal workflow:** full shell/editor/recovery surface. Termux may simultaneously be a first-class Codex host, but Dealer still does not become a terminal emulator.

Dealer cannot directly open Termux's private Unix socket because Dealer and Termux are separate Android applications. The common abstraction is SSH plus the official proxy stream on all hosts.

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
- Dealer owns configured hosts, connection routes, distribution-specific lifecycle behavior, mobile presentation, recent projection/cache, unread state, control-surface intent, and Poker synchronization.
- Poker owns only its viewport, composition state, and explicitly persisted pending input.

## Control rule

Several clients may observe one thread. Only one human-control surface should actively submit turns, steer, interrupt, or resolve an approval at a time.

## Host distinctions

| Host | Kind | Architecture | Dealer route | Availability |
| --- | --- | --- | --- | --- |
| DGX Spark | Linux workstation | ARM64 | Tailscale SSH | persistent |
| u4090 | Linux workstation | x86-64 | Tailscale SSH | persistent |
| Fold6 Termux | Android/Termux | ARM64 | loopback SSH | opportunistic |

Termux installation and update behavior is distribution-specific. Dealer must capability-test daemon status, Unix-socket binding, proxy operation, initialization, thread APIs, and reconnect rather than assuming complete parity from a version string.

## Terminology

- **Host:** DGX Spark, u4090, or Fold6 Termux.
- **Host kind:** Linux workstation or Android/Termux.
- **Codex distribution:** upstream Linux or community Termux.
- **Connection route:** Tailscale SSH or loopback SSH.
- **Thread:** durable Codex conversation/work context stored on one host.
- **Turn:** one user request and Codex execution/response cycle.
- **Item:** structured user input or Codex output within a turn, such as an agent message, command execution, file change, plan, or approval request.
- **Client connection:** disposable initialized JSON-RPC connection to app-server.
- **Dealer projection:** Dealer's local, UI-oriented representation of thread/turn/item events.
- **Card:** a Poker-readable presentation unit derived from the Dealer projection. Cards are not the authoritative Codex record.
- **Control surface:** the client currently intended to accept human actions for a thread, usually local TUI, Dealer, or Poker-through-Dealer.

## Explicitly abandoned terminology

Do not use `PaneLocator`, tmux pane attachment, capture profiles, screen diffs, terminal-output inference, or the Rust bridge as current product concepts.
