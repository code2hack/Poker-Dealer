# ADR 0002: Use long-lived Codex app-server daemons as the only backend

- **Status:** Accepted
- **Date:** 2026-07-27
- **Last amended:** 2026-08-06 for expanded M4 Poker control and focus rules
- **Amended by:** ADR 0003, which adds Fold6 Termux as a supported host
- **Amended by:** ADR 0004, which embeds a userspace Tailscale node in Dealer
- **Supersedes:** the tmux-pane bridge backend and any planned custom host conversation bridge

## Context

Poker–Dealer originally treated selected tmux panes as conversations. That design required terminal capture, ANSI handling, screen-diff heuristics, message-boundary inference, pane identity management, and tmux input injection. It also made the terminal pane rather than the Codex conversation the durable product identity.

The user's actual workflow is Codex work on three Unix-like execution hosts:

- DGX Spark, Ubuntu/Linux ARM64;
- u4090, Ubuntu/Linux x86-64 with RTX 4090;
- Fold6 Termux, Android/Termux ARM64 using a compatible community Codex distribution.

The user wants to begin work in a host-local Codex TUI, continue the same Codex work from Dealer on the Fold6, and use Rokid glasses as another client surface.

Codex app-server exposes structured thread, turn, item, streaming, interruption, steering, approval, and history APIs. A daemon-backed app-server allows the host-local TUI and Dealer to operate on the same long-lived runtime.

## Decision

1. Codex app-server is the only product backend.
2. Every supported host runs a long-lived `codex app-server` managed by an app-server daemon.
3. The daemon's Unix control socket is the local app-server endpoint.
4. Dealer reaches the socket through SSH and `codex app-server proxy`.
5. DGX Spark and u4090 support prioritized SSH routes: trusted LAN, Dealer's embedded userspace tailnet, and optional external-Tailscale fallback. ADR 0004 defines this routing decision.
6. Fold6 Termux uses loopback SSH because Dealer cannot directly access Termux-private files or Unix sockets across Android application sandboxes.
7. Dealer implements WebSocket framing and the app-server JSON-RPC lifecycle over the SSH proxy stream.
8. The host-local Codex TUI should use the same daemon-backed app-server.
9. The durable product identity is `(hostId, threadId)`.
10. Threads remain bound to their execution host; switching client surfaces does not migrate execution.
11. Multiple clients may observe one thread, while Poker–Dealer presents one intended active human-control surface at a time.
12. Dealer is a native Codex client, not a terminal emulator.
13. Termux may be both a first-class phone-local Codex host and a separate full terminal/editor/recovery surface.
14. Poker connects only to Dealer and receives a structured projection of Codex activity.
15. Production Dealer uses the stable app-server API surface by default and a tolerant wire adapter. Direct use of the experimental daemon lifecycle is accepted.
16. Linux ARM64, Linux x86-64, and Android/Termux ARM64 are supported. Native Windows is not required.
17. Distribution-specific lifecycle and update behavior is isolated behind a small adapter.
18. SSH and app-server layers consume a route-neutral TCP/duplex-stream abstraction so connectivity changes do not rewrite Codex protocol code.
19. TCP, SSH, daemon command, proxy, WebSocket upgrade, app-server request, turn-notification inactivity, and reconnect inspection phases have separate bounds. Cancellation actively closes blocking resources.
20. An uncertain `turn/start` is never replayed. One client-identified user card advances from local pending to accepted and then delivered; it becomes unknown only when acceptance was not established.
21. One-shot Dealer runs end in completed, recovered, cancelled, or error state. Service-owned run state survives Activity recreation and service rebinding within the application process.
22. Dealer may keep Spark, u4090, and Fold6 Termux connected concurrently, using one initialized app-server connection per enabled host and a durable, user-controlled connection intent.
23. Thread attachment is manual, host-qualified, and separate from host connectivity and soft control. Only Dealer attaches, detaches, or initiates thread lifecycle actions; Poker receives the synchronized projection and may perform only the specified semantic input and presentation actions through Dealer.
24. Soft-control claims are per `(hostId, threadId)`. Dealer may control several different threads concurrently, but process death, explicit host disconnection, or uncorrelated external activity revokes the affected claim.
25. Discovered and attached threads expose `BUSY`, `ATTENTION_REQUIRED`, or `READY` work state independently of host availability. Poker orders these groups horizontally but work-state changes and foreground wake never select or move focus; only the user's canonical navigation changes piles.
26. M3 resolves only command-execution approvals, file-change approvals, and structured user-input questions, and resolves them only in Dealer. Unknown or incompletely renderable requests fail closed.
27. Dealer retains attached-thread projections, drafts, and pending-action state in Dealer-private storage for process and same-phone reboot recovery. Host state remains authoritative; device loss and removal of Dealer-private storage are outside this guarantee.
28. Safe Archive/Delete cascade preflight uses the narrowly accepted unstable `thread/list.ancestorThreadId` filter only on a host/version where fixtures and live qualification prove it. Without that proof, Dealer disables those actions rather than infer descendants from an incomplete visible list.

