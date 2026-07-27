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
- Codex executes on two Ubuntu/Linux workstations:
  - DGX Spark, ARM64.
  - u4090, x86-64 with RTX 4090.
- Each workstation runs a long-lived `codex app-server` managed directly by `codex app-server daemon` and exposed through its Unix control socket.
- Dealer connects over Tailscale + SSH and runs `codex app-server proxy` to reach that socket.
- The local official Codex TUI must also use the same daemon-backed app-server.
- A durable conversation identity is `(hostId, threadId)`.

## Non-negotiable decisions

Do not reopen these decisions unless the user explicitly requests an architecture change:

1. **Codex app-server is the only product backend.**
2. **The old tmux-pane backend is abandoned and removed.**
3. **No custom host conversation bridge is part of the architecture.**
4. **Direct dependency on the experimental app-server daemon is accepted.**
5. **Unix/Linux only is sufficient. Native Windows support is out of scope.**
6. **Dealer is a native Codex client, not a terminal emulator.**
7. **Termux + Mosh + tmux remains a separate full-terminal and recovery path.**
8. **A thread stays on its original execution host. Cross-host migration is not the same thread.**
9. **Multiple clients may observe a thread, but one human-control surface should actively write or decide approvals at a time.**
10. **Poker receives a structured projection from Dealer; it does not connect directly to workstation app-servers.**
11. **Dealer↔Poker uses the previously validated ordinary Android hotspot path, with Dealer initiating and Poker listening.**
12. **No proprietary Rokid CXR transport is a production dependency.**

## Stale-design ban

Do not implement or restore any of the following:

- tmux pane discovery as the product backend;
- terminal screen scraping, screen diffing, OSC-based turn inference, or ANSI parsing for conversation extraction;
- tmux `send-keys` or paste-buffer injection as the primary input path;
- the deleted Rust `poker-dealer-bridge`;
- WebRTC as the workstation transport unless the user explicitly changes the decision;
- per-SSH-session disposable app-server processes as the main architecture;
- a terminal emulator inside Dealer;
- direct Poker-to-workstation control;
- CXR-M, CXR-S, CXR-L, ADB tunnels, or proprietary Rokid data channels.

Git history may contain these designs. History is evidence only, not current guidance.

## App-server integration rules

- Use direct daemon lifecycle commands over SSH and parse their machine-readable JSON.
- Use `codex app-server proxy` for the long-lived app-server stream.
- Remember that the proxied stream carries a WebSocket HTTP upgrade and WebSocket frames, not JSONL.
- Initialize once per app-server connection before any other request.
- Prefer stable app-server methods and fields by default.
- Keep parsing tolerant: ignore unknown optional fields, preserve unknown payloads for diagnostics, and do not crash on new notifications or item types.
- Safely reject unknown server-initiated requests so Codex cannot wait forever for an answer Dealer cannot render.
- Treat disconnects and daemon restarts as normal. Reconnect, reinitialize, inspect thread state, and never blindly replay `turn/start`.
- Do not require DGX Spark and u4090 to run identical Codex versions.

## Client continuity rules

- The local TUI, Dealer, and Poker are three surfaces for the same host-qualified thread.
- Dealer should list, read, resume, start, fork, archive, steer, interrupt, and respond to supported approvals through app-server APIs.
- Dealer must distinguish observer state from active human control.
- Poker should support reading, thread switching, reviewed Morse/ASR text, steering, interruption, and only approvals that can be displayed completely and safely.
- Escalate complex or risky approval review to Dealer.
- Escalate full shell/editor work to Termux rather than adding terminal behavior to Dealer.

## Source-of-truth hierarchy

1. `SPEC.md` — normative product and implementation contract.
2. Accepted ADRs in `docs/adr/` — durable architecture decisions.
3. `CONTEXT.md` — current terminology and concise project state.
4. `README.md` — orientation and build instructions.
5. Source code and tests.
6. Git history and superseded branches — historical evidence only.

## Immediate next slice

Unless a newer committed plan says otherwise, implement one narrow vertical slice:

1. Configure one Linux host in Dealer.
2. Connect with SSH over Tailscale.
3. Query daemon status/version and ensure it is running.
4. launch `codex app-server proxy` through SSH;
5. complete the WebSocket and app-server initialization handshakes;
6. call `thread/list`;
7. resume one thread;
8. render its existing turns/items in Dealer;
9. send one `turn/start` with an idempotent client message identifier when supported;
10. stream the agent message to completion;
11. reconnect and prove no duplicate user turn is created.

Do not add Poker networking, Morse, ASR, terminal features, cross-host migration, or broad experimental APIs to this first slice.

## Completion discipline

- Keep each change narrow and testable.
- Update `SPEC.md` and the relevant ADR in the same commit when changing architecture.
- Add compatibility fixtures for every app-server method introduced.
- Avoid claims of real-hardware or multi-version compatibility without recorded evidence.
- Leave the repository in a state where another fresh Codex session can determine the active design solely from the files on the default branch.
