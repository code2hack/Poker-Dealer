# Dealer ↔ Codex Retained Contract

**Status:** Accepted retained baseline after Legacy extraction
**Baseline date:** 2026-08-25
**Donor:** `code2hack/Poker-Dealer-Legacy@0a12901f58abf7cf7324bd92e876f4423f1cbeaa`

This document is intentionally narrower than the final Dealer product specification. It records the Dealer ↔ Codex contract retained and tested by the successor extraction. Dealer integrates directly with Codex app-server; there is no generic backend/runtime adapter.

## 1. Scope

Retained responsibilities are:

- route-neutral Codex-host connectivity;
- SSH and `codex app-server proxy` lifecycle;
- one initialized app-server session per enabled host;
- generic configured Codex-host metadata rather than hard-coded personal machines;
- thread discovery, read, start, resume, fork and supported lifecycle actions;
- Send (`turn/start`), Steer (`turn/steer`) and Interrupt;
- structured command approval, file approval, and structured user-input requests;
- structured card/projection state;
- thread attachments and the `BUSY | ATTENTION_REQUIRED | READY` work-state projection;
- durable drafts and retained local projection;
- exact request/operation/generation fencing;
- authoritative reconciliation and no blind replay;
- optional embedded userspace-tailnet routing as Dealer ↔ Codex connectivity.

Dealer ↔ Poker transport, Poker interaction/UI, concrete CXR implementation, final Dealer UI/UX, ASR, Morse and final Photo behavior are outside this retained contract.

## 2. Authority

Codex app-server is authoritative for thread identity/lifecycle, turns/items, accepted input, retained/live history, command/file state, and server-request resolution when reported.

Dealer is authoritative for configured host connection intent, local attachments/control claims, durable drafts, retained projection/cache, local uncertainty state, and the semantic state projected toward Poker.

A durable thread locator is `(hostId, threadId)`. SSH/WebSocket/app-server connections are disposable and never replace that identity.

## 3. Retained correctness invariants

1. A replacement app-server connection is initialized before normal requests.
2. Unknown optional fields and unknown notifications are tolerated where safe.
3. Unknown or unrenderable blocking server requests fail closed rather than leaving Codex waiting indefinitely.
4. Structured command/file/user-input requests remain structured through parsing, Dealer state, review and response.
5. `BUSY | ATTENTION_REQUIRED | READY` is the only known work-state projection; unavailable/unknown authoritative evidence stays unknown rather than inventing another work state.
6. Host availability is orthogonal to thread work state.
7. Send, Steer, Interrupt and server-request responses are fenced to exact targets and current authority.
8. Steer remains `turn/steer`; it is not emulated as Interrupt + Send.
9. Interrupt is bound to the exact currently confirmed active turn.
10. Request resolution is monotonic: `PENDING → RESPONDING → RESOLVED`, or `UNKNOWN` when acceptance cannot be established.
11. Any mutation whose acceptance is unknown is reconciled from authoritative app-server state and is never blindly replayed.
12. Reconnect/replacement increments the local app-server generation, invalidates generation-bound wire requests, rereads/rejoins authoritative thread state, and reconciles retained local state.
13. A matching authoritative `clientUserMessageId` updates/clears the existing pending local operation instead of fabricating duplicate user input.
14. Durable drafts survive disconnect/restart and clear only after acceptance of the exact outbound action is known.
15. Pending Send/Steer/Interrupt locks survive process/storage recreation and remain uncertain until reconciliation.
16. Dealer ↔ Codex functions with no Poker connection, no CXR session and no Legacy Poker transport.

## 4. Host connectivity contract

A configured host supplies project-owned `CodexHost` metadata and route endpoints. Route order remains a property of the host; route providers report configured/unavailable/unsupported/disabled capability and failures remain route-labelled.

The retained route set may include:

- trusted/direct LAN SSH;
- Dealer's embedded userspace tailnet SSH route;
- external tailnet-address SSH fallback;
- Android-local loopback SSH for a compatible local distribution.

