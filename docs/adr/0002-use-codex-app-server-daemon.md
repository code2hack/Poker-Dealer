# ADR 0002: Use long-lived Codex app-server daemons as the only backend

- **Status:** Accepted
- **Date:** 2026-07-27
- **Supersedes:** the tmux-pane bridge backend and any planned custom host conversation bridge

## Context

Poker–Dealer originally treated selected tmux panes as conversations. That design required terminal capture, ANSI handling, screen-diff heuristics, message-boundary inference, pane identity management, and tmux input injection. It also made the terminal pane rather than the Codex conversation the durable product identity.

The user's actual primary workflow is Codex work on two Ubuntu/Linux workstations:

- DGX Spark, ARM64;
- u4090, x86-64 with RTX 4090.

The user wants to begin work in the official Codex TUI, continue the same Codex work from the Fold6, and later use Rokid glasses as a third client surface.

Codex app-server already exposes structured thread, turn, item, streaming, interruption, steering, approval, and history APIs. The current Unix Codex TUI can connect to a local daemon-backed app-server, allowing local TUI and Dealer to operate on the same long-lived runtime.

## Decision

1. Codex app-server is the only product backend.
2. Each supported workstation runs a long-lived `codex app-server` managed directly by the experimental `codex app-server daemon`.
3. The daemon's Unix control socket is the local app-server endpoint.
4. Dealer connects over Tailscale + SSH and executes `codex app-server proxy` to reach the socket.
5. Dealer implements WebSocket framing and the app-server JSON-RPC lifecycle over that SSH stream.
6. The official Codex TUI should use the same daemon-backed app-server locally.
7. The durable product identity is `(hostId, threadId)`.
8. Threads remain bound to their execution host; switching client surfaces does not migrate execution.
9. Multiple clients may observe one thread, while Poker–Dealer presents one intended active human-control surface at a time.
10. Dealer is a native Codex client, not a terminal emulator.
11. Termux + Mosh + tmux remains a separate terminal, editor, process-continuity, recovery, and administration path.
12. Poker connects only to Dealer and receives a structured projection of Codex activity.
13. Production Dealer uses the stable app-server API surface by default and a tolerant wire adapter. Direct use of the experimental daemon lifecycle is accepted.
14. Linux ARM64 and Linux x86-64 are required. Native Windows is not required.

## Consequences

### Positive

- Removes terminal scraping and message inference.
- Preserves real Codex thread identity across workstation, phone, and glasses.
- Makes command executions, file changes, approvals, errors, and turn state structured.
- Allows the local TUI and Dealer to share one host runtime.
- Removes the custom Rust bridge and its security/protocol burden.
- Makes Poker input semantic: start, steer, interrupt, approve, deny, and switch thread.
- Keeps full terminal power available through the existing Termux workflow without duplicating a terminal in Dealer.

### Negative

- Poker–Dealer no longer supports arbitrary tmux sessions or non-Codex agents.
- The product directly depends on an experimental daemon lifecycle whose commands may change.
- Dealer must implement SSH, WebSocket-over-proxy, app-server JSON-RPC, tolerant parsing, and reconnect reconciliation.
- Threads remain host-bound; cross-host handoff requires a separate future design.
- Simultaneous human control from local TUI and Dealer requires explicit UX coordination.

### Mitigations

- Isolate daemon command parsing in a small lifecycle adapter.
- Use the stable app-server surface by default.
- Ignore unknown optional fields and notifications; preserve raw payloads for diagnostics.
- Safely reject unknown server-initiated requests.
- Treat every client connection as disposable and rebuild state after reconnect.
- Never blindly resend an uncertain `turn/start`.
- Maintain Termux + Mosh + tmux as the recovery path.

## Rejected alternatives

### Keep tmux as the product backend

Rejected because it preserves terminal processes but forces Poker–Dealer to infer structured Codex semantics from terminal output.

### Run a disposable app-server per SSH connection

Rejected because the product requires a long-lived runtime shared with the local TUI and resilient to phone disconnection.

### Add a custom host bridge around app-server

Rejected for the first version because the daemon and proxy already provide lifecycle and local-socket access. A wrapper may be introduced only if real daemon churn justifies it.

### Make Dealer a terminal emulator

Rejected because it duplicates Termux, weakens the structured Codex UX, and reintroduces terminal complexity.

### Use WebRTC between Dealer and workstations

Rejected because SSH over Tailscale plus the official app-server proxy is simpler and sufficient for the current Linux-only environment.
