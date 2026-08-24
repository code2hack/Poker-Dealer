# Poker-Dealer Successor Specification

**Status:** Normative successor constitution

Poker-Dealer is a two-client system for controlling persistent Codex sessions while preserving Codex app-server as the execution authority.

## 1. Product roles

- **Dealer** is the Android client. It owns Codex-host connectivity, Codex app-server integration, local projections, durable drafts and retained state, recovery, and the semantic state synchronized toward Poker.
- **Poker** is the Rokid client. It is a lightweight wearable interaction and presentation surface and never connects directly to Codex app-server.
- **Codex app-server** is the only backend supported by the successor.
- **Codex host** means the execution host that owns a Codex installation, `CODEX_HOME`, daemon/app-server lifecycle, repositories, tools, and threads.
- **CXR-L** is the first Dealer-side Rokid transport candidate to qualify, using `CUSTOMAPP`.
- **CXR-M/CXR-S** is the fallback/alternative transport candidate if CXR-L fails qualification.
- The exact production CXR path remains unqualified until real-device evidence exists.

Specific hardware names are qualification/evidence details, not product architecture.

## 2. Backend decision

The successor does **not** implement a generalized agent-runtime adapter. There is no DSH, Pi, Hermes, ACP, or hypothetical universal backend layer.

Dealer integrates Codex app-server directly through project-owned Codex protocol and orchestration code. The validated Legacy Dealer ↔ Codex implementation is the primary extraction donor and should be preserved unless a concrete successor requirement forces change.

## 3. Architecture

```text
Codex host
  └─ codex app-server
        ⇅
      Dealer
        │
        ├─ Codex connectivity / session / projection / recovery
        └─ PokerProjectionPort
              ⇅
          PokerTransport
              ⇅
       future qualified CXR path
       (CXR-L first candidate;
        CXR-M/CXR-S fallback)
              ⇅
             Poker
```

The Dealer Codex core must function with no Poker connection and without any CXR implementation.

## 4. Authority

Codex app-server is authoritative for:

- thread identity and lifecycle;
- turns and items;
- accepted input;
- live and retained Codex history;
- command/file state;
- server-initiated requests and their authoritative resolution when reported.

Dealer is authoritative for:

- configured Codex hosts and connection intent;
- route selection and Codex-host connectivity;
- thread attachments used by Poker-Dealer;
- local retained projection/cache;
- durable drafts and retained local assets;
- local operation state until Codex acceptance is known;
- Poker-facing semantic projection and synchronization state.

Poker is authoritative only for its local presentation/interaction state and a user action until Dealer accepts that action.

## 5. Durable identity and control

A Codex thread locator is host-qualified: `(hostId, threadId)`. A disposable SSH, WebSocket, or app-server connection is never durable thread identity.

Multiple clients may observe a thread. Poker-Dealer must keep observer state distinct from the intended active human-control surface. Send, Steer, Interrupt, and user-initiated server-request resolution require the applicable Dealer-side control and exact target identity.

## 6. Work-state projection

Dealer retains the three-state work projection:

- `BUSY` — a confirmed active turn is progressing with no known blocking human request;
- `ATTENTION_REQUIRED` — a confirmed active turn has one or more unresolved blocking human requests;
- `READY` — no active turn prevents a new prompt.

Host availability is orthogonal. Unknown authoritative work state is represented as unknown and must disable actions that require a known state rather than inventing a fourth state.

## 7. Mutation and recovery invariants

The successor preserves the Legacy correctness model for mutations and reconnects.

- Send is semantic `turn/start`.
- Steer is semantic `turn/steer`; it must not be emulated through Interrupt plus a new prompt.
- Interrupt is bound to the exact currently confirmed turn.
- Structured command approvals, file approvals, and user-input requests remain structured.
- Server requests are fenced by request identity plus the observed app-server generation and normalized scope/fingerprint where applicable.
- Request resolution is monotonic: `PENDING → RESPONDING → RESOLVED`, or `UNKNOWN` when acceptance cannot be established.
- Pending outbound actions use exact target/revision/operation fencing where applicable.
- An uncertain Send, Steer, Interrupt, or request response is never blindly replayed.
- Reconnection initializes a replacement app-server connection, rereads/rejoins authoritative state, and reconciles local state.
- Authoritative reconciliation updates the existing local operation/projection instead of fabricating duplicate user input.
- Durable drafts are cleared only after acceptance of the exact outbound action is known. Unknown acceptance locks the relevant draft/action until reconciliation.