SSH host-key verification remains mandatory. Private SSH key material and `known_hosts` data are encrypted at rest by the Android profile store.

Embedded tailnet is an optional Dealer-owned route. It does not use Android `VpnService`, does not become a Dealer ↔ Poker transport, and remains behind `HostTcpDialer`.

## 5. Persistence/recovery contract

Dealer persists:

- attached host-qualified threads;
- ordered composer drafts and next-turn reasoning selection;
- uncertain pending Send/Steer/Interrupt operations;
- cached retained thread projection;
- structured command/file/user-input request uncertainty;
- configured host connection intent/profile credentials.

Legacy Poker binding-map persistence is intentionally not part of successor recovery.

Derived cached projection may be discarded when corrupt. Stored uncertainty must be preserved/fail closed rather than silently discarded.

## 6. Poker independence boundary

Dealer core publishes only project-owned semantic state through `PokerProjectionPort`. The optional `PokerTransport` boundary sits below it. The default port is a no-op, and the Dealer core must remain fully operable with that default.

No retained Dealer ↔ Codex package imports:

- CXR-L types;
- CXR-M types;
- CXR-S types;
- Rokid SDK concrete classes;
- Legacy Poker NSD/TCP/PAKE/pinned-mTLS transport.

Concrete CXR remains deferred to `SPEC/cxr.md` and `SPEC/decisions/cxr-mobile-path.md`; CXR-L `CUSTOMAPP` is the first future qualification candidate.

## 7. Extraction evidence

The tested successor baseline includes:

- all retained shared domain tests;
- retained app-server and host tests;
- exactly 113/113 byte-identical app-server JSON fixtures;
- Dealer mutation tests for accepted/rejected/unknown behavior;
- a replacement-session integration test proving a connection loss after `turn/start` does not replay the send and authoritative reread reconciles the exact `clientUserMessageId`;
- structured command-approval and structured user-input integration tests through the successor Dealer coordinator;
- transport-neutral Poker projection tests;
- native embedded-tailnet Go tests/build and debug-APK packaging verification;
- debug APK and Android-test APK compilation;
- Android lint.

The full verification ledger and per-path provenance are in `docs/provenance/legacy-extraction.md`.

## 8. Fresh real-device evidence

Fresh successor validation was executed on 2026-08-25 on a real Android phone against a live Codex app-server `0.149.1` host, with Poker/CXR absent.

The accepted live evidence proves:

- the diagnostics Activity and foreground Dealer service start on Android and recover after process recreation;
- the encrypted generic Codex-host profile and strict SSH host-key verification operate on-device;
- embedded-tailnet host routing can establish the retained Dealer ↔ Codex path without Android `VpnService` ownership;
- app-server initialize succeeds and the live host session reaches `CONNECTED`;
- disposable thread creation, attach/control, authoritative read, and `READY` projection operate;
- reviewed Send is accepted, streams agent output, and clears only the exact accepted draft/action;
- deliberately replacing the accepted Send's app-server session leads to normal replacement initialization and authoritative reread;
- the submitted `clientUserMessageId` appears exactly once after reconnect, the existing local user card reconciles to `DELIVERED`, and `turn/start` is not blindly replayed;
- stale Steer and Interrupt targets are rejected while the exact active-turn targets are accepted;
- persisted attachments/drafts/reasoning effort and uncertain mutation locks survive Android store recreation without recreating Dealer control claims.

Validation also exposed and fixed an Android ED25519 interoperability defect in the retained JSch path: JSch `2.28.5` now has its matching optional Bouncy Castle `1.85` provider available, and host-key negotiation is restricted to already-pinned key families while `StrictHostKeyChecking=yes` remains mandatory.

Fresh structured-request coverage remains deliberately bounded: a safe `pwd` prompt did not elicit a command approval on app-server `0.149.1`; structured user-input remains qualified only for `0.146.0`; and no artificial file mutation was introduced merely to provoke file approval. The retained automated structured-request suites remain the executable acceptance evidence for those cases.

The complete commands, device fingerprints, live mutation evidence and limitations are recorded in `docs/provenance/legacy-extraction.md`.
