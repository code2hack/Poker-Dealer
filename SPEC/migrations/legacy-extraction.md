# Legacy Extraction Plan

**Proposed path:** `SPEC/migrations/legacy-extraction.md`  
**Status:** Committed draft — pending normative acceptance  
**Successor repository:** `code2hack/Poker-Dealer`  
**Donor repository:** `code2hack/Poker-Dealer-Legacy`  
**Pinned donor commit:** `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`  
**Primary objective:** Reuse the validated Dealer ↔ Codex app-server implementation while deliberately replacing the Dealer ↔ Poker transport, the Poker Rokid client interaction/UI implementation, and the Dealer UI/UX.

---

## 1. Purpose

This plan governs extraction of validated implementation from `Poker-Dealer-Legacy` into the successor `Poker-Dealer` repository.

The extraction is **not** a rewrite of the Codex backend integration and **not** a wholesale continuation of the Legacy repository.

The intended successor architecture is:

- **Codex app-server** remains the only backend.
- **Dealer** remains the Android client and authority for Codex connectivity, projections, durable drafts/assets, recovery, and Poker-facing synchronization.
- **Poker** becomes the Rokid client.
- **Dealer ↔ Poker** is redesigned around **CXR-M ↔ CXR-S**.
- Poker interaction/UI is substantially redesigned.
- Dealer UI/UX is substantially redesigned after the CXR/Poker contracts stabilize.
- No generalized agent-runtime adapter layer is required.

The migration therefore has one central rule:

> **Preserve validated Dealer ↔ Codex behavior unless a concrete successor requirement forces a change; replace only the surfaces that are intentionally being redesigned.**

---

## 2. Why extraction precedes the full successor SPEC

A complete final successor SPEC should **not** be written before extraction.

Before extraction, the successor needs only enough normative specification to freeze:

1. product identity;
2. Codex-only backend scope;
3. terminology;
4. authority and distributed-state invariants;
5. the Legacy extraction boundary;
6. the decision that Dealer ↔ Poker transport is being replaced by CXR;
7. the decision that Poker and Dealer UI/UX are redesign surfaces.

The detailed retained Dealer ↔ Codex specification should then be written from:

- the imported implementation;
- imported fixtures and tests;
- existing Legacy acceptance evidence;
- deliberate review of any behavior that must change.

This avoids two failure modes:

- **rewriting validated behavior from memory before import**, and
- **accidentally treating all Legacy behavior as successor authority**.

---

## 3. Vocabulary rules

Normative successor specifications and new implementation abstractions should use role/capability terminology.

Preferred terms:

| Use | Avoid in normative architecture |
|---|---|
| Dealer | Fold6 |
| Android client | a specific phone model |
| Poker | RG as an architectural role |
| Rokid client | generic “glasses client” where CXR/Rokid specificity matters |
| Codex app-server | Spark/u4090 as product concepts |
| Codex host | a named current workstation unless evidence requires it |
| CXR-M | phone-device nickname |
| CXR-S | glasses-device nickname |

Specific hardware names may remain in:

- historical evidence;
- qualification matrices;
- test fixtures where the platform itself matters;
- debugging notes;
- provenance records.

Extraction **must not** perform broad mechanical renaming inside validated implementation merely to satisfy prose vocabulary. First preserve behavior; then refactor names only where they leak incorrect product assumptions into public APIs, domain types, or successor specification.

---

## 4. Donor freeze and provenance

### 4.1 Pinned donor

All extraction must use the exact donor commit:

`0a12901f58abf7cf7324bd92e876f4423f1cbeaa`

Do not extract from a moving `main` branch.

### 4.2 Provenance manifest

The successor must create a provenance manifest, suggested path:

`docs/provenance/legacy-extraction.md`

For every imported path or logical slice, record:

- donor repository;
- donor commit;
- original path;
- successor path;
- extraction classification;
- whether bytes were copied unchanged;
- whether tests/fixtures were copied unchanged;
- any adaptation commit that followed;
- reason for adaptation.

### 4.3 No history grafting requirement

Do **not** merge the complete Legacy Git history into the successor.

Preferred mechanics:

