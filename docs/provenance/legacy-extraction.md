# Legacy Extraction Provenance

**Status:** Repository extraction + fresh real-device acceptance closeout
**Successor:** `code2hack/Poker-Dealer`  
**Migration branch:** `migration/legacy-extraction`  
**Architecture base / merge base:** `80303aaf568570dad74f244b3ee48ba01efc5fba`
**Initial extraction checkpoint:** `86e2106df022c7dc6b68cef99ceeac11deb36f46`
**Implementation end before closeout docs:** `2a649dbc814c2b0d5b9d62ffa217d9123c9c686d`
**Donor:** `code2hack/Poker-Dealer-Legacy`  
**Pinned donor:** `0a12901f58abf7cf7324bd92e876f4423f1cbeaa`  
**Extraction authority:** `SPEC/migrations/legacy-extraction.md` interpreted through `SPEC/decisions/cxr-mobile-path.md`

The successor does not graft Legacy Git history. Retained implementation was selected from the exact pinned donor commit, mechanically copied where practical, tested, and then adapted only at intentionally replaced boundaries.

## 1. Classification inventory

| Donor path / logical slice | Successor owner/path | Class | Result |
|---|---|---|---|
| root Gradle wrapper/config/version catalog | root Gradle files | KEEP-THEN-REFACTOR | wrapper/config restored; module graph excludes `apps:poker`; shared JVM toolchain adapted to JDK 17 while JVM target remains 17 |
| `shared/domain/Models.kt` | same | ADAPT | durable host/thread semantics retained; personal hardware default catalog removed from active domain |
| `shared/domain/CardOperations.kt` | same | KEEP | retained |
| `shared/domain/CommandApprovals.kt` | same | KEEP | retained |
| `shared/domain/FileApprovals.kt` | same | KEEP | retained |
| `shared/domain/ComposerDraft.kt` | same | KEEP-THEN-REFACTOR | durable ordered draft semantics retained; obsolete Poker-navigation test coupling excluded |
| `shared/domain/ThreadActions.kt` | same | KEEP | Send/Steer/Interrupt fencing retained |
| `shared/domain/ThreadAttachments.kt` | same | KEEP | host-qualified attachment/control-generation semantics retained |
| `shared/domain/ThreadLifecycle.kt` | same | KEEP | retained |
| `shared/domain/ThreadStartSettings.kt` | same | KEEP | retained |
| `shared/domain/ThreadWorkProjection.kt` | same | ADAPT | `BUSY / ATTENTION_REQUIRED / READY` retained; Legacy Poker pile/HUD reducer removed |
| `shared/domain/UserInputRequests.kt` | same | ADAPT | structured request/answer semantics retained; Legacy Poker panel-layout conversion removed |
| `shared/domain/PokerInteraction.kt` | not imported | REPLACE / EVIDENCE-ONLY | obsolete Poker interaction grammar excluded |
| `shared/domain/PokerActionWheel.kt` | not imported | REPLACE / EVIDENCE-ONLY | excluded |
| `shared/domain/PokerBindings.kt` | not imported | DEFER | excluded from extraction baseline |
| `shared/domain/Morse.kt` | not imported | DEFER / EVIDENCE-ONLY | excluded from extraction baseline |
| `shared/protocol/.../appserver/**` | same | KEEP / narrow ADAPT | protocol retained; only `CodexAppServerM1.kt` adapted to remove an active hardware-specific default host |
| `shared/protocol/.../host/HostStreams.kt` | same | KEEP | production bytes unchanged |
| `shared/protocol/.../host/JschHostSshClient.kt` | same | KEEP | production bytes unchanged |
| `shared/protocol/src/test/resources/app-server/v2/**` | same | KEEP | all 113 JSON fixtures copied byte-for-byte |
| Legacy Poker trust/socket protocol (`PinnedMutualTls`, enrollment, PAKE, pairing, Poker connection/socket) | not imported | DROP / REPLACE | absent from active successor production path |
| `native/embedded-tailnet/**` | same | KEEP-THEN-REFACTOR | exact donor import committed first, then two successor-label adaptations; Go tests/build retained |
| `apps/dealer/DealerDatabase.java` | same | KEEP | donor bytes unchanged |
| `apps/dealer/DealerThreadAttachmentStore.kt` | same | KEEP-THEN-REFACTOR | persistence semantics retained; implements successor-owned persistence port |
| `apps/dealer/DealerStateRecoveryStore.kt` | same | ADAPT | cached projection + request uncertainty retained; Legacy Poker binding persistence removed |
| `apps/dealer/HostConnectionIntentDataStore.kt` | same | ADAPT | intent/credential protections retained; profile metadata generalized to configured Codex hosts |
| `apps/dealer/EmbeddedTailnetHostTcpDialer.kt` | successor-owned adapted file | ADAPT | retained tunnel semantics behind route-neutral `HostTcpDialer`; no UI coupling |
| `apps/dealer/DealerConnectionService.kt` | successor coordinators/service host | ADAPT | not copied wholesale; Codex responsibilities carved into smaller successor components |
| `apps/dealer/DealerActivity.kt` | `DealerDiagnosticsActivity.kt` | REPLACE / DEFER | Legacy screen hierarchy not imported; extraction-only diagnostics shell added |
| Dealer Poker socket/discovery/pairing UI/identity | not imported | DROP / REPLACE | absent |
| Dealer ASR runtime/UI and `native/sherpa-onnx/**` | not imported | DEFER | later multimodal work |
| `apps/poker/**` | not imported | REPLACE / EVIDENCE-ONLY | successor Poker remains a future Rokid redesign |
| real CXR-L / CXR-M / CXR-S implementation | not imported | DEFER | CXR-L `CUSTOMAPP` remains first future qualification candidate |

