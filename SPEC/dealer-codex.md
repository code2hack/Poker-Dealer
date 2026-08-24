# Dealer ↔ Codex Retained Contract

**Status:** Derived retained contract in progress during Legacy extraction

This document is intentionally narrower than the final Dealer product specification. It records the retained Dealer ↔ Codex contract that the successor is extracting from the pinned Legacy implementation and executable tests.

## Scope

Dealer integrates directly with Codex app-server. There is no generic backend adapter.

Retained responsibilities include:

- route-neutral Codex-host connectivity;
- SSH and `codex app-server proxy` lifecycle;
- one initialized app-server session per enabled host;
- thread discovery, read, start, resume, fork and lifecycle actions supported by the retained implementation;
- Send, Steer and Interrupt;
- structured command approval, file approval, and structured user-input requests;
- structured card/projection state;
- thread attachments and work-state projection;
- durable drafts and retained local projection;
- exact request/operation fencing;
- authoritative reconciliation and no blind replay.

Dealer ↔ Poker transport, Poker interaction/UI, CXR implementation, and Dealer UI/UX are outside this retained contract.

## Retained correctness invariants

1. Durable thread identity is `(hostId, threadId)`.
2. App-server connection identity is disposable and must not replace durable identity.
3. A connection is initialized exactly once before normal app-server operations.
4. Unknown optional protocol fields and unknown notifications are tolerated where safe.
5. Unknown or unrenderable server-initiated blocking requests fail closed rather than hanging Codex indefinitely.
6. Structured requests remain structured through parsing, projection, and resolution.
7. `BUSY | ATTENTION_REQUIRED | READY` remains the work-state projection; host availability is separate.
8. Send, Steer, Interrupt, approval responses, and structured answers are target-fenced.
9. Request resolution advances monotonically to `RESOLVED` or `UNKNOWN`.
10. Any action whose acceptance is unknown is reconciled from authoritative app-server state and is not blindly replayed.
11. Reconnect/replacement initializes a new connection and reconciles attachments, thread state, requests, and retained projections.
12. A matching authoritative user-message identity updates the pending local projection rather than creating a duplicate.
13. Durable drafts survive disconnect/restart and clear only when the exact outbound action is accepted.
14. Dealer ↔ Codex must work with no Poker transport connected.

## Evidence source

Initial behavior is derived from:

- donor repository `code2hack/Poker-Dealer-Legacy`;
- donor commit `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`;
- retained `shared/domain` tests;
- retained `shared/protocol/appserver` and `shared/protocol/host` tests;
- retained app-server JSON fixtures;
- retained Dealer persistence/recovery tests;
- narrow successor smoke evidence recorded by the extraction closeout.

The extraction closeout should replace this “in progress” status with a precise tested baseline and note any deliberate deviations.