1. add/fetch the Legacy repository as a temporary/read-only Git remote, or otherwise obtain the pinned donor commit;
2. copy exact paths from the pinned donor commit;
3. commit imported slices atomically in the successor;
4. record donor SHA/path provenance.

A subtree merge or wholesale repository copy is not preferred because it obscures the intended successor boundary and imports deliberately abandoned implementation.

---

## 5. Extraction classifications

Every Legacy path considered for migration must receive exactly one initial classification.

### KEEP

Copy nearly byte-for-byte, together with tests and fixtures.

Use for validated code whose product responsibility is unchanged.

### KEEP-THEN-REFACTOR

Import unchanged first, establish a green successor baseline, then perform an isolated refactor without semantic change.

Use when naming/module placement is imperfect but behavior is valuable.

### ADAPT

Reuse the implementation logic, but introduce a small successor seam because the Legacy code mixes retained and replaced responsibilities.

Adaptation must be narrower than a rewrite.

### REPLACE

Do not migrate the implementation as production code. Preserve only tests, evidence, or behavioral knowledge where useful.

### DEFER

Do not import during the core extraction milestone. Reconsider under a later successor specification.

### DROP

Do not import. The behavior is explicitly obsolete.

### EVIDENCE-ONLY

Keep a reference/provenance link or selectively copy qualification evidence, but do not make the code part of the successor.

---

## 6. High-level keep/adapt/replace map

### 6.1 KEEP — Codex app-server protocol core

The strongest default-KEEP region is:

`shared/protocol/src/main/kotlin/com/code2hack/pokerdealer/protocol/appserver/`

This currently contains the core implementation for:

- app-server initialization;
- JSON-RPC/WebSocket framing;
- thread list/read/resume/start;
- thread lifecycle actions;
- turn start;
- steer;
- interrupt;
- command approvals;
- file approvals;
- structured user-input requests;
- host session management;
- connection initialization;
- structured card projection;
- retained card storage;
- thread discovery;
- thread start settings.

Initial KEEP candidates include:

- `CodexAppServerM1.kt`
- `CommandApprovals.kt`
- `FileApprovals.kt`
- `HostSessionManager.kt`
- `InitializedHostSessionConnector.kt`
- `RetainedCardStore.kt`
- `StructuredCardProjection.kt`
- `ThreadDiscovery.kt`
- `ThreadLifecycle.kt`
- `ThreadStartSettings.kt`
- `TurnInput.kt`
- `UserInputRequests.kt`
- `WebSocketJsonRpc.kt`

These should be imported **with their tests and app-server JSON fixtures in the same extraction phase**.

No CXR abstraction belongs inside this package.

No Poker UI concept belongs inside this package.

No generalized “agent adapter” should be introduced around it.

---

## 7. KEEP / KEEP-THEN-REFACTOR — Dealer domain

The existing `shared/domain` module contains both highly reusable Codex/Dealer domain logic and Legacy-Poker-specific interaction logic.

### 7.1 Strong KEEP candidates

Import first, then assess only concrete successor differences:

- core host/session/thread identity from `Models.kt`;
- `ThreadActions.kt`;
- `ThreadAttachments.kt`;
- `ThreadLifecycle.kt`;
- `ThreadStartSettings.kt`;
- `ThreadWorkProjection.kt`;
- `CommandApprovals.kt`;
- `FileApprovals.kt`;
- `UserInputRequests.kt`;
- `ComposerDraft.kt`;
- reusable card/projection operations where still presentation-neutral.

The current `BUSY | ATTENTION_REQUIRED | READY` work-state derivation should be retained unless the successor SPEC explicitly changes it.

Structured approvals/questions must remain structured.

Send, Steer, and Interrupt must remain semantic Codex operations.

Accepted/rejected/unknown mutation outcomes and no-blind-replay rules must survive.

### 7.2 REPLACE or DEFER candidates inside `shared/domain`

Do not automatically carry forward Legacy-Poker interaction behavior such as:

- old raw/canonical input mappings;
- old boundary-driven navigation/input transitions;
- old action-wheel semantics where they conflict with the successor Poker design;
- Legacy HID binding behavior unless reaccepted;
- old Morse behavior where dsh-style semantics supersede it.

Likely classifications:

- `PokerInteraction.kt` → **REPLACE / reference-only**
- `PokerActionWheel.kt` → **REPLACE / semantic reference**
- `PokerBindings.kt` → **DEFER**
- `Morse.kt` → **DEFER / reference-only until successor Morse spec**

The exact file split must be decided by dependency analysis rather than filename alone.

---

## 8. KEEP — Dealer ↔ Codex host connectivity

Dealer ↔ Codex connectivity is not part of the CXR redesign.

Initial retained areas include:

`shared/protocol/.../host/`

- `HostStreams.kt`
- `JschHostSshClient.kt`

and relevant Android-side implementations such as:

- `EmbeddedTailnetHostTcpDialer.kt`
- host connection-intent persistence;
- route-neutral stream abstractions;
- SSH/app-server proxy connection lifecycle.

The embedded-tailnet implementation under:

`native/embedded-tailnet/`

is a KEEP candidate because it belongs to Dealer ↔ Codex connectivity, not Dealer ↔ Poker transport.

However:

- successor prose should describe generic Codex-host connectivity;
- host-specific test/evidence names may remain historical;
- no specific currently owned machine becomes a normative product requirement.

---

## 9. KEEP — test fixtures are first-class extracted assets

The app-server test fixture corpus under:

`shared/protocol/src/test/resources/app-server/`

must be treated as production migration evidence, not disposable test data.

Import it together with the app-server implementation.

It currently covers important protocol surfaces such as:

- initialize / initialized;
- thread list/read/start/resume/fork/archive/delete;
- turn start/steer/interrupt;
- command approvals;
- file approvals;
- user-input requests;
- model/config reads;
- streaming deltas;
- request resolution;
- malformed/unknown RPC cases;
- mixed text/image turn input.

Rule:

> **No retained protocol implementation is considered extracted unless its corresponding tests and fixtures are also extracted and green.**

Do not weaken or delete a failing donor test merely to make the successor build pass.

---

## 10. ADAPT — Dealer application orchestration

`DealerConnectionService.kt` must **not** be copied wholesale as the successor architecture.

It is a large mixed orchestration surface containing:

- Codex app-server responsibilities;
- host connectivity;
- projection/recovery;
- Poker protocol behavior;
- old Poker transport;
- Photo;
- Morse;
- ASR;
- bindings;
- pairing/synchronization concerns.

This is the central surgical extraction task.

### 10.1 Required carve

Extract from the Legacy service only the responsibilities that belong to the retained Dealer core:

- Codex-host session lifecycle;
- app-server connection management;
- thread discovery;
- thread attachment;
- thread/read and projection reconciliation;
- turn start/steer/interrupt;
- request/approval handling;
- control generation / mutation fencing where transport-neutral;
- durable draft ownership;
- retained projection ownership;
- recovery/no-replay coordination;
- Android foreground-service lifecycle required by Dealer ↔ Codex connectivity.

### 10.2 New seam

The successor should establish an explicit Poker-facing boundary before CXR implementation.

Conceptually:

`DealerCore`
→ `PokerProjectionPort`
→ `PokerTransport`

The names may change, but the separation must exist.

Dealer core must not directly depend on:

- NSD;
- TCP listener endpoint discovery;
- port `39817`;
- PAKE;
- pinned mTLS;
- old Poker socket classes;
- CXR SDK concrete types;
- Compose UI.

### 10.3 Migration rule

Do not redesign Codex orchestration and CXR integration simultaneously.

First make the retained Dealer core compile and pass tests with a fake/no-op/loopback Poker-facing port.

Only then begin CXR-M integration.

---

## 11. REPLACE — Dealer ↔ Poker Legacy transport

The following Legacy responsibilities are intentionally superseded by CXR and should not enter the successor production path:

- Android NSD/mDNS enrollment discovery;
- fixed Poker TCP listener/endpoint assumptions;
- manual/implicit IP endpoint machinery;
- `PokerConnectionSocket` implementation;
- PAKE enrollment transport implementation;
- pinned mTLS transport implementation;
- old socket heartbeat/reconnect mechanics to the degree CXR replaces them.

Likely REPLACE/DROP candidates include:

- `PokerConnectionSocket.kt`
- `PokerEnrollmentDiscovery.kt`
- `PokerPairingEnrollmentClient.kt`
- Dealer-side pairing UI coupled to PAKE/NSD;
- `PinnedMutualTls.kt`;
- `PokerPairing.kt`;
- `PokerPairingWire.kt`;
- `PokerEnrollmentDiscovery.kt`;
- old listener/socket implementation in the Poker app.

Important:

Distributed-state invariants above the transport are **not** dropped just because the socket transport is dropped.

The successor must still preserve, as applicable:

- connection epoch;
- complete snapshot;
- revisioned delta;
- control generation;
- target/draft revision;
- operation ID;
- idempotency;
- accepted/rejected/unknown outcomes;
- authoritative reconciliation;
- no blind replay.

CXR replaces communication/bootstrap machinery, not state correctness.

---

## 12. REPLACE — Poker application architecture

Do not transplant `apps/poker` wholesale.

The successor Poker is a **Rokid client using CXR-S** and a redesigned interaction model.

Legacy Poker production code should therefore be classified mostly as:

- **EVIDENCE-ONLY** for device behavior;
- **REFERENCE** for Camera2 and proven hardware facts;
- **REFERENCE** for distributed-state edge cases;
- **REPLACE** for UI, input grammar, transport, and activity/service architecture where successor design differs.

Potentially reusable implementation snippets must be individually justified after the new Poker SPEC exists.

No Legacy Poker implementation should be imported merely because it already works.

---

## 13. DEFER — Dealer UI/UX

`DealerActivity.kt` and existing Dealer Compose UI should not define the successor product.

During extraction:

- provide only the minimum shell required to compile, start the service, inspect diagnostics, and exercise Codex behavior;
- do not invest in polishing Legacy screens;
- do not make old screen structure normative;
- do not bind the new CXR architecture to old UI navigation.

Dealer UI/UX receives a dedicated Open Design phase after:

1. retained Dealer ↔ Codex core is extracted;
2. CXR-M/S transport contract is known;
3. Poker interaction and projection contracts are stable enough to expose the right Dealer controls.

---

## 14. DEFER — ASR, Morse, and Photo product behavior

These features contain valuable validated implementation, but they cross the retained/redesigned boundary.

### Dealer-local ASR

Legacy Dealer-local ASR is a strong reuse candidate, but its successor behavior should be decided in the multimodal SPEC.

During core extraction:

- do not delete the donor implementation;
- do not automatically import all ASR UI/runtime machinery;
- classify it as **DEFER**;
- preserve provenance and tests for later selective extraction.

### Photo

Dealer photo asset storage and exact-byte preservation may remain valuable.

Likely classification:

- durable Dealer photo asset/storage primitives → **KEEP/DEFER**
- old Poker Photo controller and wire transport → **REPLACE**
- successor Photo session semantics → specify later.

### Morse

Legacy Morse code should remain reference material until the successor Poker interaction spec freezes Morse semantics.

---

## 15. Target repository shape immediately after extraction

The extraction milestone should remain conservative.

Suggested immediate shape:

- `apps/dealer/`
- `shared/domain/`
- `shared/protocol/`
- `shared/testing/` only where still useful
- `native/embedded-tailnet/`
- `SPEC.md`
- `SPEC/migrations/legacy-extraction.md`
- `SPEC/dealer-codex.md` — initially marked “derived/retained contract in progress”
- `SPEC/cxr.md` — skeleton/open qualification contract
- `SPEC/poker/interaction.md` — later
- `SPEC/poker/uiux.md` — later
- `SPEC/dealer/uiux.md` — later
- `docs/provenance/legacy-extraction.md`

Do not create empty architecture modules for hypothetical backends.

Do not add a generalized agent adapter package.

Do not create a second abstraction layer around Codex merely to appear extensible.

---

## 16. Phase 0 — successor constitution and migration freeze

### Goal

Create just enough normative authority to execute extraction safely.

### Required outputs

1. Root `SPEC.md` containing:
   - product definition;
   - Codex-only backend decision;
   - Dealer = Android client;
   - Poker = Rokid client;
   - CXR-M/S redesign direction;
   - authority hierarchy;
   - distributed-state invariants;
   - SPEC hierarchy/index;
   - document precedence rules.

