# Dealer ↔ Poker CXR Transport

**Status:** Skeleton / deferred qualification contract

The successor intends to replace the Legacy Dealer ↔ Poker NSD/TCP/PAKE/pinned-mTLS production path with a qualified Rokid CXR path. The accepted qualification order is:

```text
first candidate: Dealer → CXR-L CUSTOMAPP → Rokid link stack → Poker
fallback:        Dealer → CXR-M ⇄ CXR-S → Poker
```

The exact production path is intentionally unselected until real-device qualification. `SPEC/decisions/cxr-mobile-path.md` is authoritative for this ordering.

## Extraction-milestone boundary

Legacy extraction does **not** implement production CXR.

The extraction milestone may introduce only:

- a project-owned `PokerTransport` boundary;
- semantic projection/action messages above that boundary;
- no-op, fake, or loopback transport implementations;
- connection-state placeholders;
- tests proving Dealer Codex behavior is independent from Poker connectivity and concrete CXR SDK types.

## Deferred decisions

A later CXR milestone must qualify and specify:

- exact supported CXR-L and, if needed, CXR-M/CXR-S SDK/API versions;
- connection and readiness lifecycle;
- discovery/bootstrap behavior supplied by the Rokid stack;
- application authentication/security model;
- reconnect and process-recreation behavior;
- ordering, framing, size limits, and backpressure;
- binary transfer behavior where needed;
- failure semantics and state resynchronization;
- exact Rokid hardware/firmware qualification;
- CXR-L `CUSTOMAPP` integration tests and diagnostics first;
- CXR-M/CXR-S integration tests and diagnostics only if the fallback path is qualified.

## Hard separation

CXR SDK concrete types must stay below the Poker transport implementation boundary. Retained Dealer ↔ Codex packages must not import CXR-L, CXR-M, CXR-S, or other Rokid SDK classes.

CXR is unrelated to Dealer ↔ Codex host connectivity.