## Consequences

### Positive

- Removes terminal scraping and message inference.
- Preserves real Codex thread identity across host-local TUI, phone, and glasses.
- Makes command executions, file changes, approvals, errors, and turn state structured.
- Allows Dealer to use one app-server client abstraction across remote workstations and phone-local Termux.
- Removes the custom Rust bridge and its security/protocol burden.
- Makes Poker input semantic: start, steer, interrupt, approve, deny, and switch thread.
- Keeps full terminal power available through Termux without duplicating a terminal in Dealer.
- Allows workstation reachability without requiring Dealer to own Android's VPN-service slot.

### Negative

- Poker–Dealer no longer supports arbitrary tmux sessions or non-Codex agents.
- The product directly depends on experimental daemon lifecycle behavior.
- Dealer must implement SSH, WebSocket-over-proxy, app-server JSON-RPC, tolerant parsing, reconnect reconciliation, and route selection.
- Embedded tailnet support adds a Go/native Android build boundary.
- The community Termux distribution may lag or diverge from upstream Codex.
- Android may suspend or stop the Termux host, so it cannot promise workstation-level availability.
- Threads remain host-bound; cross-host handoff requires a separate future design.
- Simultaneous human control from local TUI and Dealer requires explicit UX coordination.

### Mitigations

- Isolate daemon command parsing and update behavior by distribution.
- Isolate LAN, embedded-tailnet, external-tailnet, and loopback routing behind one dialer abstraction.
- Use the stable app-server surface by default.
- Ignore unknown optional fields and notifications; preserve unknown non-secret payloads for diagnostics, but redact or discard raw `config/read` and other credential-bearing payloads.
- Safely reject unknown server-initiated requests.
- Treat every client connection as disposable and rebuild state after reconnect.
- Never blindly resend an uncertain `turn/start`.
- Capability-test the installed Termux build instead of trusting version strings alone.
- Model the Termux host as opportunistic and provide explicit recovery states.
- Keep Termux terminal workflows as the recovery path.

## Rejected alternatives

### Keep tmux as the product backend

Rejected because it preserves terminal processes but forces Poker–Dealer to infer structured Codex semantics from terminal output.

### Run a disposable app-server per SSH connection

Rejected because the product requires a long-lived runtime shared with the host-local TUI and resilient to Dealer disconnection.

### Add a custom host bridge around app-server

Rejected for the first version because the daemon and proxy already provide lifecycle and local-socket access. A wrapper may be introduced only if real daemon churn justifies it.

### Open the Termux Unix socket directly from Dealer

Rejected because Dealer and Termux are separate Android applications with separate private sandboxes. Loopback SSH plus the proxy preserves one host-access abstraction.

### Make Dealer a terminal emulator

Rejected because it duplicates Termux, weakens the structured Codex UX, and reintroduces terminal complexity.

### Use WebRTC between Dealer and hosts

Rejected because SSH plus the official app-server proxy is simpler and sufficient for the current environment.