2. This `legacy-extraction.md`.

3. Donor SHA pinned.

4. Migration branch created, suggested:
   - `migration/legacy-extraction`

### Gate

No production code extraction begins until these decisions exist in repository authority.

This does **not** require final CXR, Poker UI, or Dealer UI specs.

---

## 17. Phase 1 — complete Legacy inventory and classification

### Goal

Produce a machine-reviewable keep/adapt/replace/defer/drop matrix.

### Procedure

Inventory:

- root Gradle/build files;
- `apps/dealer`;
- `apps/poker`;
- `shared/domain`;
- `shared/protocol`;
- `shared/testing`;
- `native/embedded-tailnet`;
- `native/sherpa-onnx`;
- relevant tests;
- relevant evidence.

For each path, record:

- classification;
- successor owner/module;
- direct dependencies;
- tests;
- fixtures;
- known hardware assumptions;
- old Poker-transport coupling;
- whether naming-only refactor is desired later.

### Gate

Every file imported in later phases must trace back to an inventory row.

Unclassified files are not copied.

---

## 18. Phase 2 — bootstrap the successor build

### Goal

Establish a buildable successor project before behavior import.

### Actions

Reuse the Legacy Gradle/toolchain baseline where practical:

- wrapper;
- version catalog;
- root Gradle configuration;
- Android/Kotlin plugin versions;
- JVM targets;
- dependency declarations needed by retained modules.

The Legacy module graph currently includes:

- `:apps:dealer`
- `:apps:poker`
- `:shared:protocol`
- `:shared:domain`
- `:shared:testing`

The successor should initially enable only modules required for the extraction baseline.

Do not require `apps:poker` to exist merely to reproduce the old graph.

### Gate

A minimal successor Gradle build completes before importing large production slices.

---

## 19. Phase 3 — import `shared/domain` retained core

### Goal

Bring in transport-neutral Dealer/Codex semantics before protocol/orchestration.

### Actions

1. Copy strong-KEEP domain files and their unit tests.
2. Keep package names initially where changing them adds no product value.
3. Exclude/defer old Poker interaction files.
4. Resolve compile dependencies by importing the smallest required neutral dependency.
5. Do not pull old Poker transport into the domain just to satisfy compilation.

### Gate

All imported domain tests pass unchanged.

Any required semantic edit must be separately reviewed and documented as an ADAPT step.

---

## 20. Phase 4 — import Codex app-server protocol, host stack, tests, and fixtures

### Goal

Re-establish the validated Dealer ↔ Codex engine in isolation.

### Actions

Import:

- `shared/protocol/.../appserver/`
- retained `.../host/`
- app-server unit tests;
- JSON fixtures;
- required build dependencies.

Initially prefer byte-equivalent copies.

### Required verification

At minimum prove:

- JSON-RPC request/response multiplexing;
- initialize/initialized;
- thread discovery/list/read;
- resume/start/fork lifecycle;
- streaming item/delta projection;
- Send;
- Steer;
- Interrupt;
- command approval parsing/resolution;
- file approval parsing/resolution;
- structured user-input request parsing/resolution;
- malformed/unknown request fail-closed behavior;
- retained projection behavior;
- unknown-operation/no-replay tests where present.

### Gate

The imported protocol suite is green before Android Dealer orchestration is connected.

---

## 21. Phase 5 — import Dealer persistence and Codex-host connectivity

### Goal

Restore durable Dealer ownership without importing old Poker transport.

### Candidate retained slices

- host connection intent persistence;
- thread attachment store;
- state recovery store;
- retained card/projection storage;
- thread notification derivation where UI-neutral;
- host connection routing;
- embedded tailnet;
- SSH/proxy lifecycle.

### Actions

1. Import storage schema and migration logic required by retained core.
2. Identify any schema columns used only by old Poker pairing/transport.
3. Do not delete such columns inside the same commit as the initial import unless required to compile.
4. Follow with a deliberate cleanup migration after the baseline is green.

### Gate

Persistence unit/instrumentation tests for retained state pass.

Process restart/reload must not duplicate uncertain operations or destroy drafts/attachments.

---

## 22. Phase 6 — carve Dealer orchestration

### Goal

