# Poker-Dealer Successor Specification

**Status:** Normative successor constitution

Poker-Dealer is a two-client system for controlling persistent Codex sessions while preserving Codex app-server as the execution authority.

## 1. Product roles

- **Dealer** is the Android client. It owns Codex-host connectivity, Codex app-server integration, local projections, durable drafts and retained state, recovery, Dealer-local organization, and the semantic state synchronized toward Poker.
- **Poker** is the Rokid client. It is a lightweight wearable interaction and presentation surface and never connects directly to Codex app-server.
- **Codex app-server** is the only backend supported by the successor.
- **Codex host** means the execution host that owns a Codex installation, `CODEX_HOME`, app-server lifecycle, Projects, repositories, tools, and threads.
- **CXR-L** is the first Dealer-side Rokid transport candidate to qualify, using `CUSTOMAPP`.
- **CXR-M/CXR-S** is the fallback/alternative transport candidate if CXR-L fails qualification.
- The exact production CXR path remains unqualified until real-device evidence exists.

Specific hardware names are qualification/evidence details, not product architecture.

## 2. Backend decision

The successor does **not** implement a generalized agent-runtime adapter. There is no DSH, Pi, Hermes, ACP, or hypothetical universal backend layer.

Dealer integrates Codex app-server directly through project-owned Codex protocol and orchestration code. The validated Legacy Dealer ↔ Codex implementation is the primary extraction donor and is preserved unless a concrete successor requirement forces change.

## 3. Architecture