Unclassified Legacy files were not imported.

## 2. Imported-path manifest and byte equivalence

### Root/build

- `build.gradle.kts`, `gradle.properties`, and `gradle/libs.versions.toml`: donor bytes unchanged.
- `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`: donor blobs unchanged.
- `gradlew.bat`: committed Git blob equals the pinned donor blob; working-tree line endings may differ by checkout normalization.
- `settings.gradle.kts`: adapted to enable only successor modules (`:shared:domain`, `:shared:protocol`, `:apps:dealer`) and not `:apps:poker`.
- `shared/domain/build.gradle.kts` and `shared/protocol/build.gradle.kts`: donor dependency/plugin baseline retained; `jvmToolchain(21)` changed to `jvmToolchain(17)` because the extraction environment provides JDK 17, while bytecode target stays JVM 17.

### Shared domain

Current production comparison against the pinned donor:

- 8 retained production files are byte-identical.
- `Models.kt` is adapted only to remove the hardware-specific default host catalog; generic `CodexHost` and host-qualified identity remain.
- `ThreadWorkProjection.kt` is adapted to retain only presentation-neutral work-state derivation and `TurnOutcome`; Legacy Poker pile/HUD behavior is intentionally removed.
- `UserInputRequests.kt` is adapted to remove old Poker panel-layout conversion while retaining structured request semantics.

Current domain tests similarly retain the donor behavioral baseline where applicable; tests changed only where the successor intentionally removed hardware defaults or Legacy Poker interaction/presentation behavior.

### Shared app-server and host protocol

Current production comparison against the pinned donor:

- 12 of 13 files under `shared/protocol/.../appserver/` are byte-identical.
- `CodexAppServerM1.kt` is adapted to require an explicit `CodexHost` for the old M1 compatibility slice instead of defaulting to a named personal workstation. App-server request/response/recovery semantics are unchanged.
- both production files under `shared/protocol/.../host/` are byte-identical.
- app-server tests were adapted only to move historical hardware host fixtures into test scope.
- `HostStreamsTest` uses a generic test host rather than an active hardware default.

### App-server fixtures

`shared/protocol/src/test/resources/app-server/v2/` contains exactly **113 JSON fixtures** in both successor and pinned donor. A file-by-file `cmp` audit reports **0 byte differences**, **0 missing**, and **0 extra** fixtures.

### Dealer persistence and host connectivity