Recover a runnable Dealer core while removing dependency on Legacy Poker transport.

### Strategy

Start from the validated Legacy orchestration logic, but split it along responsibility boundaries.

Suggested conceptual components:

- `CodexConnectionCoordinator`
- `ThreadRepository` / projection coordinator
- `MutationCoordinator`
- `RequestCoordinator`
- `DraftRepository`
- `RecoveryCoordinator`
- `PokerProjectionPort`
- Android foreground-service host

Names are non-normative; the boundaries are what matter.

### Rules

- Do not refactor internal Codex behavior for style.
- Do not introduce a general backend adapter.
- Do not implement CXR yet.
- The Poker-facing port may initially be a no-op or loopback test implementation.
- Codex connection/recovery must function with Poker absent.

### Gate

Dealer can run as an Android client against a Codex app-server with no Legacy Poker transport code active.

---

## 23. Phase 7 — create CXR boundary stubs, not full CXR behavior

### Goal

Make the successor architecture explicit without mixing extraction with CXR qualification.

Introduce project-owned transport interfaces around the Poker-facing edge.

Conceptually:

- Dealer semantic projection/actions
- project-owned Poker protocol
- `PokerTransport`
- future `CxrPokerTransport`

CXR SDK concrete types must stay below the CXR transport implementation boundary.

### During extraction

Allowed:

- interfaces;
- no-op/fake transport;
- loopback transport;
- connection-state placeholder;
- tests proving Dealer core is transport-independent.

Not yet required:

- real CXR-M;
- real CXR-S;
- pairing/security decision;
- reconnect qualification;
- binary transfer qualification.

### Gate

No retained Dealer ↔ Codex package imports a CXR SDK class.

---

## 24. Phase 8 — retained behavior verification

### Goal

Prove that extraction preserved the backend functionality we intentionally kept.

### 24.1 Static/unit gate

All imported retained tests pass.

Tests should remain unchanged unless:

- the successor intentionally changed the normative contract; and
- the change is separately specified and reviewed.

### 24.2 Fixture gate

All imported app-server fixtures remain usable and cover the same parser/projection behavior.

### 24.3 Android integration gate

A minimal Dealer Android build proves:

- service startup;
- Codex app-server connection;
- thread discovery;
- history read;
- projection;
- one reviewed Send;
- live streaming;
- reconnect without duplicate input.

### 24.4 Structured-control gate

Where safe test fixtures/live test setup exists, verify:

- Steer;
- Interrupt;
- command approval;
- file approval;
- structured user-input request.

### 24.5 Recovery gate

Verify at least:

- transport loss to Codex host;
- app-server connection replacement;
- Android service/process recreation where practical;
- unknown mutation state remains fenced;
- no blind replay.

### 24.6 Poker independence gate

All of the above must work with:

- no connected Poker;
- no CXR session;
- no Legacy NSD/TCP/PAKE transport.

---

## 25. Phase 9 — Legacy Poker-transport purge

### Goal

Ensure obsolete transport did not leak through transitive copying.

### Search/audit targets

Production successor code should not retain active dependencies on:

- NSD enrollment discovery;
- old port `39817`;
- old Poker TCP listener;
- old Dealer Poker socket;
- PAKE transport code;
- pinned mTLS Poker transport;
- endpoint/IP persistence used only by the old Poker link.

Exceptions:

- provenance docs;
- historical evidence;
- migration notes;
- intentionally retained comparison tests.

### Gate

A repository audit demonstrates that the production Dealer ↔ Poker path is no longer coupled to the Legacy transport.

---

## 26. Phase 10 — extraction provenance closeout

### Goal

Declare the migration baseline closed before feature redesign begins.

### Required outputs

1. final path classification matrix;
2. imported donor-path manifest;
3. donor SHA;
4. successor commit range;
5. retained tests and results;
6. live smoke evidence;
7. list of adapted files with rationale;
8. list of deferred Legacy candidates;
9. list of intentionally dropped Legacy code;
10. known deviations from Legacy Dealer ↔ Codex behavior.

### Resulting authority

After closeout:

- successor repository implementation/tests become active implementation authority;
- Legacy remains historical evidence/reference;
- new behavior must be specified in successor SPEC files rather than edited back into Legacy.

