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

The same LAN app-server slice passes against daemon-backed local-TUI threads on both Spark and u4090, and Dealer requires an explicit host-qualified soft control claim before sending; see `docs/evidence/workstations-m2-2026-07-28.md`. A Fold6 run retained a VPN-routed LAN failure, selected embedded tsnet, completed a Spark turn, and reconciled one delivered user card. Both hosts ran Codex `0.145.0`. The user accepted progression to M3 on 2026-07-28 with the separate live mixed-version proof deferred; broad mixed-version compatibility remains unproven.

M2T qualifies the tested Fold6 community build through loopback SSH, the distribution-specific daemon lifecycle, the shared app-server stack, its daemon-backed local TUI, and bounded recovery after proxy, `sshd`, daemon, and Termux-process interruption without replay; see `docs/evidence/fold6-m2t-turn-2026-07-28.md` and `docs/evidence/fold6-m2t-recovery-2026-07-28.md`.

M3 is now the active slice. It adds long-lived simultaneous host sessions, configured-host thread discovery, manual Dealer attachments and control, Dealer-only lifecycle actions, deterministic `BUSY | ATTENTION_REQUIRED | READY` projection metadata, phone notifications, complete command/file cards, command/file approvals, structured questions, steering/interruption, and monotonic recovery without starting Poker networking.

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
- Dealer owns configured hosts, route priority, embedded-tailnet lifecycle, distribution-specific daemon behavior, mobile presentation, recent projection/cache, unread state, control-surface intent, durable composer drafts and photo assets, and Poker synchronization.
- Poker owns only its process-local viewport, unacknowledged composition/capture state, and the user's direct semantic action before Dealer accepts it. Only its pairing identity persists.

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
- **Host connection intent:** Dealer's durable per-host choice to keep a host connected or disconnected. An enabled intent survives accidental loss and is automatically restored; a disabled intent never reconnects until the user enables it.
- **Embedded tailnet:** Dealer-private userspace Tailscale node used only for Dealer sockets.
- **Thread:** durable Codex conversation/work context stored on one host.
- **Turn:** one user request and Codex execution/response cycle.
- **Item:** structured user input or Codex output within a turn, such as an agent message, command execution, file change, or plan.
- **Server request:** server-initiated blocking request that requires a client response or safe rejection and may reference turn items.
- **Request resolution state:** monotonic request lifecycle `PENDING → RESPONDING → RESOLVED`, or `UNKNOWN` when response acceptance cannot be established.
- **Client connection:** disposable initialized JSON-RPC connection to app-server.
- **Dealer projection:** Dealer's local, UI-oriented representation of thread/turn/item events.
- **Card:** a Poker-readable presentation unit derived from the Dealer projection. Cards are not the authoritative Codex record.
- **Card pile:** Poker's ordered card history for one attached host-qualified thread.
- **Thread work state:** Dealer's projection classifies a discovered or attached host-qualified thread as `BUSY` while its turn progresses, `ATTENTION_REQUIRED` while an active turn is blocked on the user, or `READY` when it can accept a new prompt. Host availability is separate.
- **Busy activity order:** The oldest-to-newest ordering within the Busy pile group. Entering Busy or accepting Send or Steer moves that pile to the group's right edge; streamed output alone does not reorder it.
- **Control surface:** the client currently intended to accept human actions for a thread, usually local TUI, Dealer, or Poker-through-Dealer.
- **Soft-control claim:** Dealer's per-thread, cooperative record that it is the intended human-control surface; it is not an app-server-enforced writer lease.
- **Thread-management action:** A host-scoped action that changes a thread's identity or lifecycle, such as starting, forking, renaming, archiving, restoring, or deleting it. Dealer is the only Poker–Dealer surface that may initiate one; Poker only receives the resulting synchronized state.
- **Thread attachment:** Dealer's managed set of host-qualified threads synchronized to Poker. Only Dealer may attach or detach a thread.
- **Poker pairing:** The one-to-one trust relationship between one Dealer installation and one Poker installation, independent of either installation's current network endpoint. Authenticated reconnection reuses that relationship; trusting a different Dealer requires explicit replacement.
- **Poker synchronization snapshot:** Dealer's authoritative retained projection sent to establish or rebuild Poker's synchronized state before live updates continue.
- **Poker operation:** A source-neutral HUD action emitted by either the Rokid's built-in controls or a paired Bluetooth controller. The canonical operations are `DOWN`, `UP`, `RIGHT`, `LEFT`, `FN`, `TAP`, and `TAPTAP`.
- **Poker action-wheel layout:** Dealer's stable mapping from noncentral relative-head-posture sectors to the available Poker actions. Runtime availability disables a sector without moving the others; the origin dead zone has no action.
- **Poker Primary action:** The action wheel's state-sensitive draft action: Send for a nonempty Ready composer, Steer for a nonempty Busy composer, or semantic Interrupt when the Busy composer is empty.
- **Poker navigation mode:** The HUD mode in which `DOWN` and `UP` scroll within or jump among cards in the focused pile.
- **Poker request panel:** The text-input or decision surface owned by one unresolved server-request card.
- **Poker input mode:** The HUD mode in which focus has moved from a card into that card's request panel or into the thread composer below the newest card.
- **Poker composer input:** The ordinary thread-composer form of Poker input mode. Draft editing, photo capture, and turn submission apply only here, never in a request panel.
- **Draft photo token:** An atomic photo element in an ordered composer draft, rendered on Poker as `📷`. It retains the identity of its underlying unsent image and is distinct from an ordinary typed emoji.
- **Poker word motion:** Unicode-aware movement among user-visible words and atomic photo tokens in composer input. It borrows Vim's `w`, `b`, and `dw` behavior without emulating terminal keys.
- **Poker HUD visibility:** Poker's local visible or hidden presentation state, including its focused and last-viewed pile. Dealer supplies synchronized thread facts but does not command the viewport; hiding the HUD does not detach a thread, unsubscribe Dealer, or disconnect a host.
- **Poker scroll anchor:** Poker's process-local card and reading position within one attached pile. Each pile retains its own anchor and interaction mode so live growth and pile switching do not displace the text being read.
- **Poker unread state:** A per-pile indication that synchronized content exists beyond its scroll anchor. It clears only while the pile is focused at its newest content and never changes focus by itself.

## Explicitly abandoned terminology

Do not use `PaneLocator`, tmux pane attachment, capture profiles, screen diffs, terminal-output inference, or the Rust bridge as current product concepts.