- `DealerDatabase.java`: donor bytes unchanged.
- `DealerThreadAttachmentStore.kt`: donor storage/migration semantics retained; successor adaptation is the narrow `DealerThreadStateStore` port implementation.
- `DealerStateRecoveryStore.kt`: adapted to preserve only cached thread projection plus command/file/user-input request uncertainty. Legacy Poker binding-map persistence is intentionally absent.
- `HostConnectionIntentDataStore.kt`: retained encrypted credential storage and enabled-host intent; adapted to store generic host metadata and configured-host IDs.
- `DealerHostConnectionConfig.kt`: extracted from the mixed Legacy service and generalized so personal machine names are not product-domain concepts.
- `native/embedded-tailnet/**`: all nine donor files were first committed byte-identically in `e327263`. The successor then changed only the runtime node hostname (`dealer-fold6` → `dealer-android`) and one build-log label (`Spark build host` → `ARM64 Linux build host`) in `2a649db`. Test-only historical labels remain evidence only.
- `EmbeddedTailnetController.kt` and `EmbeddedTailnetHostTcpDialer.kt`: successor-owned Android lifecycle/route adapters around the retained native engine. The embedded route remains Dealer ↔ Codex connectivity and does not enter Dealer ↔ Poker code.

### Dealer orchestration carve

Legacy `DealerConnectionService.kt` was not copied. Retained responsibilities were carved into:

- `CodexConnectionCoordinator.kt` — host-session generations, connect/disconnect observation, app-server lookup;
- `DealerCore.kt` / `DealerCoreState.kt` — thread/projection/attachment/reconciliation ownership;
- `DealerMutationCoordinator.kt` — Send, Steer, Interrupt persistence/fencing/accepted-rejected-unknown behavior;
- `DealerRequestCoordinator.kt` — command/file/user-input request parsing, persistence, generation fencing and responses;
- `DealerPersistence.kt` — successor-owned persistence ports;
- `DealerCoreService.kt` — minimal Android foreground-service host;
- `DealerHostSessionFactory.kt` — generic profile → route-neutral Codex host session construction.

### Poker-facing replacement seam

Successor-owned `PokerProjectionPort.kt` provides:

- `PokerProjectionPort`;
- semantic `PokerProjectionSnapshot` / `PokerThreadProjection`;
- `PokerTransport` and project-owned transport message;
- `NoOpPokerProjectionPort` / `NoOpPokerTransport`;
- `TransportPokerProjectionPort`.

No CXR-L, CXR-M, CXR-S, Rokid SDK, NSD, TCP pairing, PAKE, or pinned Poker mTLS type appears above or inside this seam.

### Minimal Android shell

`DealerDiagnosticsActivity.kt` is extraction-only diagnostics. It can expose host/session/thread state and exercise host enable/disable, thread refresh/attach/control, Send/Steer, Interrupt, and embedded-tailnet start/stop. It is not the successor Dealer UI architecture.

## 3. Intentionally excluded production slices

- all of Legacy `apps/poker`;
- NSD/mDNS Poker enrollment discovery;
- fixed TCP `:39817` assumptions;
- Legacy Dealer/Poker TCP sockets and heartbeat/reconnect machinery;
- PAKE Dealer ↔ Poker bootstrap;
- pinned Poker mTLS;
- endpoint/IP persistence used only for the old Poker link;
- old Poker interaction reducer, pile/HUD navigation, action wheel, bindings and Morse semantics;
- Legacy Dealer screen/navigation architecture;
- generalized backend/agent adapters;
- DSH, Pi, Hermes, ACP, or other backend support;
- concrete CXR implementation;
- Dealer-local ASR and final Photo/Morse product behavior in this extraction milestone.

## 4. Verification ledger

All commands below were executed from the successor repository on Spark unless noted otherwise.

### Shared retained baseline

```text
./gradlew :shared:domain:test :shared:protocol:test
BUILD SUCCESSFUL
```

This includes malformed/unknown JSON-RPC behavior, initialize/initialized, thread APIs, Send/Steer/Interrupt protocol surfaces, structured command/file/user-input parsing/resolution, host session management, and route-neutral stream tests.