---

## 27. Commit strategy

Extraction should be a chain of reviewable commits rather than one huge copy.

Suggested commit sequence:

1. **constitution + provenance skeleton**
2. **build/toolchain bootstrap**
3. **import retained domain + tests**
4. **import app-server protocol + tests + fixtures**
5. **import host transport/connectivity**
6. **import Dealer persistence**
7. **carve Dealer Codex orchestration**
8. **introduce Poker-facing transport seam**
9. **purge old Poker transport residue**
10. **record extraction acceptance evidence**

Each commit should:

- mention pinned donor SHA where donor code is introduced;
- avoid mixing mechanical import with semantic edits;
- keep tests in the same commit as the code they validate where practical.

---

## 28. Mechanical import rule

For any KEEP slice:

1. copy exact donor bytes;
2. compile/test;
3. commit the mechanical import;
4. only then refactor.

Do not combine:

- package rename;
- formatting;
- architecture cleanup;
- behavior change;
- dependency upgrade

with the first import commit unless unavoidable.

This makes regressions attributable.

---

## 29. Dependency-upgrade rule

Do not opportunistically upgrade Kotlin, Android Gradle Plugin, coroutines, serialization, JSch, Tailscale, or other core dependencies during extraction.

First reproduce a green baseline close to the validated donor.

Dependency modernization is a separate post-extraction task unless the donor dependency cannot build in the successor environment.

If an upgrade is unavoidable:

- record why;
- isolate it;
- rerun the entire affected donor test suite;
- do not claim byte-equivalent extraction for that slice.

---

## 30. Schema and persistence migration rule

The successor is a new application/repository, so it does not automatically need in-place upgrade compatibility with installed Legacy application data unless that is explicitly required later.

However, internal correctness rules still apply:

- no uncertain action may be guessed away;
- durable draft semantics must remain coherent;
- asset references must not silently orphan;
- retained projections may be rebuilt if derived;
- secrets must remain protected.

Do not spend extraction effort writing Legacy→successor on-device data migration unless the product explicitly decides users must upgrade an installed Legacy APK in place.

---

## 31. Test-preservation rule

The Legacy tests are not merely a safety net; for the retained Codex slice they are executable specification evidence.

Rules:

- copy relevant tests with code;
- preserve assertions initially;
- preserve fixture bytes initially;
- preserve edge-case tests for malformed/unknown app-server behavior;
- preserve recovery/idempotency tests;
- preserve live-test harnesses where still generally applicable.

A test may be removed only when:

1. its behavior is intentionally obsolete;
2. the successor SPEC explicitly says so;
3. the removal is reviewed separately from mechanical import.

---

## 32. Live-evidence rule

Historical Legacy evidence demonstrates that behavior was previously validated, but extraction still needs a narrow successor smoke.

The successor does **not** need to repeat every historical hardware acceptance test during this milestone.

Required live focus:

- Dealer Android client ↔ Codex app-server;
- retained thread/session behavior;
- retained structured actions;
- recovery/no-replay.

CXR and Rokid-client hardware acceptance belong to the later CXR/Poker milestones.

---

## 33. Anti-goals

The extraction milestone MUST NOT:

- design or implement a generalized agent adapter;
- support DSH/Pi/Hermes/ACP backends;
- rewrite the Codex app-server integration from scratch;
- redesign Dealer UI/UX;
- finish the Poker UI;
- fully implement CXR;
- preserve NSD/TCP/PAKE/mTLS merely because Legacy used them;
- copy all of `apps/poker`;
- copy all of `apps/dealer` unchanged;
- upgrade every dependency;
- normalize every old hardware-specific name in one sweep;
- change Send/Steer/Interrupt semantics without an explicit successor decision;
- weaken unknown-outcome/no-replay behavior;
- flatten structured approvals/questions into text.

---

## 34. Failure handling during extraction

### If a donor test fails after exact copy

Do not immediately change the test.

First inspect:

1. missing dependency;
2. build/toolchain mismatch;
3. missing fixture;
4. Android/JVM environment difference;
5. transitive dependency on an intentionally unimported Legacy component.

### If Codex core depends on old Poker code

Create the smallest transport-neutral interface that breaks the dependency.

