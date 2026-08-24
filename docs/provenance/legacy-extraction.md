# Legacy Extraction Provenance

**Successor:** `code2hack/Poker-Dealer`  
**Migration branch:** `migration/legacy-extraction`  
**Successor base:** `591c1965c5e4104457ed9a86f0f5710967b6b3ce`  
**Donor:** `code2hack/Poker-Dealer-Legacy`  
**Pinned donor:** `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`  
**Extraction authority:** `SPEC/migrations/legacy-extraction.md`

This repository does not graft the Legacy history. Imported implementation is selected from the exact pinned donor commit and recorded below.

## Classification inventory

| Donor path / logical slice | Successor owner/path | Class | Direct dependency notes | Tests / fixtures | Hardware / old Poker coupling |
|---|---|---|---|---|---|
| root Gradle wrapper/config/version catalog | root Gradle files | KEEP-THEN-REFACTOR | module graph adapted to omit Legacy Poker | build tasks | none |
| `shared/domain/.../Models.kt` | same | KEEP | serialization only | `CodexHostTest` and dependent suites | legacy hardware labels may remain only as data defaults until separately refactored |
| `shared/domain/.../CardOperations.kt` | same | KEEP | domain only | `CardOperationsTest` | presentation-neutral retained cards |
| `shared/domain/.../CommandApprovals.kt` | same | KEEP | domain models | `CommandApprovalsTest` | none |
| `shared/domain/.../FileApprovals.kt` | same | KEEP | domain models | `FileApprovalsTest` | none |
| `shared/domain/.../ComposerDraft.kt` | same | KEEP | serialization/JDK text segmentation | retained draft-focused tests | Poker navigation assertions are excluded with the replaced interaction layer |
| `shared/domain/.../ThreadActions.kt` | same | KEEP | domain only | `ThreadActionsTest` | none |
| `shared/domain/.../ThreadAttachments.kt` | same | KEEP | domain only | `ThreadAttachmentsTest` | attachment semantics retained independent of transport |
| `shared/domain/.../ThreadLifecycle.kt` | same | KEEP | domain only | `ThreadLifecycleTest` | none |
| `shared/domain/.../ThreadStartSettings.kt` | same | KEEP | domain only | `ThreadStartSettingsTest` | none |
| `shared/domain/.../ThreadWorkProjection.kt` | same | KEEP-THEN-REFACTOR | domain only | `ThreadWorkProjectionTest` | work-state invariant retained; Poker-specific naming may be refactored later |
| `shared/domain/.../UserInputRequests.kt` | same | ADAPT | request semantics retained; Legacy Poker panel conversion removed if required to break interaction coupling | `UserInputRequestsTest` | old Poker panel layout is replaced |
| `shared/domain/.../PokerInteraction.kt` | not imported | REPLACE / EVIDENCE-ONLY | old Poker reducer | Legacy tests remain donor evidence | old interaction grammar |
| `shared/domain/.../PokerActionWheel.kt` | not imported | REPLACE / EVIDENCE-ONLY | old Poker UI interaction | donor tests remain evidence | old action wheel |
| `shared/domain/.../PokerBindings.kt` | not imported | DEFER | old bindings | donor tests remain evidence | hardware/input-specific |
| `shared/domain/.../Morse.kt` | not imported | DEFER / EVIDENCE-ONLY | old Morse semantics | donor tests remain evidence | Poker interaction-specific |
| `shared/protocol/.../appserver/**` | same | KEEP | domain + coroutines/serialization | matching app-server tests and JSON fixtures | none |
| `shared/protocol/.../host/HostStreams.kt` | same | KEEP | coroutines | `HostStreamsTest` | route-neutral |
| `shared/protocol/.../host/JschHostSshClient.kt` | same | KEEP | JSch | exercised by retained host/app-server tests | route-neutral SSH |
| `shared/protocol/src/test/resources/app-server/**` | same | KEEP | test resources | app-server fixture corpus | none |
| `shared/protocol/PinnedMutualTls.kt` | not imported | DROP for production | Legacy Poker trust transport | donor test only | obsolete Poker transport |
| `shared/protocol/PokerEnrollmentDiscovery.kt` | not imported | DROP for production | NSD endpoint discovery | donor test only | obsolete Poker transport |
| `shared/protocol/PokerPairing*.kt` | not imported | DROP for production | PAKE/pinned identity | donor tests only | obsolete Poker transport |
| `shared/protocol/PokerConnection.kt` | not imported | REPLACE | Legacy socket/session transport | donor tests only | obsolete Poker transport |
| other `shared/protocol/Poker*.kt`, `PhotoProtocol.kt`, `TextChunker.kt`, `Protocol.kt` | selective / deferred | DEFER or REPLACE pending dependency analysis | mostly old Poker wire/presentation | donor evidence | old Poker-facing protocol |
| `native/embedded-tailnet/**` | same | KEEP | Go/Tailscale toolchain | Go unit tests | Dealer ↔ Codex host connectivity only |
| `apps/dealer/.../EmbeddedTailnetHostTcpDialer.kt` | same | KEEP | embedded-tailnet Android binding | integration/build coverage | Codex-host path only |
| `apps/dealer/.../HostConnectionIntentDataStore.kt` | same | KEEP | Android DataStore | retained store tests where available | Codex-host intent |
| `apps/dealer/.../DealerThreadAttachmentStore.kt` | same | KEEP | Room/domain | `ThreadAttachmentStoreTest` | transport-neutral attachment ownership |
| `apps/dealer/.../DealerStateRecoveryStore.kt` | same | KEEP | Room/domain | `DealerStateRecoveryStoreTest` | retained no-replay state |
| `apps/dealer/.../DealerPhotoAssetStore.kt` | deferred | KEEP/DEFER | local file storage | donor tests | multimodal successor decision deferred |
| `apps/dealer/DealerConnectionService.kt` | carved into successor coordinator/service components | ADAPT | mixed Codex + Poker + ASR/Photo/Morse | new carve tests plus retained protocol/domain suites | must not be copied wholesale |
| `apps/dealer/DealerActivity.kt` | minimal successor shell only | REPLACE/DEFER | Compose | smoke/build only | Legacy screen structure not authoritative |
| Dealer `PokerConnectionSocket.kt`, `PokerEnrollmentDiscovery.kt`, `PokerPairingEnrollmentClient.kt`, pairing UI/identity | not imported | DROP/REPLACE | NSD/TCP/PAKE/mTLS | donor tests remain evidence | obsolete Poker transport |
| Dealer ASR runtime/UI and `native/sherpa-onnx/**` | not imported in core extraction | DEFER | multimodal/native runtime | donor tests/evidence | later multimodal spec |
| `apps/poker/**` | not imported | REPLACE / EVIDENCE-ONLY | Legacy Poker app | donor tests/evidence only | entire redesigned Rokid client surface |
| relevant `docs/evidence/*` | provenance references, selective closeout citations | EVIDENCE-ONLY | historical acceptance | n/a | hardware names permitted in evidence |

