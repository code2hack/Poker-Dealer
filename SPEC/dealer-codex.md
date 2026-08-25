# Dealer ↔ Codex Retained Contract

**Status:** Accepted retained baseline after Legacy extraction, reconciled with the current official Codex runtime model  
**Baseline date:** 2026-08-25  
**Donor:** `code2hack/Poker-Dealer-Legacy@0a12901f58abf7cf7324bd92e876f4423f1cbeaa`  
**Current protocol evidence:** `openai/codex@6525b95dae2082ac9fee672b14c2cffdef172bb8`

This document records the Dealer ↔ Codex contract retained and tested by the successor extraction together with normative corrections and additions required by the current Codex app-server protocol. Dealer integrates directly with Codex app-server; there is no generic backend/runtime adapter.

The extraction evidence in Sections 11–12 covers the retained Legacy-derived implementation. Newly specified Project/runtime/UI protocol surfaces may require later implementation work and are not claimed as already present merely because they are normative here.

## 1. Scope

Dealer ↔ Codex responsibilities are:

- route-neutral Codex-host connectivity;
- SSH and `codex app-server proxy` lifecycle;
- one initialized app-server session per enabled host;
- generic configured Codex-host metadata rather than hard-coded personal machines;
- Project discovery and Project/thread assignment where the connected app-server supports the experimental Project APIs;
- thread discovery, read, start, resume, fork, archive, rename, and supported lifecycle actions;
- official `ThreadStatus` and status-change notifications;
- Send (`turn/start`), Steer (`turn/steer`), and Interrupt;
- structured command approval, file approval, permission escalation, MCP elicitation, and structured user-input requests;
- structured item/projection state;
- durable drafts and retained local projection;
- exact request/operation/generation fencing;
- authoritative reconciliation and no blind replay;
- optional embedded userspace-tailnet routing as Dealer ↔ Codex connectivity.

Dealer ↔ Poker transport, Poker interaction/UI, concrete CXR implementation, final Poker UI, and detailed ASR model qualification are outside this retained contract. Dealer UI behavior is governed by `SPEC/dealer-ui.md`; Dealer-local ASR is governed by `SPEC/asr.md`.

## 2. Protocol evidence pin

Normative protocol interpretations in this document are audited against:

`openai/codex@6525b95dae2082ac9fee672b14c2cffdef172bb8`

The evidence ledger is `docs/protocol/codex-app-server-6525b95d.md`.

A future Codex upgrade must review generated schemas and source against this pin before changing Dealer behavior.

## 3. Authority and durable identity

Codex app-server is authoritative for:

- official Project identity and Project/thread assignment when supported;
- thread identity/lifecycle;
- official `ThreadStatus`;
- turns/items and exact active-turn identity when reported;
- accepted input;
- retained/live history;
- command/file state;
- effective thread settings;
- server-request resolution when reported.

Dealer is authoritative for:

- configured host connection intent;
- local control claims;
- Dealer-local Chats membership;
- Poker attachment intent;
- durable drafts;
- retained projection/cache and freshness metadata;
- local uncertainty state;
- the semantic state projected toward Poker.

A durable thread locator is `(hostId, threadId)`. SSH, proxy, WebSocket, app-server connections, and app-server generations are disposable and never replace that identity.

A Dealer Workspace is an official Codex Project. A cwd or directory is not Project identity.

## 4. Official runtime model and orthogonal state

For thread runtime, Dealer and Poker use only:

- `notLoaded`
- `idle`
- `active { activeFlags }`
- `systemError`

Official active flags are:

- `waitingOnApproval`
- `waitingOnUserInput`

The extracted Legacy `BUSY | ATTENTION_REQUIRED | READY` projection is not the successor runtime model. Dealer must not create `READY`, `BUSY`, `RUNNING`, `ATTENTION_REQUIRED`, or `UNKNOWN` as substitute thread states.

Dealer tracks these separate axes without encoding them into `ThreadStatus`:

1. exact active turn and whether it accepts Steer;
2. Codex-host/app-server connection authority;
3. app-server generation;
4. outbound operation acceptance state;
5. pending structured requests;
6. Dealer-local Chats membership;
7. Poker attachment/CXR state;
8. transient UI and ASR state.