```text
Codex host
  └─ codex app-server
        ⇅
      Dealer
        ├─ Codex connectivity / session / projection / recovery
        ├─ Dealer UI and local organization
        ├─ Dealer-local ONNX ASR
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

The Dealer Codex core and Dealer UI must function with no Poker connection and without any CXR implementation.

## 4. Authority

Codex app-server is authoritative for:

- Project identity and Project/thread assignment when supported;
- thread identity and lifecycle;
- official `ThreadStatus`;
- turns and items;
- accepted input;
- live and retained Codex history;
- command/file state;
- effective thread settings;
- server-initiated requests and their authoritative resolution when reported.

Dealer is authoritative for:

- configured Codex hosts and connection intent;
- route selection and Codex-host connectivity;
- Dealer-local Chats membership;
- Poker attachment intent used by Poker-Dealer;
- local retained projection/cache and its freshness metadata;
- durable drafts and retained local assets;
- local operation state until Codex acceptance is known;
- Dealer-local ASR execution and transcript staging;
- Poker-facing semantic projection and synchronization state.

Poker is authoritative only for its local presentation/interaction state, locally captured input before Dealer accepts it, and a user action until Dealer accepts that action.

## 5. Durable identity and control

A Codex thread locator is host-qualified: `(hostId, threadId)`. A disposable SSH, WebSocket, proxy, or app-server connection is never durable thread identity.

A Dealer **Workspace** is exactly one official Codex **Project**. A cwd, folder, repository path, or arbitrary directory is not a Workspace.

Multiple clients may observe a thread. Poker-Dealer must keep observer state distinct from the intended active human-control surface. Send, Steer, Interrupt, settings changes, and user-initiated server-request resolution require the applicable Dealer-side authority and exact target identity.

## 6. Codex thread runtime and orthogonal Dealer state

For the thread-runtime axis, Dealer and Poker use only the official Codex app-server `ThreadStatus`:

- `notLoaded`
- `idle`
- `active { activeFlags }`
- `systemError`

The official active flags are:

- `waitingOnApproval`
- `waitingOnUserInput`

Poker-Dealer must not replace this model with `READY`, `BUSY`, `RUNNING`, `ATTENTION_REQUIRED`, `UNKNOWN`, or any other derived thread-state enum. User-facing icons may visualize the official state as specified by `SPEC/dealer-ui.md`, but those icons do not create alternative state semantics.

The following axes are orthogonal and must remain separate from `ThreadStatus`:

1. exact active-turn identity and turn kind/status;
2. Dealer connection and reconciliation authority;
3. outbound operation state, including unknown acceptance;
4. Dealer-local Chats membership;
5. Poker attachment and future CXR connectivity;
6. transient UI state;
7. local ASR session state.

When Dealer cannot establish fresh authority, it enters a Dealer-local reconnecting/reconciling condition and disables unsafe mutations. It does not fabricate or overwrite a Codex `ThreadStatus`.

Host availability and Poker attachment are orthogonal to Codex runtime state.

## 7. Mutation and recovery invariants

The successor preserves the Legacy correctness model for mutations and reconnects while adopting the current official Codex runtime model.

- Send is semantic `turn/start`.
- Steer is semantic `turn/steer`; it must not be emulated through Interrupt plus a new prompt.
- Steer is fenced by the exact expected active turn.
- Interrupt is bound to the exact currently confirmed active turn.
- An accepted interrupt request does not mean the turn has completed; Dealer waits for authoritative completion or reconciliation.
- Structured command approvals, file approvals, permission escalations, MCP elicitations, and user-input requests remain structured.
- Server requests are fenced by request identity plus the observed app-server generation and normalized scope/fingerprint where applicable.
- Request resolution is monotonic: `PENDING → RESPONDING → RESOLVED`, or `UNKNOWN` when acceptance cannot be established.
- Pending outbound actions use exact target/revision/operation fencing where applicable.
- An uncertain Send, Steer, Interrupt, settings update, or request response is never blindly replayed.
- Reconnection initializes a replacement app-server connection, rereads/rejoins authoritative state, and reconciles local state.
- Authoritative reconciliation updates the existing local operation/projection instead of fabricating duplicate user input.
- Durable drafts are cleared only after acceptance of the exact outbound action is known. Unknown acceptance locks the relevant draft/action until reconciliation.
- Cached official status is marked stale when authority is lost and is never presented as fresh authority.

## 8. Dealer ↔ Codex connectivity

Dealer ↔ Codex connectivity is independent of Dealer ↔ Poker transport.

Retained connectivity may include:

- route-neutral TCP/duplex streams;
- SSH;
- `codex app-server proxy`;
- one initialized app-server connection per enabled Codex host;
- distribution-aware app-server/session lifecycle;
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

The successor direction is Rokid CXR, with CXR-L `CUSTOMAPP` qualified first and CXR-M/CXR-S retained as the fallback/alternative candidate. During Legacy extraction only a project-owned, transport-neutral Poker boundary plus fake/no-op/loopback implementation is required. Real CXR qualification, security, reconnect behavior, hardware acceptance, and Poker-audio transfer are governed by `SPEC/cxr.md` and `SPEC/decisions/cxr-mobile-path.md`.

## 10. Poker redesign boundary

Legacy `apps/poker` is evidence/reference, not successor implementation authority. Poker becomes a new Rokid client. Old Poker input grammar, action wheel, navigation transitions, pairing/socket architecture, and UI are not migrated merely because they worked in Legacy.

Rokid-specific hardware evidence, such as Camera2 behavior, may be consulted later without wholesale code import.

## 11. Dealer product and UI authority

Dealer is an IM-style Android Codex client.

Primary navigation is:

- **Chats** — a Dealer-curated human-attention subset of Codex threads;
- **Workspaces** — official Codex Project and Project-thread management;
- **Settings** — Tailscale, Codex host, Poker, ASR, and general configuration.

Opening a thread pushes the Chat screen.

`Add to Chats` and `Remove from Chats` are Dealer-local organization operations. They are not `thread/unsubscribe`.

Workspaces must not implement a file-manager-like navigator. Project roots may be shown or selected only as official Project metadata. A host without the experimental Project APIs may still support Chats and Chat, but Workspaces must show Projects as unavailable rather than inventing cwd-based pseudo-workspaces.

The focused normative Dealer UI/UX contract is `SPEC/dealer-ui.md`. The accepted screenshot-derived geometry and visual references under `docs/design/` are evidence subordinate to that focused specification.

## 12. ASR

ASR runs locally in Dealer through an ONNX runtime and a supported locally deployed model.

The same Dealer-owned recognition pipeline serves:

- audio recorded by Dealer; and
- audio recorded by Poker and transferred to Dealer over a future qualified CXR path.

MVP sends recognized, user-reviewable text to Codex. It does not upload raw voice/audio as Codex `audio` or `localAudio` input. The focused contract is `SPEC/asr.md`.

## 13. Extraction authority

The normative extraction plan is `SPEC/migrations/legacy-extraction.md` using donor:

- repository: `code2hack/Poker-Dealer-Legacy`
- commit: `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`

Retained code, tests, and fixtures are imported from that exact commit. No complete Legacy history is grafted.

## 14. Specification hierarchy

For successor work, authority descends in this order:

1. root `SPEC.md`;
2. accepted architecture decisions under `SPEC/decisions/`;
3. accepted focused specifications under `SPEC/`;
4. accepted migration specifications under `SPEC/migrations/` for the migration they govern;
5. accepted ADRs, when added;
6. implementation tests and fixtures;
7. implementation code;
8. accepted design evidence under `docs/design/`;
9. protocol/research evidence under `docs/protocol/` and other evidence directories;
10. `HANDOFF.md` and historical evidence;
11. Legacy repository and Git history as reference/evidence only.

Evidence documents do not override normative specifications. If an older handoff, design record, or Legacy document conflicts with this constitution, this constitution wins.

## 15. Focused specifications and evidence pins

- `SPEC/dealer-ui.md` — normative Dealer navigation, Chats, Workspaces, Settings, Chat, state presentation, structured requests, settings, uploads, and recovery UX.
- `SPEC/asr.md` — normative Dealer-local ONNX ASR architecture and cross-client audio-source contract.
- `SPEC/dealer-codex.md` — retained Dealer ↔ Codex correctness and connectivity contract.
- `SPEC/decisions/cxr-mobile-path.md` — accepted CXR-L-first qualification correction.
- `SPEC/cxr.md` — CXR qualification and transport contract, including future Poker-audio transfer.
- `SPEC/migrations/legacy-extraction.md` — normative Legacy extraction contract, interpreted through accepted decision files.
- `docs/protocol/codex-app-server-6525b95d.md` — non-normative but auditable protocol evidence pinned to `openai/codex@6525b95dae2082ac9fee672b14c2cffdef172bb8`.