Do not import the old Poker transport just to make compilation easier.

### If a Legacy behavior conflicts with the new constitution

Mark the path **ADAPT** or **REPLACE** and escalate the semantic difference into the relevant successor SPEC.

### If the required behavior is unclear

Preserve the donor behavior temporarily when it is on the retained Dealer ↔ Codex path, document the ambiguity, and avoid speculative redesign during extraction.

---

## 35. Acceptance criteria

Legacy extraction is complete only when all of the following are true:

### Repository / provenance

- [ ] Legacy donor commit is pinned.
- [ ] Every imported slice has provenance.
- [ ] Import and semantic-refactor commits are distinguishable.
- [ ] The successor builds independently of the Legacy repository.

### Backend

- [ ] Codex app-server remains the only backend.
- [ ] No general agent adapter exists.
- [ ] Core app-server tests and fixtures are imported and green.
- [ ] Thread discovery/history/resume operate.
- [ ] Send operates.
- [ ] Steer operates where app-server permits it.
- [ ] Interrupt operates.
- [ ] Supported approvals/questions retain structured semantics.
- [ ] Unknown outcomes remain reconciled without blind replay.

### Dealer

- [ ] Dealer runs as an Android client with Poker disconnected.
- [ ] Dealer owns durable attachments/drafts/projection as specified.
- [ ] Codex connectivity/recovery survives the extraction.
- [ ] Old Poker transport is not required for Dealer startup or Codex use.

### Dealer ↔ Poker boundary

- [ ] A project-owned transport seam exists.
- [ ] Dealer core is independent of CXR concrete SDK types.
- [ ] Old NSD/TCP/PAKE/mTLS is absent from the active successor production path.
- [ ] CXR implementation is still free to be qualified/designed independently.

### Poker/UI

- [ ] Legacy Poker production UI is not treated as successor authority.
- [ ] Dealer UI redesign remains unblocked by Legacy screen structure.
- [ ] Specific hardware names appear only where evidence/qualification requires them.

### Documentation

- [ ] Root `SPEC.md` describes the successor constitution.
- [ ] `SPEC/migrations/legacy-extraction.md` is accepted.
- [ ] `SPEC/dealer-codex.md` can now be completed from retained implementation/evidence.
- [ ] Deferred CXR/Poker/Dealer-UI specs are clearly identified.

---

## 36. Post-extraction sequence

Once this plan closes successfully, proceed in this order:

### A. Formalize retained Dealer ↔ Codex SPEC

Write `SPEC/dealer-codex.md` from the extracted implementation, tests, fixtures, and accepted Legacy invariants.

### B. CXR qualification and specification

Write/resolve `SPEC/cxr.md`:

- CXR-M lifecycle;
- CXR-S lifecycle;
- connection model;
- payload model;
- ordering/loss/duplicate behavior;
- security/trust properties;
- reconnect;
- app/process lifecycle;
- binary transfer;
- state-correctness envelope retained above CXR.

### C. Poker interaction and Rokid UI/UX

Write:

- `SPEC/poker/interaction.md`
- `SPEC/poker/uiux.md`

Use the accepted dsh-style interaction grammar where still desired, plus real Rokid/CXR evidence.

### D. Dealer UI/UX redesign

Use Open Design to produce and iterate:

- information architecture;
- navigation model;
- Codex-session management;
- Poker/Rokid management;
- CXR diagnostics/onboarding;
- request/approval surfaces;
- draft/multimodal surfaces;
- configuration and recovery UX.

Capture accepted design artifacts under `SPEC/dealer/uiux.md` and/or a dedicated design subtree.

### E. Tickets

Only after each spec slice is accepted should `to-tickets` convert it into implementation tickets with acceptance criteria.

---

## 37. Definition of success

The extraction is successful when the successor reaches this state:

> **The validated Dealer ↔ Codex app-server engine has been transferred with its tests, fixtures, state/recovery guarantees, and Android-client ownership intact; the Legacy Poker transport and Legacy UI architecture have not been allowed to define the successor; and the codebase now exposes a clean boundary on which CXR-M/CXR-S, the new Poker Rokid client, and the redesigned Dealer UI/UX can be built independently.**
