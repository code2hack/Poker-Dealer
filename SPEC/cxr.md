# Dealer ↔ Poker CXR Transport

**Status:** Skeleton / deferred qualification contract

The successor intends to replace the Legacy Dealer ↔ Poker NSD/TCP/PAKE/pinned-mTLS production path with a qualified Rokid CXR path. The accepted qualification order is:

```text
first candidate: Dealer → CXR-L CUSTOMAPP → Rokid link stack → Poker
fallback:        Dealer → CXR-M ⇄ CXR-S → Poker
```

The exact production path is intentionally unselected until real-device qualification. `SPEC/decisions/cxr-mobile-path.md` is authoritative for this ordering.

## 1. Extraction-milestone boundary

Legacy extraction does **not** implement production CXR.

The extraction milestone may introduce only:

- a project-owned `PokerTransport` boundary;
- semantic projection/action messages above that boundary;
- no-op, fake, or loopback transport implementations;
- connection-state placeholders;
- tests proving Dealer Codex behavior is independent from Poker connectivity and concrete CXR SDK types.

## 2. Dealer UI boundary

Poker is optional. Dealer setup and normal Chat operation must complete without Poker.

Before CXR qualification, Dealer UI may show only:

- Poker not connected/skipped;
- transport unqualified;
- attachment intent where already supported;
- entry to a deferred Poker setup component.

Dealer MUST NOT invent or restore:

- Legacy NSD discovery;
- manual IP enrollment;
- PAKE codes;
- pinned Poker mTLS;
- a CXR pairing flow not proven by the selected SDK.

`SPEC/dealer-ui.md` governs this presentation.

## 3. Official runtime projection

CXR transports project-owned semantic messages.

Dealer must project official Codex:

- `ThreadStatus`;
- `activeFlags`;
- exact synchronization/revision identity required by the Poker protocol.

CXR must not reintroduce the Legacy `READY/BUSY/ATTENTION_REQUIRED` projection.

Connection/readiness state above CXR remains separate from Codex thread runtime.

## 4. Poker-audio transfer for Dealer-local ASR

Poker may record audio, but Dealer performs authoritative recognition locally under `SPEC/asr.md`.

A qualified CXR path must support Poker → Dealer audio transfer with:

- recording/session ID;
- deterministic start/end/cancel;
- encoding and sample metadata;
- ordered chunks or a complete payload;
- duplicate/loss behavior;
- bounded buffering and backpressure;
- transfer progress;
- cancellation;
- retry identity;
- process-death/link-loss recovery;
- privacy/security;
- battery/thermal acceptance.

Raw Poker audio MUST NOT flow directly to Codex app-server in MVP.

ASR and Codex-core packages must depend only on project-owned audio/session abstractions, not concrete CXR SDK types.

## 5. Deferred qualification decisions

A later CXR milestone must qualify and specify:

- exact supported CXR-L and, if needed, CXR-M/CXR-S SDK/API versions;
- connection and readiness lifecycle;
- discovery/bootstrap behavior supplied by the Rokid stack;
- application authentication/security model;
- reconnect and process-recreation behavior;
- ordering, framing, size limits, and backpressure;
- binary transfer behavior;
- Poker-audio transfer behavior;
- failure semantics and state resynchronization;
- exact Rokid hardware/firmware qualification;
- CXR-L `CUSTOMAPP` integration tests and diagnostics first;
- CXR-M/CXR-S integration tests and diagnostics only if the fallback path is qualified.

## 6. Hard separation

CXR SDK concrete types must stay below the Poker transport implementation boundary.

Retained Dealer ↔ Codex packages must not import CXR-L, CXR-M, CXR-S, or other Rokid SDK classes.

Dealer ASR must not import concrete CXR types.

CXR is unrelated to Dealer ↔ Codex host connectivity.

## 7. Readiness and correctness

A successful low-level CXR connection does not imply:

- Poker app running;
- Poker render-ready;
- projection synchronized;
- mutation-ready;
- audio-capture-ready.

The final protocol requires explicit readiness and synchronization above transport.

CXR qualification must preserve:

- connection epoch/generation;
- snapshot/delta/revision semantics;
- operation identity and idempotency;
- accepted/rejected/unknown outcomes;
- authoritative reconciliation;
- no blind replay.

## 8. Qualification acceptance

No CXR path becomes production authority until real-device evidence proves:

- normal third-party Dealer and Poker usability;
- deterministic authorization and target-app establishment;
- bidirectional asynchronous semantic traffic;
- ordering/duplicate/loss properties;
- sustained output streaming;
- image/binary payload behavior;
- Poker-audio transfer;
- background/process/reboot recovery;
- security sufficient for application isolation;
- failure semantics compatible with Poker-Dealer correctness;
- acceptable latency, battery, and thermal behavior.
