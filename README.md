# Poker–Dealer

Poker–Dealer is a private mobile and wearable client for Codex threads running on the user's Linux workstations.

`SPEC.md` is the normative product and architecture contract. `AGENTS.md` is the mandatory entry point for every fresh implementation session.

## Fixed architecture

The supported execution hosts are:

- **DGX Spark** — Ubuntu/Linux, ARM64.
- **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.

Each host runs a long-lived `codex app-server` managed directly by the experimental `codex app-server daemon` and exposed locally through its Unix control socket.

```text
Official Codex TUI on host ─┐
                            ├─ long-lived codex app-server daemon
Dealer on Fold6 ─ SSH proxy ┘              │
                                           │ Codex thread/turn/item API
                                           ▼
                                      Codex threads

Dealer ─ authenticated Dealer↔Poker link ─ Poker on Rokid glasses
```

The official Codex TUI, Dealer, and Poker all operate on the same host-qualified Codex thread:

```text
(hostId, threadId)
```

A thread remains on its execution host. Switching from workstation to phone or glasses changes the client surface, not the execution machine.

## Product roles

- **Dealer** is a native Android Codex client and the authority for phone UI, host connections, Poker projection, unread state, and wearable input routing.
- **Poker** is the Rokid HUD and lightweight input surface. It reads projected Codex activity and sends reviewed text or explicit semantic actions through Dealer.
- **Codex app-server** is authoritative for threads, turns, items, approvals, command execution, and persisted Codex history.
- **Termux + Mosh + tmux** remains a separate power-user and recovery tool for full terminal work. Dealer is not a terminal emulator.

## Continuity model

Poker–Dealer preserves:

- the same Codex thread across local TUI, Dealer, and Poker;
- streamed agent output and structured command/file-change activity;
- approvals, steering, interruption, and reconnection;
- a recent local projection for phone and glasses UX.

It does not migrate a live thread between DGX Spark and u4090, and it does not replace tmux for arbitrary shell, editor, REPL, or process continuity.

Multiple clients may observe one thread. Poker–Dealer follows a **one active human-control surface per thread** rule to avoid conflicting input or duplicate approval decisions.

## Compatibility policy

- Dealer talks directly to the daemon lifecycle commands over SSH.
- Dealer reaches the daemon's Unix socket using `codex app-server proxy` over the SSH stream.
- Production behavior uses the stable app-server API surface by default.
- The wire adapter ignores unknown fields and notifications, preserves unknown payloads for diagnostics, and safely rejects unknown server-initiated requests.
- DGX Spark and u4090 may run different Codex versions as long as each satisfies Dealer's tested stable API subset.
- The daemon is experimental by deliberate product decision; daemon churn is isolated in a small lifecycle adapter.

## Repository state

The repository has been reset from the abandoned tmux-pane backend design. The Rust tmux bridge and its scratch planning tree are removed. The Android mock and shared model now use Codex host/thread terminology.

The next implementation slice is defined in `SPEC.md`: connect Dealer to one daemon through SSH + `codex app-server proxy`, initialize the app-server connection, list threads, resume one thread, and stream one turn end to end in Dealer before adding Poker synchronization.

## Build and test

Requirements:

- JDK 21.
- Android SDK platform 35.

Run local gates:

```sh
./tooling/check.sh
```

Equivalent command:

```sh
./gradlew test lint
```

Build the developer APKs:

```sh
./gradlew :apps:dealer:assembleDebug :apps:poker:assembleMockDebug
```

On ARM64 Termux, install the native `aapt2` package. `tooling/check.sh` automatically supplies its path to Gradle when available.

## Start a fresh Codex implementation session

1. Read `AGENTS.md`.
2. Read `CONTEXT.md`.
3. Read `SPEC.md`.
4. Read accepted ADRs under `docs/adr/`.
5. Do not use deleted or historical tmux-bridge designs as implementation guidance.