Unclassified Legacy files are not imported.

## Imported-path manifest

This section is completed by the extraction closeout. Every imported file is listed either explicitly or through a directory entry whose byte-equivalence status applies to the complete imported directory.

| Donor path | Successor path | Class | Donor bytes unchanged? | Tests/fixtures unchanged? | Adaptation / rationale |
|---|---|---|---|---|---|

## Intentionally excluded production slices

- all of Legacy `apps/poker`;
- NSD/mDNS Poker enrollment discovery;
- fixed TCP `:39817` assumptions;
- Legacy Dealer/Poker TCP socket transport;
- PAKE Dealer ↔ Poker bootstrap/transport;
- pinned Poker mTLS transport;
- endpoint/IP persistence used only for the old Poker link;
- old Poker interaction reducer/action wheel/bindings/Morse semantics;
- Legacy Dealer screen/navigation architecture;
- generalized backend/agent adapters;
- full CXR implementation;
- Dealer-local ASR and final Photo/Morse product behavior during this core extraction.

## Successor adaptation principles

1. Mechanical KEEP imports precede semantic edits whenever practical.
2. Mixed files are adapted only at the boundary necessary to remove replaced Poker responsibilities.
3. No uncertain Codex mutation is replayed to compensate for extraction changes.
4. CXR concrete SDK types do not enter retained Codex packages.
5. Historical hardware names may remain in evidence/tests but are not introduced as successor product concepts.

## Verification ledger

To be completed during closeout with exact commands/results for:

- retained domain tests;
- app-server protocol/fixture suite;
- malformed/unknown RPC fail-safe behavior;
- host connectivity tests;
- persistence/recovery tests;
- Dealer build and no-Poker startup path;
- Codex smoke or a precise statement of any live-environment limitation;
- static purge audit for Legacy Poker transport terms/dependencies.

## Successor commit range

Start: `591c1965c5e4104457ed9a86f0f5710967b6b3ce`  
End: to be recorded at extraction closeout.