### Fixture equivalence

```text
successor fixture count = 113
donor fixture count     = 113
missing                 = 0
extra                   = 0
byte differences        = 0
```

### Successor Dealer + Android build gate

```text
ANDROID_HOME=/home/code2hack/Android/Sdk ./gradlew \
  :shared:domain:test \
  :shared:protocol:test \
  :apps:dealer:testDebugUnitTest \
  :apps:dealer:assembleDebug \
  :apps:dealer:assembleDebugAndroidTest \
  :apps:dealer:verifyEmbeddedTailnetPackaging
BUILD SUCCESSFUL
```

Successor Dealer tests include:

- accepted Send clears the exact persisted lock only after Codex acceptance;
- uncertain Send stays fenced and a second submission is rejected instead of replaying;
- stale Steer target is rejected before a Codex mutation;
- uncertain Interrupt stays locked and is not replayed;
- connection loss after `turn/start` write produces `UNKNOWN`, then replacement-session authoritative reread matches the exact `clientUserMessageId`, clears the lock, and proves `turn/start` was invoked exactly once;
- command approval remains structured and is resolved against the exact app-server generation/request identity;
- structured user-input questions remain structured through the Dealer coordinator and response;
- the Poker projection seam contains only attached semantic state and can be no-op/transport-backed without CXR types;
- embedded-tailnet status parsing fails closed on malformed/future state.

### Native embedded tailnet

```text
ANDROID_HOME=/home/code2hack/Android/Sdk ./gradlew :apps:dealer:buildEmbeddedTailnet
all modules verified
ok  code2hack.com/pokerdealer/embeddedtailnet
BUILD SUCCESSFUL
```

The Android packaging check confirms `lib/arm64-v8a/libgojni.so` is present in the debug APK.

### Android lint

```text
ANDROID_HOME=/home/code2hack/Android/Sdk ./gradlew :apps:dealer:lintDebug
BUILD SUCCESSFUL
```

### Static purge audit

Production source was searched for:

- `39817`;
- Android NSD;
- Legacy Poker socket/discovery/pairing classes;
- PAKE;
- pinned Poker mTLS;
- concrete CXR/Rokid SDK types;
- DSH/Hermes/ACP/general agent-adapter symbols;
- `apps:poker` module dependency;
- personal hardware nicknames in active Kotlin/Java/native production code.

The audit returned no active obsolete Poker transport, no concrete CXR SDK dependency, no generalized backend adapter, no `apps:poker` dependency, and no personal hardware nickname in active production source. Test/evidence fixtures may retain historical labels.

## 5. Fresh real-device acceptance — 2026-08-25

Fresh successor acceptance was executed on u4090 after the repository extraction closeout.

### Devices and live host

- Dealer device: Samsung `SM-F956N`, Android 16 / API 36, fingerprint `samsung/q6qksx/q6q:16/BP4A.251205.006/F956NKSS4DZG1:user/release-keys`.
- Attached Rokid evidence target: `RG-glasses`, Android 12 / API 32, fingerprint `Rokid/glasses/glasses:12/SKQ1.240613.001/1.23.009-20260725-150201:user/release-keys`. Dealer was not installed on this device.
- Codex-host evidence label: u4090. The live daemon reported Codex CLI/app-server `0.149.1`.
- Dealer used the generic configured Codex-host profile and the embedded-tailnet route. The phone had no usable LAN route to the host during the run.

### Repository and install gate

The pre-device gate passed with:

```text
ANDROID_HOME=/home/code2hack/Android/Sdk
ANDROID_NDK_HOME=/opt/android-sdk/ndk/23.1.7779620
./gradlew \
  :shared:domain:test \
  :shared:protocol:test \
  :apps:dealer:testDebugUnitTest \
  :apps:dealer:assembleDebug \
  :apps:dealer:assembleDebugAndroidTest \
  :apps:dealer:verifyEmbeddedTailnetPackaging \
  :apps:dealer:lintDebug
BUILD SUCCESSFUL
```