## 8. Dealer ↔ Codex connectivity

Dealer ↔ Codex connectivity is independent of Dealer ↔ Poker transport.

Retained connectivity may include:

- route-neutral TCP/duplex streams;
- SSH;
- `codex app-server proxy`;
- one initialized app-server connection per enabled Codex host;
- distribution-aware daemon/session lifecycle;
- embedded userspace tailnet routing where applicable;
- bounded phase-specific failure handling and active cancellation.

CXR is not a Dealer ↔ Codex transport.

## 9. Dealer ↔ Poker transport replacement

The Legacy production Dealer ↔ Poker transport is obsolete in the successor. Production architecture must not depend on:

- NSD/mDNS enrollment discovery;
- fixed TCP port `39817`;
- Legacy Poker TCP sockets;
- PAKE as the Dealer ↔ Poker transport/bootstrap mechanism;
- pinned Poker mTLS;
- IP/endpoint persistence that exists only for the old Poker link.

The successor direction is Rokid CXR, with CXR-L `CUSTOMAPP` qualified first and CXR-M/CXR-S retained as the fallback/alternative candidate. During Legacy extraction only a project-owned, transport-neutral Poker boundary plus fake/no-op/loopback implementation is required. Real CXR qualification, security, reconnect behavior, and hardware acceptance are deferred to `SPEC/cxr.md` and `SPEC/decisions/cxr-mobile-path.md`.

## 10. Poker redesign boundary

Legacy `apps/poker` is evidence/reference, not successor implementation authority. Poker becomes a new Rokid client. Old Poker input grammar, action wheel, navigation transitions, pairing/socket architecture, and UI are not migrated merely because they worked in Legacy.

Rokid-specific hardware evidence, such as Camera2 behavior, may be consulted later without wholesale code import.

## 11. Dealer UI/UX boundary

Legacy Dealer screen structure is not successor architecture. During extraction Dealer needs only a runnable shell/diagnostics surface sufficient to start the service and exercise retained Codex behavior. Substantial Dealer UI/UX redesign is deferred to a later Open Design phase.

## 12. Extraction authority

The normative extraction plan is `SPEC/migrations/legacy-extraction.md` using donor:

- repository: `code2hack/Poker-Dealer-Legacy`
- commit: `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`

Retained code, tests, and fixtures are imported from that exact commit. No complete Legacy history is grafted.

## 13. Specification hierarchy

For successor work, authority descends in this order:

1. root `SPEC.md`;
2. accepted architecture decisions under `SPEC/decisions/`;
3. accepted focused specifications under `SPEC/`;
4. accepted migration specifications under `SPEC/migrations/` for the migration they govern;
5. accepted ADRs, when added;
6. implementation tests and fixtures;
7. implementation code;
8. `HANDOFF.md` and historical evidence;
9. Legacy repository and Git history as reference/evidence only.

If an older handoff or Legacy document conflicts with this constitution, this constitution wins.

## 14. Focused specifications

- `SPEC/decisions/cxr-mobile-path.md` — accepted CXR-L-first qualification correction; supersedes older CXR-M-canonical wording.
- `SPEC/migrations/legacy-extraction.md` — normative Legacy extraction contract, interpreted through accepted decision files.
- `SPEC/dealer-codex.md` — retained Dealer ↔ Codex contract derived from imported implementation/tests; completed after extraction evidence is established.
- `SPEC/cxr.md` — CXR qualification and transport contract; CXR-L `CUSTOMAPP` qualifies first, with CXR-M/CXR-S as fallback/alternative; deferred beyond core extraction.
- future Poker interaction/UI and Dealer UI/UX specs are written only after their redesign work begins.
