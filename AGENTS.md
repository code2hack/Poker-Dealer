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

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `code2hack/Poker-Dealer`. See `docs/agents/issue-tracker.md`.

### Triage labels

The default triage label vocabulary is used. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo with root `CONTEXT.md` and root ADRs. See `docs/agents/domain.md`.

## Current product

Poker–Dealer is a private mobile and wearable Codex client.

- **Dealer** runs on the Samsung Fold6.
- **Poker** runs on Rokid RG-glasses.
- Codex executes on three supported hosts:
  - **DGX Spark** — Ubuntu/Linux, ARM64.
  - **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.
  - **Fold6 Termux** — Android/Termux, ARM64, using a compatible community Termux Codex distribution.
- Every host runs a long-lived `codex app-server` managed by an app-server daemon and exposed through a Unix control socket.
- Dealer reaches workstation SSH through a prioritized route set:
  1. trusted LAN;
  2. Dealer's embedded userspace `tsnet` node;
  3. optional external Tailscale-app fallback.
- Dealer reaches Fold6 Termux through loopback SSH + `codex app-server proxy`; it MUST NOT open Termux-private files or Unix sockets directly across Android app sandboxes.
- The host-local Codex TUI should use the same daemon-backed app-server.
- A durable conversation identity is `(hostId, threadId)`.

## Current host availability

u4090 MUST NOT host source worktrees, builds, host-side tests, artifact production, CI, or fallback
implementation. It is explicitly enabled only as an SSH-accessed ADB/control relay for the attached
Rokid glasses and Fold6, an audio-alarm host, and temporary device-transfer staging under
`~/tmp/Poker-Dealer-ADB`. Current implementation, builds, and host-side tests may use DGX Spark and
Fold6 Termux only. ADB remains diagnostic tooling, never a product transport.

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
12. **Dealer↔Poker trust uses the explicit physical Pair/Replace + six-digit PAKE ceremony; while that five-minute window is open, Android NSD/mDNS discovers Poker's Wi-Fi endpoint automatically, and all authenticated product data uses the validated Dealer-initiated ordinary Wi-Fi/hotspot path.**
13. **Bluetooth/RFCOMM/BLE/GATT and proprietary Rokid CXR are not Dealer↔Poker pairing/bootstrap or product transports.**
14. **Dealer embeds a userspace Tailscale node for workstation access without claiming Android's `VpnService` slot.**
15. **The embedded tailnet routes only Dealer-owned connections; Dealer is not a system VPN or exit-node client.**

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
- requiring the standalone Tailscale Android app as Dealer's only remote route;
- using Android `VpnService` for Dealer's embedded tailnet;
- routing all Fold6 traffic through Dealer's embedded tailnet;
- CXR-M, CXR-S, CXR-L, ADB tunnels, or proprietary Rokid data channels;
- Bluetooth/RFCOMM/BLE/GATT as a Dealer↔Poker trust/bootstrap path;
- user-visible or user-entered Poker IP addresses or ports during pairing.

Git history may contain these designs. History is evidence only, not current guidance.

## App-server integration rules

- Use daemon lifecycle commands over SSH and parse their machine-readable output.
- Use `codex app-server proxy` for the long-lived app-server stream.
- Remember that the proxied stream carries a WebSocket HTTP upgrade and WebSocket frames, not JSONL.
- Initialize once per app-server connection before any other request.
- Prefer stable app-server methods and fields by default.
- Keep parsing tolerant: ignore unknown optional fields, preserve unknown non-secret payloads for diagnostics, and do not crash on new notifications or item types. Redact or discard raw `config/read` and other credential-bearing payloads.
- Safely reject unknown server-initiated requests so Codex cannot wait forever for an answer Dealer cannot render.
- Treat disconnects and daemon restarts as normal. Reconnect, reinitialize, inspect thread state, and never blindly replay `turn/start`.
- Do not require hosts to run identical Codex versions or distributions.
- Keep daemon lifecycle and update behavior behind a distribution-aware adapter.

## Connectivity rules

- SSH and app-server code MUST depend on a route-neutral TCP/duplex-stream abstraction.
- A workstation host has an ordered route list rather than one permanent route.
- Route priority is trusted LAN, embedded `tsnet`, then optional external Tailscale.
- A failed route MAY fall through to the next configured route after host-key and endpoint checks.
- Route providers MUST report configured, temporarily unavailable, unsupported, or disabled capability; attempt only configured routes and retain route-labelled failures.
- Bound TCP, SSH, proxy, WebSocket, app-server request, turn inactivity, and reconnect phases separately. Cancellation MUST actively close blocking resources.
- Embedded `tsnet` is packaged behind a narrow Go/Android module boundary. Go networking types MUST NOT leak into Compose or app-server protocol code.
- The embedded node MUST NOT request `VpnService`, install a default route, act as an exit node, or carry unrelated app traffic.
- Tailnet identity state belongs in Dealer-private storage. Never store auth keys or node secrets in plaintext Room/DataStore fields or logs.
- SSH host-key verification remains mandatory even over the tailnet.
- Karing or other third-party-VPN coexistence, direct UDP, DERP fallback, foreground-service behavior, and battery behavior require real Fold6 testing.

## Host-specific rules

### DGX Spark native Android builds

- DGX Spark is ARM64. Build the embedded-tailnet Android/arm64 artifact there with `native/embedded-tailnet/build.sh`; it runs the repository-pinned Linux/amd64 Go, `gomobile`, and `gobind` toolchain through Docker/QEMU.
- Treat `native/embedded-tailnet/build.sh` and `native/embedded-tailnet/versions.env` as the executable source of truth for this build; do not duplicate mutable toolchain versions in this file.
- u4090 MUST NOT be used for builds or artifact production.