The explicit NDK path is an environment workaround: `/home/code2hack/Android/Sdk/ndk/23.1.7779620` was an incomplete local SDK installation, while `/opt/android-sdk/ndk/23.1.7779620` was a complete installation of the same NDK version. No product/toolchain upgrade was made for that issue.

The debug APK installed on the phone, `DealerDiagnosticsActivity` cold-launched successfully, and `DealerCoreService` ran as an Android foreground `dataSync` service (`isForeground=true`, notification ID 1701) with no Poker/CXR dependency.

### Validation-driven SSH fix

The first real host connection exposed an Android-specific retained-SSH defect. The stored `known_hosts` pin was an `ssh-ed25519` key whose SHA-256 fingerprint exactly matched the current host ED25519 key, but JSch could not retain ED25519 in its Android host-key proposal because its Java-15 ED25519 verifier is unavailable from the base multi-release-JAR classes on this runtime.

The fix is deliberately narrow and fail-closed:

- keep JSch at the retained `2.28.5` version;
- add its matching optional `bcprov-jdk18on:1.85` provider so ED25519 verification is available on Android;
- derive JSch `server_host_key` negotiation from the key families actually present in the pinned `known_hosts` entry;
- keep `StrictHostKeyChecking=yes`;
- for RSA pins, permit SHA-2 host signatures only, never legacy SHA-1 `ssh-rsa`;
- unsupported pinned key types fail before negotiation;
- retain the underlying JSch host-key failure text in diagnostics.

Unit regression coverage was added for raw ED25519/ECDSA pins, RSA SHA-2-only negotiation, certificate-authority pins, and unsupported-key fail-closed behavior.

### Live Dealer ↔ Codex acceptance

The opt-in Android instrumentation harness `DealerRealDeviceAcceptanceTest` uses the actual Android embedded-tailnet, encrypted host profile, SSH implementation, daemon/proxy, WebSocket/app-server connection, and successor `DealerCore`. It never embeds credential material.

The final live run passed:

```text
DealerRealDeviceAcceptanceTest
OK (1 test)
Time: 52.351s
```

Observed/verified behavior:

- encrypted host-profile credential round-trip succeeded through the production Android Keystore store;
- embedded tailnet reached `CONNECTED` as `dealer-android`;
- strict pinned SSH connected over `SSH_EMBEDDED_TSNET`;
- Codex app-server initialized and reported `0.149.1`;
- a disposable thread was created as `READY`, attached, and Dealer control was held;
- a reviewed Send invoked the retained `turn/start` path and became `ACCEPTED`; the exact accepted draft was cleared;
- streaming produced a non-empty agent card;
- the live app-server session was deliberately closed after acceptance;
- a replacement initialized session connected;
- authoritative reread found exactly one user message with the submitted `clientUserMessageId`;
- the existing local user card reconciled to `DELIVERED` with one state-card identity and no duplicate input/replayed `turn/start`;
- stale Steer targeting was `REJECTED`, while Steer against the exact active turn was `ACCEPTED`;
- stale Interrupt targeting was `REJECTED`, while Interrupt against the exact active turn was `ACCEPTED` and the thread returned to `READY`.

Safe live limitations are explicit rather than weakened into artificial passes:

- a benign `pwd` prompt did not elicit a command-approval request on the live 0.149.1 host, so command approval retains automated executable evidence but no fresh live approval response;
- structured user-input live handling remains version-qualified to app-server `0.146.0`; the connected app-server was `0.149.1`, so this request type was not treated as qualified live coverage;
- file approval was not deliberately manufactured because doing so would require an unnecessary live source/workspace mutation. Automated structured file-approval coverage remains green.

### Android lifecycle and persistence

On the same phone:

- `HostConnectionProfileStoreTest` passed 1/1 using the real Android Keystore;
- `ThreadAttachmentStoreTest` passed 6/6, including durable drafts, host-qualified attachments, reasoning effort, uncertain Send/Interrupt locks surviving recreation without replay, and exact purge behavior;
- backgrounding the Activity preserved the same Dealer process and foreground service;
- returning to the Activity was a HOT launch with the service still running;
- a deliberate force-stop removed the Dealer process, and a subsequent COLD launch created a new process and restarted the foreground service;
- the embedded tailnet re-established after process recreation; u4090 observed the node active after both direct and relayed recovery paths.

