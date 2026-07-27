# Poker–Dealer implementation instructions

This file is the mandatory entry point for every new Codex session working in this repository.

## Read order

Before changing code:

1. Read this file completely.
2. Read `CONTEXT.md`.
3. Read `SPEC.md`.
4. Read all accepted ADRs under `docs/adr/`.
5. Inspect the current code and tests only after understanding the normative architecture.

If code disagrees with `SPEC.md`, the specification wins until code and specification are deliberately changed in the same commit.

## Current product

Poker–Dealer is a private mobile and wearable Codex client.

- **Dealer** runs on the Samsung Fold6.
- **Poker** runs on Rokid RG-glasses.
- Codex executes on three supported hosts:
  - **DGX Spark** — Ubuntu/Linux, ARM64.
  - **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.
  - **Fold6 Termux** — Android/Termux, ARM64, using a compatible community Termux Codex distribution.
- Every host runs a long-lived `codex app-server` managed by an app-server daemon and exposed through a Unix control socket.
- Dealer reaches DGX Spark and u4090 through Tailscale + SSH + `codex app-server proxy`.
- Dealer reaches Fold6 Termux through loopback SSH + `codex app-server proxy`; it MUST NOT open Termux-private files or Unix sockets directly across Android app sandboxes.
- The local Codex TUI on each host should use the same daemon-backed app-server.
- A durable conversation identity is `(hostId, threadId)`.

## Non-negotiable decisions

Do not reopen these decisions unless the user explicitly requests an architecture change:

1. **Codex app-server is the only product backend.**
2. **The old tmux-pane backend is abandoned and removed.**
3. **No custom host conversation bridge is part of the architecture.**
4. **Direct dependency on the experimental app-server daemon is accepted.**
5. **The supported hosts are DGX Spark, u4090, and Fold6 Termux.**
6. **Native Windows support is out of scope.**
7. **Dealer is a native Codex client, not a terminal emulator.**
8. **Termux may act both as a first-class phone-local Codex host and as a separate terminal/recovery surface. These are distinct roles.**
9. **A thread stays on its original execution host. Cross-host migration is not the same thread.**
10. **Multiple clients may observe a thread, but one human-control surface should actively write or decide approvals at a time.**
11. **Poker receives a structured projection from Dealer; it does not connect directly to any app-server.**
12. **Dealer↔Poker uses the validated ordinary Android hotspot path, with Dealer initiating and Poker listening.**
13. **No proprietary Rokid CXR transport is a production dependency.**

## Stale-design ban

Do not implement or restore any of the following:

- tmux pane discovery as the product backend;
- terminal screen scraping, screen diffing, OSC-based turn inference, or ANSI parsing for conversation extraction;
- tmux `send-keys` or paste-buffer injection as the primary input path;
- the deleted Rust `poker-dealer-bridge`;
- WebRTC as the host transport unless the user explicitly changes the decision;
- per-SSH-session disposable app-server processes as the main architecture;
- a terminal emulator inside Dealer;
- direct Poker-to-host control;
- direct Dealer access to Termux-private Unix sockets or files;
- CXR-M, CXR-S, CXR-L, ADB tunnels, or proprietary Rokid data channels.

Git history may contain these designs. History is evidence only, not current guidance.

## App-server integration rules

- Use daemon lifecycle commands over SSH and parse their machine-readable output.
- Use `codex app-server proxy` for the long-lived app-server stream.
- Remember that the proxied stream carries a WebSocket HTTP upgrade and WebSocket frames, not JSONL.
- Initialize once per app-server connection before any other request.
- Prefer stable app-server methods and fields by default.
- Keep parsing tolerant: ignore unknown optional fields, preserve unknown payloads for diagnostics, and do not crash on new notifications or item types.
- Safely reject unknown server-initiated requests so Codex cannot wait forever for an answer Dealer cannot render.
- Treat disconnects and daemon restarts as normal. Reconnect, reinitialize, inspect thread state, and never blindly replay `turn/start`.
- Do not require hosts to run identical Codex versions or distributions.
- Keep daemon lifecycle and update behavior behind a distribution-aware adapter.

## Host-specific rules

### DGX Spark and u4090

- Distribution: upstream Linux Codex.
- Route: Tailscale + SSH.
- Availability class: persistent workstation host.
- Dealer may use daemon bootstrap/update flows supported by the installed upstream distribution.

### Fold6 Termux

- Distribution: compatible community Termux Codex build, not an official OpenAI Android release.
- Route: SSH to a Termux `sshd` listener on loopback, then `codex app-server proxy`.
- Availability class: opportunistic mobile host; Android may suspend or stop Termux.
- Dealer must expose recoverable states such as Termux unavailable, local SSH unavailable, daemon stopped, and Android-suspended.
- Dealer must not assume the upstream standalone installer or daemon updater works unchanged. Termux installation and updates are distribution-specific.
- A Termux build is supported only after live capability checks confirm daemon lifecycle, Unix-socket binding, proxy operation, initialization, thread APIs, and reconnect behavior.

## Client continuity rules

- The local TUI, Dealer, and Poker are surfaces for the same host-qualified thread.
- Dealer should list, read, resume, start, fork, archive, steer, interrupt, and respond to supported approvals through app-server APIs.
- Dealer must distinguish observer state from active human control.
- Poker should support reading, thread switching, reviewed Morse/ASR text, steering, interruption, and only approvals that can be displayed completely and safely.
- Escalate complex or risky approval review to Dealer.
- Escalate full shell/editor work to Termux rather than adding terminal behavior to Dealer.
- A Fold6 Termux thread remains on Fold6 Termux. Continuing it on Spark or u4090 requires an explicit new-thread handoff after repository synchronization.

## Source-of-truth hierarchy

1. `SPEC.md` — normative product and implementation contract.
2. Accepted ADRs in `docs/adr/` — durable architecture decisions.
3. `CONTEXT.md` — current terminology and concise project state.
4. `README.md` — orientation and build instructions.
5. Source code and tests.
6. Git history and superseded branches — historical evidence only.

## Immediate next slice

Unless a newer committed plan says otherwise, implement one narrow vertical slice on DGX Spark first:

1. Configure the Spark host in Dealer.
2. Connect with SSH over Tailscale.
3. Query daemon status/version and ensure it is running.
4. Launch `codex app-server proxy` through SSH.
5. Complete the WebSocket and app-server initialization handshakes.
6. Call `thread/list`.
7. Resume one thread.
8. Render its existing turns/items in Dealer.
9. Send one `turn/start` with an idempotent client message identifier when supported.
10. Stream the agent message to completion.
11. Reconnect and prove no duplicate user turn is created.

Do not start with Termux-specific lifecycle work. Add u4090 after Spark, then add Fold6 Termux through the same app-server adapter with its route/distribution differences.

## Completion discipline

- Keep each change narrow and testable.
- Update `SPEC.md` and the relevant ADR in the same commit when changing architecture.
- Add compatibility fixtures for every app-server method introduced.
- Avoid claims of real-hardware, Termux-daemon, or multi-version compatibility without recorded evidence.
- Leave the repository in a state where another fresh Codex session can determine the active design solely from the files on the default branch.
