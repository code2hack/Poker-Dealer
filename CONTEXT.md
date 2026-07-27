# Poker–Dealer current context

Poker–Dealer provides three client surfaces for Codex work that continues to execute on the user's Linux workstations.

## Supported workstations

- **DGX Spark** — Ubuntu/Linux, ARM64.
- **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.

Each host owns its repositories, filesystem state, tools, model/provider configuration, and Codex threads. Each host runs a long-lived daemon-managed `codex app-server` on its Unix control socket.

## Product surfaces

- **Official Codex TUI:** full workstation interface connected to the local daemon.
- **Dealer:** native Android client on the Fold6. It connects to workstation daemons through Tailscale + SSH + `codex app-server proxy`.
- **Poker:** Rokid HUD and lightweight input endpoint connected only to Dealer.
- **Termux + Mosh + tmux:** separate power-user/recovery path for arbitrary terminal, editor, REPL, Git, process, and administration work.

## Core identity

A durable Poker–Dealer conversation reference is:

```text
(hostId, threadId)
```

`threadId` alone is insufficient because DGX Spark and u4090 have separate Codex homes and thread stores.

## Continuity

Switching surfaces means attaching another client to the same host-qualified Codex thread:

```text
workstation TUI → Dealer on phone → Poker on glasses
```

The execution host does not change. Cross-host continuation requires an explicit handoff or fork into a new thread and is outside the first product version.

## Authority

- Codex app-server owns threads, turns, items, approvals, command/file-change state, and persisted Codex history.
- Dealer owns host connection state, mobile presentation, recent projection/cache, unread state, control-surface intent, and Poker synchronization.
- Poker owns only its viewport, composition state, and explicitly persisted pending input.

## Control rule

Several clients may observe one thread. Only one human-control surface should actively submit turns, steer, interrupt, or resolve an approval at a time.

## Terminology

- **Host:** DGX Spark or u4090.
- **Thread:** durable Codex conversation/work context stored on one host.
- **Turn:** one user request and Codex execution/response cycle.
- **Item:** structured user input or Codex output within a turn, such as an agent message, command execution, file change, plan, or approval request.
- **Client connection:** disposable initialized JSON-RPC connection to app-server.
- **Dealer projection:** Dealer's local, UI-oriented representation of thread/turn/item events.
- **Card:** a Poker-readable presentation unit derived from the Dealer projection. Cards are not the authoritative Codex record.
- **Control surface:** the client currently intended to accept human actions for a thread, usually local TUI, Dealer, or Poker-through-Dealer.

## Explicitly abandoned terminology

Do not use `PaneLocator`, tmux pane attachment, capture profiles, screen diffs, terminal-output inference, or the Rust bridge as current product concepts.