### Poker/CXR negative evidence

- Dealer was not installed on the attached Rokid device;
- no phone TCP listener existed on port `39817`;
- active production source contains no Legacy Poker NSD/socket/pairing/PAKE/pinned-mTLS implementation;
- active production source contains no concrete CXR/Rokid SDK imports and no `:apps:poker` dependency;
- all Dealer ↔ Codex live acceptance above succeeded without Poker or a CXR session.

## 6. Deviations from the pinned donor

1. **JDK toolchain:** donor shared modules requested JDK 21 while targeting JVM 17. The extraction environment supplies JDK 17, so shared Gradle files use `jvmToolchain(17)` while retaining JVM target 17. No dependency versions were opportunistically upgraded.
2. **Hardware defaults removed:** active named workstation/phone defaults were removed from shared domain/protocol and replaced by configured generic Codex-host metadata. Historical labels remain only in tests/evidence.
3. **Poker presentation behavior removed:** Legacy pile/HUD reducer, Poker request-panel conversion, bindings/action-wheel/Morse behavior are not successor authority.
4. **Recovery narrowed:** Poker binding-map persistence was removed from `DealerStateRecoveryStore`; retained projection and structured request uncertainty remain.
5. **Dealer service carved:** the mixed Legacy `DealerConnectionService.kt` is replaced by smaller successor-owned Codex coordinators plus a minimal Android host.
6. **Poker transport replaced by seam:** no Legacy transport and no concrete CXR implementation is imported; only a transport-neutral project seam exists.
7. **Native labels generalized:** embedded-tailnet source was first imported byte-identically, then the device hostname/build log label were generalized without changing route/tunnel behavior.
8. **Android ED25519 provider:** fresh device validation showed JSch 2.28.5 could not verify a pinned ED25519 host key on the Android runtime without its optional provider. `bcprov-jdk18on:1.85` was added at the same JSch-matched version, and host-key negotiation is now constrained to already-pinned key families while strict verification remains enabled.

## 7. Commit lineage

Architecture base:

- `80303aa` — accepted CXR-L-first architecture correction on `origin/main`.

Initial extraction checkpoint:

- `86e2106` — constitution/build/shared-domain/shared-protocol/fixture checkpoint.

Implementation continuation:

- `7c2b689` — Dealer persistence baseline;
- `7ff2111` — Dealer Codex orchestration carve;
- `8d51115` — transport-neutral Poker projection seam;
- `fa8db72` — minimal Dealer diagnostics shell;
- `7bc30fe` — remove obsolete Poker pile/HUD behavior;
- `e327263` — exact retained Gradle-wrapper and embedded-tailnet donor assets;
- `2a649db` — generic Dealer Codex host connectivity + embedded-tailnet Android integration + structured-request integration evidence.

The final documentation/audit commit is the commit containing this closeout document. The exact final branch HEAD is reported in the migration handoff/review summary rather than self-referencing a commit SHA inside its own contents.

## 8. Closeout conclusion

Repository extraction is complete:

- Codex app-server is the only backend;
- Dealer ↔ Codex core has no Poker/CXR requirement;
- structured requests and mutation uncertainty remain fenced;
- no blind replay is preserved and directly tested;
- old Poker NSD/TCP/PAKE/pinned-mTLS transport is absent from active production code;
- a transport-neutral Poker-facing seam exists;
- concrete CXR remains deferred, with CXR-L `CUSTOMAPP` first to qualify;
- Legacy Poker UI/interaction code is not successor authority;
- Dealer UI remains free for later Open Design redesign;
- imported/adapted/deferred/dropped slices and deviations are recorded above.

Fresh real Android-device acceptance is now complete for the retained Dealer ↔ Codex path, including a controlled replacement-session/no-blind-replay exercise, exact-turn Steer and Interrupt, Android lifecycle recovery, and strict pinned-SSH connectivity. The structured-request live limitations above remain intentionally bounded and retain green automated executable evidence.