### DGX Spark and u4090

- Distribution: upstream Linux Codex.
- Route set: trusted LAN SSH, embedded-tsnet SSH, optional external-Tailscale SSH.
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

- The host-local TUI, Dealer, and Poker are surfaces for the same host-qualified thread.
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

M1 was proven on u4090 through trusted-LAN SSH on 2026-07-27. See `docs/evidence/u4090-m1-2026-07-27.md`. M1T completed on the real Fold6 on 2026-07-28 with direct and genuine DERP-relayed turns, third-party-VPN coexistence, lifecycle and cancellation recovery, route fallback, and unplugged battery observations; see `docs/evidence/fold6-m1t-routing-2026-07-28.md` and `docs/evidence/fold6-m1t-lifecycle-2026-07-28.md`.

The M2 workstation slice proves Spark and u4090 through the same daemon-backed app-server stack, including explicit soft control and LAN-to-embedded-tailnet fallback; see `docs/evidence/workstations-m2-2026-07-28.md`. Both hosts used Codex `0.145.0`. The user accepted progression to M3 on 2026-07-28 without making the separate mixed-version proof an M3 prerequisite. Broad mixed-version compatibility remains unproven and MUST NOT be claimed.

M2T now proves the tested Fold6 community build through loopback SSH, the distribution-specific daemon lifecycle, the shared app-server stack, its daemon-backed local TUI, and recovery after proxy, `sshd`, daemon, and Termux-process interruption without replay; see `docs/evidence/fold6-m2t-turn-2026-07-28.md` and `docs/evidence/fold6-m2t-recovery-2026-07-28.md`.

M3 is complete. The current slice is the expanded M4: code-only Dealer↔Poker PAKE pairing with temporary NSD/mDNS endpoint discovery, secure Wi-Fi synchronization, horizontal `BUSY | ATTENTION_REQUIRED | READY` card piles without an attached-thread list, canonical operations and HID bindings, reviewed composer/request input, the fixed action wheel, Photo, Morse, and Dealer-local ASR/model management. The former M5 milestone is retired. Follow the published dependency DAG for issues #33–#64; `SPEC.md` section 17 defines scope order, not a global serialization barrier.

Do not access Termux-private files or Unix sockets directly, route Termux through embedded tsnet, assume upstream Linux installation/update behavior, add an attached-thread list or configurable wheel, revive M5, use u4090 outside the narrow ADB/audio exception above, or add a terminal, generic slash-command parser, per-thread provider proxy, broad experimental app-server APIs, proprietary Rokid transport, or cross-host migration during M4.

## M4 implementation execution

These rules govern issues #33–#64 until #64 is merged and closed.

- The active implementation session owns both product-management and engineering work: live issue/DAG inspection, isolated worktree management, code/docs/tests, validation, commits, pushes, integration, issue closure, cleanup, and user coordination.
- Do **not** spawn separate Engineer/Codex worker sessions or delegate issue implementation unless the user explicitly asks for delegation again.
- Keep each issue in its existing isolated `.worktrees/issue-<N>` worktree and `m4/issue-<N>` branch when available; fetch and verify `origin/main` before integration and never force-push.
- Follow the mandatory read order, then read the complete live issue/comments/blockers before changing an issue. Implement only its scope and preserve unrelated work.
- Run proportionate focused checks plus the required final integration gate, then stage only owned paths with the required Codex provenance trailers.
- Ask the user only for unavoidable physical, permission, authentication, approval, or hardware actions; continue all agent-resolvable work directly in the active session.
- The user's approval authorizes issue-branch commits/pushes, `main` integration/pushes, and issue closure for #33–#64 without repeated confirmation. It does not authorize unrelated or destructive changes.

### u4090 device relay

- Reach u4090 only through SSH and create or reuse one remote tmux session named `adb` for all ADB
  operations.
- Run ADB there with `/opt/android-sdk/platform-tools/adb`.
- Store temporary remote files only under `~/tmp/Poker-Dealer-ADB` and remove issue-specific files
  after use.
- Building an artifact on Spark and temporarily copying it there for ADB installation is allowed;
  producing or testing the artifact as a u4090 host build is not.

### User-intervention alarm

- Only the active implementation session may start the alarm, and only when an active issue genuinely requires
  user action.
- In the remote u4090 `adb` tmux session, run exactly one repeating alarm using the designated Super
  Mario BGM file under `~/Music`. Report the blocked issue and exact manual action once.
- Continue the alarm until the user's next message to the Product Manager Agent, then stop it
  immediately. Do not alert for agent-resolvable failures.
- Manual actions include device unlock or ADB trust, Android permission/settings dialogs, physical
  pairing or bonding, glasses/remote gestures, camera or microphone exercises, human approval
  decisions, and interactive `sudo` password entry. Repository work, builds, tests, merges, and issue
  administration remain agent-owned.

## Completion discipline

- Keep each change narrow and testable.
- Update `SPEC.md` and the relevant ADR in the same commit when changing architecture.
- Add compatibility fixtures for every app-server method introduced.
- Pin native/Go dependencies and make Android ARM64 packaging reproducible.
- Avoid claims of real-hardware, embedded-tailnet, Termux-daemon, VPN-coexistence, or multi-version compatibility without recorded evidence.
- Leave the repository in a state where another fresh Codex session can determine the active design solely from the files on the default branch.

## Privileged commands

When a required command needs interactive `sudo` authentication, automatically create a uniquely
named temporary detached tmux session with a short name, run the exact command there, report the
session name and attach command, and end the current turn so the thread becomes `READY`. Resume only
after the user says password entry is complete, then inspect the result and remove only that temporary
session when it is no longer needed. Never store, retrieve, capture, log, or relay the password, and
do not silently replace the privileged command with a user-level fallback.