When authoritative connectivity is lost, Dealer retains the last official status only as stale cached data, marks its freshness, disables unsafe actions, and reconciles. It does not mutate the cached status into an invented thread state.

## 5. Correctness invariants

1. A replacement app-server connection is initialized before normal requests.
2. Unknown optional fields and unknown notifications are tolerated where safe.
3. Unknown or unrenderable blocking server requests fail closed rather than leaving Codex waiting indefinitely or fabricating a response.
4. Structured command/file/permission/MCP/user-input requests remain structured through parsing, Dealer state, review, and response.
5. Official `ThreadStatus` and `activeFlags` are preserved losslessly in Dealer and in the Poker-facing semantic projection.
6. Connection/reconciliation uncertainty is separate from `ThreadStatus`.
7. Host availability, Chats membership, and Poker attachment are orthogonal to thread runtime.
8. Send, Steer, Interrupt, settings updates, and server-request responses are fenced to exact targets and current authority.
9. Steer remains `turn/steer`; it is not emulated as Interrupt + Send.
10. Steer uses the exact `expectedTurnId` and is unavailable when the active turn kind or blocking-request state does not permit it.
11. Interrupt is bound to the exact currently confirmed active turn.
12. An accepted interrupt request does not complete the local operation; Dealer waits for authoritative `turn/completed` or reconciliation.
13. Request resolution is monotonic: `PENDING → RESPONDING → RESOLVED`, or `UNKNOWN` when acceptance cannot be established.
14. Any mutation whose acceptance is unknown is reconciled from authoritative app-server state and is never blindly replayed.
15. Reconnect/replacement increments the local app-server generation, invalidates generation-bound wire requests, rereads/rejoins authoritative Project/thread state, and reconciles retained local state.
16. A matching authoritative `clientUserMessageId` updates/clears the existing pending local operation instead of fabricating duplicate user input.
17. Durable drafts survive disconnect/restart and clear only after acceptance of the exact outbound action is known.
18. Pending Send/Steer/Interrupt/settings/request locks survive process/storage recreation and remain uncertain until reconciliation.
19. Dealer ↔ Codex functions with no Poker connection, no CXR session, and no Legacy Poker transport.
20. Dealer never redefines Workspace as cwd when Project APIs are unavailable.

## 6. Host connectivity contract

A configured host supplies project-owned `CodexHost` metadata and route endpoints. Route order remains a property of the host; route providers report configured/unavailable/unsupported/disabled capability and failures remain route-labelled.

The retained route set may include:

- trusted/direct LAN SSH;
- Dealer's embedded userspace tailnet SSH route;
- external tailnet-address SSH fallback;
- Android-local loopback SSH for a compatible local distribution.

SSH host-key verification remains mandatory. Private SSH key material and `known_hosts` data are encrypted at rest by the Android profile store.

Embedded tailnet is an optional Dealer-owned route. It does not use Android `VpnService`, does not become a Dealer ↔ Poker transport, and remains behind `HostTcpDialer`.

## 7. Thread lifecycle contract

- `thread/list` discovers stored threads and their official status.
- `thread/loaded/list` reports currently loaded thread IDs.
- `thread/read` reads stored thread state without loading/resuming it.
- `thread/resume` loads or rejoins a stored thread and establishes the live subscription required for mutation.
- Opening a `notLoaded` thread may render read-only history first, but Dealer must resume before enabling mutation.
- `thread/unsubscribe` only releases this connection's subscription. It is unrelated to Dealer-local `Remove from Chats` and does not force immediate unload.
- `thread/name/set`, `thread/archive`, and `thread/fork` retain their official semantics.
- `thread/status/changed` updates the official runtime axis and never updates Chats membership or Poker attachment.

Project methods and Project-related fields are experimental at the pinned protocol commit. Dealer enables them only when supported and otherwise presents Workspaces as unavailable without a cwd-based fallback.

## 8. Settings contract

Dealer MVP inherits the host's effective Codex configuration. It does not implement named Dealer profiles, Thread Presets, ordinary Personality controls, or an ordinary Developer Instructions editor.

When supported, Chat settings use `thread/settings/update` for persistent next-turn settings:

- model;
- reasoning effort;
- service tier;
- permission profile;
- approval policy;
- approval reviewer.

Dealer displays only host-reported values. A visible selection is committed only after server acceptance. Rejection leaves the last authoritative value visible. Unknown acceptance is reconciled and never blindly replayed.

Permission profile, approval policy, and approval reviewer remain distinct protocol concepts even when one UI sheet groups them.

## 9. Persistence and recovery contract

Dealer persists:

- Dealer-local Chats membership keyed by `(hostId, threadId)`;
- Poker attachment intent separately from Chats membership;
- ordered composer drafts and staged image assets;
- next-turn model/reasoning/service-tier selections and any pending update uncertainty;
- uncertain pending Send/Steer/Interrupt operations;
- cached official `ThreadStatus`, exact active-turn evidence, source generation, and freshness metadata;
- cached retained thread projection;
- structured command/file/permission/MCP/user-input request uncertainty;
- configured host connection intent/profile credentials.

Legacy Poker binding-map persistence is intentionally not part of successor recovery.

Derived cached projection may be discarded when corrupt. Stored uncertainty must be preserved/fail closed rather than silently discarded.

A stale cached status may support passive rendering but never enables a mutation that requires fresh authority.

## 10. Poker independence boundary

Dealer core publishes project-owned semantic state through `PokerProjectionPort`. The optional `PokerTransport` boundary sits below it. The default port is a no-op, and the Dealer core must remain fully operable with that default.

The Poker-facing state preserves official `ThreadStatus` and active flags; it does not send a Legacy work-state projection.

No retained Dealer ↔ Codex package imports:

- CXR-L types;
- CXR-M types;
- CXR-S types;
- Rokid SDK concrete classes;
- Legacy Poker NSD/TCP/PAKE/pinned-mTLS transport.

Concrete CXR remains deferred to `SPEC/cxr.md` and `SPEC/decisions/cxr-mobile-path.md`; CXR-L `CUSTOMAPP` is the first future qualification candidate.

## 11. Extraction evidence

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

## 12. Fresh real-device evidence and historical terminology

Fresh successor validation was executed on 2026-08-25 on a real Android phone against a live Codex app-server `0.149.1` host, with Poker/CXR absent.

The accepted live evidence proves:

- the diagnostics Activity and foreground Dealer service start on Android and recover after process recreation;
- the encrypted generic Codex-host profile and strict SSH host-key verification operate on-device;
- embedded-tailnet host routing can establish the retained Dealer ↔ Codex path without Android `VpnService` ownership;
- app-server initialize succeeds and the live host session reaches `CONNECTED`;
- each successful live host connection executes the production `onHostConnected() → refreshThreads(hostId)` discovery/list path;
- disposable thread creation, attach/control, authoritative read, and the then-existing Legacy-derived `READY` projection operated;
- reviewed Send is accepted, streams agent output, and clears only the exact accepted draft/action;
- deliberately replacing the accepted Send's app-server session leads to normal replacement initialization and authoritative reread;
- the submitted `clientUserMessageId` appears exactly once after reconnect, the existing local user card reconciles to `DELIVERED`, and `turn/start` is not blindly replayed;
- stale Steer and Interrupt targets are rejected while the exact active-turn targets are accepted;
- persisted attachments/drafts/reasoning effort and uncertain mutation locks survive Android store recreation without recreating Dealer control claims.

The word `READY` in that historical evidence describes the extracted Legacy-derived projection observed by the test. It is **not** the normative successor runtime model. The successor now uses official `ThreadStatus` only.

Validation also exposed and fixed an Android ED25519 interoperability defect in the retained JSch path: JSch `2.28.5` has its matching optional Bouncy Castle `1.85` provider available, and host-key negotiation is restricted to already-pinned key families while `StrictHostKeyChecking=yes` remains mandatory.

Fresh structured-request coverage remains deliberately bounded: a safe `pwd` prompt did not elicit a command approval on app-server `0.149.1`; structured user-input remains qualified only for `0.146.0`; and no artificial file mutation was introduced merely to provoke file approval. The retained automated structured-request suites remain the executable acceptance evidence for those cases.

The complete commands, device fingerprints, live mutation evidence, and limitations are recorded in `docs/provenance/legacy-extraction.md`.
