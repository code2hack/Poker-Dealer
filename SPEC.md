# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 4  
**Date:** 2026-07-27  
**Repository:** `code2hack/Poker-Dealer`  
**Primary implementer:** fresh local Codex sessions  
**Product names:** **Dealer** = Android phone client; **Poker** = Rokid glasses client

This file is the single source of truth for the first production-capable version of Poker–Dealer. When code and this specification disagree, this specification wins until both are deliberately updated in the same commit.

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are normative.

---

## 1. Product contract

Poker–Dealer is a private mobile and wearable client for **Codex threads** that execute on one of the user's configured hosts.

The supported hosts are:

1. **DGX Spark** — Ubuntu/Linux, ARM64, upstream Codex distribution.
2. **u4090** — Ubuntu/Linux, x86-64 with RTX 4090, upstream Codex distribution.
3. **Fold6 Termux** — Android/Termux, ARM64, compatible community Termux Codex distribution.

Each host MUST run a long-lived `codex app-server` managed by an app-server daemon and exposed through a host-private Unix control socket.

The user may interact with one host-qualified Codex thread through:

- the host-local Codex TUI;
- Dealer on the Fold6;
- Poker on Rokid glasses through Dealer.

The thread continues to execute on its original host when the user changes surfaces.

### 1.1 Intended topology

```text
DGX Spark / Ubuntu / ARM64
┌──────────────────────────────────────────────────────────────┐
│ Host-local Codex TUI ─┐                                     │
│                        ├─ daemon-managed codex app-server    │
│                        │      └─ Unix control socket         │
│                        └─ repositories, tools, models, threads│
└──────────────────────────────▲───────────────────────────────┘
                               │ Tailscale + SSH
                               │ codex app-server proxy
                               │ WebSocket frames over SSH
                               │
                         Dealer / Fold6
                               │
                               │ authenticated Dealer↔Poker link
                               ▼
                         Poker / Rokid
                               ▲
                               │
u4090 / Ubuntu / x86-64       │
┌──────────────────────────────┴───────────────────────────────┐
│ Host-local Codex TUI ─┐                                     │
│                        ├─ daemon-managed codex app-server    │
│                        │      └─ Unix control socket         │
│                        └─ repositories, tools, models, threads│
└──────────────────────────────────────────────────────────────┘

Fold6 Termux / Android / ARM64
┌──────────────────────────────────────────────────────────────┐
│ Termux Codex TUI ─────┐                                     │
│                        ├─ daemon-managed codex app-server    │
│ Dealer ─ loopback SSH  │      └─ Termux-private Unix socket  │
│          + proxy ──────┘                                     │
│                          repositories, tools, threads         │
└──────────────────────────────────────────────────────────────┘
```

Poker MUST NOT connect directly to any Codex host. Dealer is the only host client used by Poker.

Dealer MUST NOT directly open Termux-private files or Unix sockets across Android application sandboxes. The Termux route is loopback SSH followed by `codex app-server proxy`.

### 1.2 Product purpose

Poker–Dealer MUST support:

- discovering Codex threads on all configured hosts;
- reading existing thread history;
- resuming a stored or currently loaded thread;
- observing a running turn;
- streaming structured Codex items and agent-message deltas;
- starting a new turn;
- steering an active turn when the server accepts direct input;
- interrupting an active turn;
- reviewing and resolving supported approval or user-input requests;
- switching smoothly among host-local TUI, Dealer, and Poker;
- deterministic reconnection after phone-network, SSH, proxy, daemon, app-server, Android-process, or host interruption;
- a recent Dealer-owned projection for phone and glasses presentation;
- preserving complete text unless the user explicitly selects a conclusion-only presentation policy.

### 1.3 Explicit non-goals

The first product version MUST NOT attempt to provide:

- a tmux-pane backend;
- terminal screen scraping or ANSI parsing;
- a terminal emulator inside Dealer or Poker;
- arbitrary shell, editor, REPL, or general process continuity;
- live thread migration among DGX Spark, u4090, and Fold6 Termux;
- native Windows support;
- a custom host conversation bridge;
- WebRTC as the host control transport;
- direct Poker-to-host networking;
- direct Dealer-to-Termux private-socket access;
- proprietary Rokid CXR transport;
- cloud account synchronization beyond Codex's own services and host-local thread stores;
- support for arbitrary non-Codex agents.

Termux remains the separate full-terminal/editor/recovery surface even though Fold6 Termux is also a first-class Codex host.

---

## 2. Durable identity and terminology

### 2.1 Host

A **host** is one supported execution environment running one Codex installation, one `CODEX_HOME`, one daemon-managed app-server, and one host-local thread store.

A host MUST have a Dealer-assigned stable `hostId` that does not depend on hostname, IP address, Tailscale address, CPU architecture, package name, or SSH endpoint.

### 2.2 Host kind, distribution, route, and availability

Dealer MUST model host characteristics separately:

- **Host kind:** Linux workstation or Android/Termux.
- **Architecture:** Linux ARM64, Linux x86-64, or Android ARM64.
- **Codex distribution:** upstream Linux or community Termux.
- **Connection route:** Tailscale SSH or loopback SSH.
- **Availability class:** persistent or opportunistic.

Initial required host records are equivalent to:

| hostId | Host | Kind | Architecture | Distribution | Route | Availability |
| --- | --- | --- | --- | --- | --- | --- |
| `spark` | DGX Spark | Linux workstation | Linux ARM64 | upstream | Tailscale SSH | persistent |
| `u4090` | u4090 | Linux workstation | Linux x86-64 | upstream | Tailscale SSH | persistent |
| `fold6-termux` | Fold6 Termux | Android/Termux | Android ARM64 | community Termux | loopback SSH | opportunistic |

The exact IDs remain user-configurable Dealer identities; the table gives recommended initial values.

### 2.3 Thread

A **thread** is the durable Codex conversation and work context identified by app-server's `threadId` on one host.

The durable product identity is:

```kotlin
data class CodexThreadLocator(
    val hostId: String,
    val threadId: String,
)
```

`threadId` MUST NOT be treated as globally unique across hosts.

### 2.4 Turn

A **turn** is one user request and the associated Codex execution/response lifecycle.

### 2.5 Item

An **item** is structured user input or Codex output within a turn, including:

- user message;
- agent message;
- plan;
- reasoning summary;
- command execution and output;
- file change;
- approval request;
- user-input request;
- system or error status.

### 2.6 Client connection

A **client connection** is one initialized app-server JSON-RPC connection. It is disposable and MUST NOT be used as durable product identity.

### 2.7 Dealer projection and cards

Dealer MUST maintain a UI-oriented projection of host, thread, turn, and item state.

A **card** is a presentation unit derived from that projection for Dealer or Poker. Cards are not authoritative Codex records and MUST be rebuildable from app-server state plus current streamed events.

---

## 3. Authority and state ownership

### 3.1 App-server authority

Codex app-server is authoritative for:

- thread existence and identifiers;
- persisted thread history;
- loaded-thread state;
- turns and items;
- active turn status;
- command and file-change state;
- approval and user-input requests;
- model/provider execution state;
- host-local Codex configuration relevant to a thread.

### 3.2 Dealer authority

Dealer is authoritative for:

- configured hosts and stable host IDs;
- SSH endpoints and connection routes;
- host kind, distribution, architecture, and availability classification;
- distribution-specific daemon lifecycle and update behavior;
- connection lifecycle and retry state;
- mapping app-server data into mobile/wearable presentation;
- recent local cache and unread state;
- selected thread and viewport synchronization with Poker;
- intended human-control surface;
- pending outbound user actions until app-server acceptance is known;
- safety policy for whether an approval can be resolved on Poker.

### 3.3 Poker authority

Poker is authoritative only for:

- its current viewport;
- local scrolling and navigation;
- composition state;
- explicitly persisted pending Morse/ASR text;
- the user's direct semantic action before Dealer accepts it.

Poker MUST NOT become the authoritative store for thread history or delivery state.

---

## 4. Cross-surface and cross-host continuity

### 4.1 Required continuity

For any supported host, the following workflow MUST be supported:

1. The user starts or resumes a thread in the host-local Codex TUI.
2. The TUI uses the host's daemon-backed app-server.
3. The user changes location or device surface.
4. Dealer connects to the same host app-server through that host's SSH route and proxy.
5. Dealer locates and resumes or rejoins the same host-qualified thread.
6. Dealer reconstructs existing history and current status.
7. The user continues through Dealer.
8. Poker may attach to the Dealer projection of that same thread.

### 4.2 Host-bound execution

A thread remains bound to the host that owns its thread store, working directory, filesystem, tools, environment, and running processes.

Poker–Dealer MUST NOT present a thread as having moved among DGX Spark, u4090, and Fold6 Termux.

A future cross-host handoff MAY create or fork a new thread on another host after repository synchronization and an explicit handoff, but the resulting locator is a different `(hostId, threadId)`.

### 4.3 Multiple observers

Several initialized clients MAY observe the same thread.

Dealer MUST tolerate the host-local TUI and Dealer being subscribed at the same time.

### 4.4 One active human-control surface

Poker–Dealer adopts a one-writer product rule:

> Multiple clients may observe one thread, but only one intended human-control surface should actively submit turns, steer, interrupt, or resolve approvals at a time.

The first implementation MAY use a soft control indicator rather than a distributed lock.

Dealer MUST show the intended control surface and SHOULD require an explicit **Take control on phone** action before sending input when the thread is believed to be controlled locally.

Poker actions are considered Dealer-controlled because Poker routes through Dealer.

Dealer MUST still handle server rejection and races correctly; the control indicator is UX coordination, not a security primitive.

---

## 5. App-server lifecycle and host distributions

### 5.1 Direct daemon dependency

Direct dependency on the experimental app-server daemon is an accepted product decision.

Dealer MUST integrate with the installed distribution's machine-readable daemon lifecycle commands through SSH.

The lifecycle adapter SHOULD support:

- querying daemon and running app-server version/status;
- starting the daemon-managed app-server;
- restarting it;
- stopping it when explicitly requested;
- bootstrapping or restoring the installation when supported and requested.

Machine-readable output MUST be parsed when available. Dealer MUST NOT scrape human-oriented prose when a structured response exists.

### 5.2 Long-lived server requirement

The daemon-managed app-server MUST outlive individual Dealer SSH or proxy connections.

Dealer disconnection MUST NOT stop app-server or cancel unrelated threads.

### 5.3 Upstream Linux hosts

DGX Spark and u4090 use the upstream Linux Codex distribution.

Dealer MAY use upstream daemon bootstrap and update flows when they are supported by the installed version.

The implementation MUST support:

- Linux ARM64 for DGX Spark;
- Linux x86-64 for u4090.

### 5.4 Fold6 Termux host

Fold6 Termux uses a compatible community Termux Codex distribution. Poker–Dealer MUST NOT describe that distribution as an official OpenAI Android release.

Termux support is capability-based. Before marking the host `SUPPORTED`, Dealer or the compatibility suite MUST verify:

- daemon lifecycle commands;
- successful Unix-socket binding;
- `codex app-server proxy` connectivity;
- WebSocket and app-server initialization;
- required stable thread/turn methods;
- server-request handling;
- reconnect after proxy and daemon interruption.

Dealer MUST NOT assume that the upstream standalone installer, updater, or daemon bootstrap behavior applies unchanged to Termux.

Termux installation and updates MUST be handled by a distribution-specific adapter or documented user action.

### 5.5 Availability classes

DGX Spark and u4090 are persistent hosts.

Fold6 Termux is an opportunistic mobile host. Android may suspend or stop Termux, `sshd`, the daemon, or app-server.

Dealer MUST expose recoverable states equivalent to:

- host application unavailable;
- SSH unavailable;
- daemon stopped;
- daemon starting/restarting;
- proxy unavailable;
- app-server initializing;
- connected;
- Android suspended;
- backoff;
- error.

### 5.6 Host-local TUI consistency

The host-local Codex TUI SHOULD connect to the same daemon-backed app-server used by Dealer.

Each host setup MUST provide a documented launcher or startup check that ensures the daemon socket is ready before local TUI work, preventing accidental split-brain use of a separate embedded app-server process.

---

## 6. Dealer-to-host transport

### 6.1 Route selection

Dealer MUST choose the route from host configuration:

- DGX Spark and u4090: Tailscale + SSH;
- Fold6 Termux: loopback SSH to a Termux `sshd` listener.

SSH host identity MUST be pinned or verified using a user-approved trust-on-first-use flow. Host-key changes MUST require explicit review.

Termux loopback access SHOULD use a dedicated Dealer SSH key rather than a reusable password.

### 6.2 Android sandbox boundary

Dealer and Termux are separate Android applications. Dealer MUST NOT rely on direct access to Termux-private files, process descriptors, or Unix sockets.

Loopback SSH plus `codex app-server proxy` is the required boundary for phone-local host access.

### 6.3 Control and app-server channels

Dealer SHOULD maintain two logical SSH uses per host:

1. short-lived lifecycle/control commands;
2. a long-lived `codex app-server proxy` stream.

Dealer MAY implement both over one multiplexed SSH connection when the library supports independent channels safely.

### 6.4 Proxy framing

`codex app-server proxy` connects to the host's Unix socket and proxies the raw stream.

Dealer MUST implement:

- the WebSocket HTTP Upgrade handshake over the SSH exec channel;
- WebSocket text-frame encoding and decoding;
- ping/pong and close handling as needed;
- fragmented/coalesced transport reads;
- bounded ingress and egress queues;
- message-size limits;
- clean cancellation and reconnect.

The proxy stream MUST NOT be treated as newline-delimited JSON.

### 6.5 App-server initialization

Immediately after the WebSocket connection is established, Dealer MUST:

1. send one `initialize` request with Poker–Dealer client metadata and selected capabilities;
2. wait for the initialize response;
3. send the `initialized` notification;
4. reject or queue all other product requests until initialization completes.

Repeated initialization on the same connection MUST NOT occur.

---

## 7. App-server compatibility strategy

### 7.1 Compatibility objective

Dealer SHOULD remain usable across many newer Codex releases without requiring an Android update for every host update.

Literal compatibility with every historical, nightly, experimental, community, or future breaking version is not required.

### 7.2 Stable surface by default

Production Dealer MUST use the stable app-server API surface by default.

Dealer MUST NOT opt into `experimentalApi` globally merely because daemon lifecycle is experimental.

Individual experimental features MAY be added later behind explicit capability detection and feature flags.

### 7.3 Tolerant wire adapter

Dealer's wire layer MUST:

- parse the outer JSON-RPC shape generically;
- dispatch by method string;
- ignore unknown optional fields;
- preserve raw payloads for diagnostics;
- tolerate unknown notification methods;
- tolerate new item types by presenting a generic activity card when possible;
- use string-backed values for evolving wire enums where exhaustive matching would make forward compatibility brittle;
- safely reject unknown server-initiated requests so app-server cannot wait indefinitely;
- avoid sending optional fields that Dealer does not need.

### 7.4 Version and distribution handling

Dealer MUST record independently for each host:

- Codex distribution;
- local Codex CLI version;
- running app-server version when reported;
- platform family and operating system from initialization;
- capability/compatibility state;
- last successful connection and smoke test.

Hosts MAY run different Codex versions and distributions.

Dealer MUST NOT require exact version equality among hosts.

### 7.5 Compatibility states

Dealer SHOULD expose:

- `SUPPORTED` — required methods passed live compatibility checks;
- `DEGRADED` — core reading works but one or more optional features are unavailable;
- `UNSUPPORTED` — initialization or required methods fail incompatibly;
- `UNKNOWN` — not yet checked.

### 7.6 Required stable subset for MVP

The first production client SHOULD rely on the smallest useful stable subset, including equivalents of:

- `initialize` / `initialized`;
- `thread/list`;
- `thread/read`;
- `thread/start`;
- `thread/resume`;
- `thread/fork`;
- `thread/archive`;
- `thread/loaded/list` when available;
- `turn/start`;
- `turn/steer` when accepted;
- `turn/interrupt`;
- thread/turn/item status and message notifications;
- supported server-initiated approval and user-input requests.

Method availability MUST be verified against the installed server rather than assumed solely from a version number.

---

## 8. Host and thread discovery

### 8.1 Host dashboard

Dealer MUST show every configured host independently with:

- display name;
- host kind;
- architecture;
- distribution;
- connection route;
- availability class;
- SSH state;
- daemon state;
- app-server connection state;
- Codex/app-server version;
- active thread count when known;
- compatibility state.

### 8.2 Thread list

Dealer MUST support paginated thread listing and SHOULD display:

- host;
- thread name or preview;
- thread ID in a details view;
- working directory;
- updated/recency time when available;
- loaded/idle/active/error state;
- unread state;
- whether the thread is attached to Poker;
- intended control surface.

### 8.3 Resume and read

Dealer SHOULD use read-only thread APIs for browsing when possible and MUST resume/rejoin a thread before sending turns or subscribing to full live activity when required by app-server semantics.

When resuming a running thread, Dealer MUST treat the response as rejoining the existing live thread, not creating a new conversation.

### 8.4 Names and aliases

App-server thread names and Dealer-local aliases MUST NOT be treated as unique identifiers.

The locator `(hostId, threadId)` remains authoritative.

---

## 9. Turn and item projection

### 9.1 Structured projection

Dealer MUST project structured app-server events rather than infer messages from terminal pixels.

Typical mappings are:

| Codex item or event | Dealer/Poker presentation |
| --- | --- |
| user message | user card |
| agent message | agent card |
| agent-message delta | growing open agent card |
| plan | plan card |
| reasoning summary | optional reasoning-summary card |
| command execution | command card with state |
| command output | expandable command-output region |
| file change | file-change card |
| approval request | approval card |
| user-input request | question/input card |
| turn completed | turn status and usage |
| error | system/error card |

### 9.2 Text preservation

Full agent messages, command output retained by Dealer, and file-change text MUST NOT be silently summarized or semantically truncated.

Large content MAY be split into presentation continuations, but exact reassembly MUST be possible.

Poker MAY default to a conclusion-first view, but full text MUST remain available unless the user explicitly configures conclusion-only display.

### 9.3 Live updates and scrolling

A growing open card MUST update without forcing the viewport to the bottom when the user is reading earlier content.

Auto-follow MAY occur only when the user is already at the logical end or explicitly enables follow mode.

### 9.4 Lossless and best-effort events

Dealer MUST treat transcript deltas, authoritative item completion, turn completion, and unresolved server requests as lossless.

High-volume cosmetic progress or command-output deltas MAY be coalesced or degraded under backpressure only when the authoritative completed item can reconstruct final state.

---

## 10. Input and semantic actions

### 10.1 Dealer input

Dealer MUST support:

- starting a turn with reviewed text;
- steering an active turn with reviewed text when supported;
- interrupting an active turn;
- answering supported user-input requests;
- resolving supported approvals;
- switching intended control to Dealer.

### 10.2 Poker input

Poker MUST use semantic actions rather than terminal key emulation.

MVP Poker actions are:

- send reviewed text as a new turn;
- steer the active turn;
- interrupt;
- approve or deny only supported, fully displayed requests;
- switch thread;
- navigate and scroll.

Poker MUST NOT expose generic Control-C, Control-D, arrow-key terminal control, or arbitrary key injection as the product input model.

### 10.3 Morse and ASR

Morse and ASR both produce text drafts.

- Drafts MUST be reviewable before submission by default.
- ASR partial and final results MUST be visually distinguished.
- Audio MUST NOT be retained after transcription unless the user explicitly opts in.
- The selected host, thread, and action type MUST be shown before confirmation.

### 10.4 Idempotency

Dealer SHOULD provide a client-generated user-message identifier when the installed app-server supports it.

On timeout or reconnect, Dealer MUST inspect thread/turn state before retrying. It MUST NOT blindly replay a `turn/start` whose acceptance is unknown.

---

## 11. Approvals and safety

### 11.1 Structured requests

Dealer MUST treat server-initiated requests as structured blocking requests that require a response or explicit rejection.

### 11.2 Poker-safe approvals

Poker MAY resolve an approval only when:

- the complete command/action and relevant path/scope can be displayed;
- the request type is understood;
- the decision is limited and unambiguous;
- the user intentionally selects approve or deny;
- Dealer still has the matching unresolved request.

### 11.3 Dealer-required approvals

The following SHOULD require Dealer phone review:

- broad filesystem access;
- network permission expansion;
- persistent/session-wide grants;
- destructive commands;
- large or complex diffs;
- unknown request types;
- any request whose complete details cannot be safely displayed on Poker.

Deny and interrupt MAY always remain available when technically possible.

### 11.4 Duplicate resolution

Dealer MUST prevent Poker and Dealer from resolving the same server request twice. Late responses MUST be rejected locally when the request is no longer pending.

---

## 12. Reconnection and recovery

### 12.1 Expected disconnects

Dealer MUST treat all of the following as normal recoverable events:

- Android process or network interruption;
- Tailscale route change;
- SSH disconnect;
- proxy EOF;
- daemon restart;
- app-server update;
- workstation sleep/reboot;
- Termux application stop or Android suspension;
- loopback `sshd` stop;
- Poker disconnect/restart.

### 12.2 Host reconnect procedure

After app-server transport failure, Dealer SHOULD:

1. mark the connection unavailable without deleting cached state;
2. select the configured host route;
3. reconnect SSH with exponential backoff and jitter;
4. query distribution-specific daemon status/version;
5. ensure app-server is running when possible;
6. reopen the proxy;
7. repeat WebSocket and app-server initialization;
8. list loaded/stored threads as needed;
9. read or resume attached threads;
10. compare authoritative turn/item state with the local projection;
11. settle pending outbound actions without blind replay;
12. resynchronize Poker from Dealer's rebuilt projection.

### 12.3 Termux recovery

When the Termux route is unavailable, Dealer MUST distinguish likely recovery actions where possible:

- open/start Termux;
- start or restore `sshd`;
- start the app-server daemon;
- repair or update the community Codex distribution;
- retry after Android suspension or battery-policy intervention.

Dealer MUST NOT report Fold6 Termux as permanently disconnected merely because Android stopped it.

### 12.4 Daemon updates

A distribution updater MAY replace and restart app-server independently of Dealer.

Dealer MUST not assume one permanent app-server connection.

An app-server restart during an active turn MUST produce an explicit recovered, interrupted, failed, or unknown outcome based on authoritative state. Dealer MUST not fabricate successful completion.

### 12.5 Poker resynchronization

Dealer remains authoritative for the wearable projection.

On Poker reconnect, Dealer SHOULD send a deterministic retained snapshot followed by live incremental updates. Repeated snapshot or event delivery MUST be idempotent.

---

## 13. Dealer and Poker platform decisions

### 13.1 Dealer

Dealer MUST be a native Android application written in Kotlin using:

- Jetpack Compose;
- coroutines and `Flow`/`StateFlow`;
- Room for configured hosts, attached-thread metadata, recent projection/cache, unread state, and pending outbound actions;
- DataStore for non-secret preferences;
- Android Keystore-backed protection for SSH credentials, host-key pins, and Poker pairing secrets;
- a foreground service while live host or Poker connections are enabled.

Dealer is a Codex client, not a terminal emulator.

Dealer and Termux are independent Android apps. No shared-UID or private-filesystem coupling is required or allowed by the architecture.

### 13.2 Poker

Poker MUST be an ordinary native Android application using public Android APIs and compatible with the observed Android 12/API 32 glasses environment.

Poker MUST use:

- `minSdk = 28`;
- Kotlin and Jetpack Compose unless measured hardware constraints require a deliberately documented exception;
- shared pure-Kotlin domain/protocol modules;
- a foreground service for the Dealer listener when enabled;
- public Android input and audio APIs;
- platform-facing interfaces around HUD, input, audio, power, and networking.

### 13.3 Dealer↔Poker transport

The validated topology remains fixed:

```text
Fold6 / Dealer / hotspot owner / TCP client
                  │
                  │ authenticated bidirectional transport
                  ▼
Rokid / Poker / hotspot client / TCP listener
```

Dealer initiates the socket connection. Poker listens. Socket roles do not change product authority.

No CXR, ADB tunnel, or proprietary companion channel may be required.

---

## 14. Domain model direction

Shared pure-Kotlin domain code SHOULD use concepts equivalent to:

```kotlin
data class CodexHost(
    val id: String,
    val displayName: String,
    val kind: CodexHostKind,
    val architecture: HostArchitecture,
    val distribution: CodexDistribution,
    val connectionRoute: HostConnectionRoute,
    val availabilityClass: HostAvailabilityClass,
    val connectionState: HostConnectionState,
    val codexVersion: String?,
    val appServerVersion: String?,
)

data class CodexThreadLocator(
    val hostId: String,
    val threadId: String,
)

data class Conversation(
    val id: String,
    val locator: CodexThreadLocator,
    val alias: String,
    val state: ConversationState,
    val intendedControlSurface: ControlSurface,
    val unreadCount: Int,
)
```

Wire-schema types and UI projection types SHOULD remain separate.

The app-server adapter MUST NOT leak raw JSON parsing into Compose UI classes.

---

## 15. Persistence and secrets

Dealer MUST persist:

- host IDs and display names;
- host kind, architecture, distribution, route, and availability class;
- SSH endpoint configuration;
- user-approved host-key pins;
- daemon/app-server capability and compatibility observations;
- attached thread locators;
- local aliases and presentation policy;
- recent thread projection and unread state;
- pending outbound actions with idempotency metadata;
- Poker pairing and synchronization state.

Dealer MUST NOT persist plaintext private keys, passwords, bearer tokens, or ChatGPT credentials in Room or DataStore.

Codex authentication remains owned by Codex on each host unless a future explicitly designed flow says otherwise.

The Termux loopback SSH private key MUST be protected like workstation SSH credentials.

---

## 16. Milestones

### M0 — Architecture reset

Complete when:

- tmux backend and bridge are removed;
- README, CONTEXT, SPEC, AGENTS, ADRs, models, mocks, tests, and CI consistently use Codex host/thread terminology;
- no default-branch planning document instructs a worker to implement the old backend.

### M1 — DGX Spark Dealer vertical slice

Implement on DGX Spark first:

1. configured Tailscale SSH host;
2. daemon status/start integration;
3. proxy WebSocket connection;
4. initialize/initialized;
5. thread listing;
6. thread read/resume;
7. existing history rendering;
8. one new turn;
9. streamed agent message to completion;
10. reconnect with no duplicate turn.

Do not include Poker, Morse, ASR, terminal features, or broad experimental APIs in M1.

### M2 — u4090 and control-surface continuity

Complete when:

- DGX Spark ARM64 and u4090 x86-64 both work;
- threads are always host-qualified;
- host-local TUI and Dealer can observe the same daemon-backed thread;
- Dealer exposes intended control and safe take-control behavior;
- host versions may differ without breaking core use.

### M2T — Fold6 Termux host

Complete when:

- Dealer stores Fold6 Termux as a `TERMUX_ANDROID` / `ANDROID_ARM64` / `TERMUX_COMMUNITY` host;
- loopback SSH authentication works without plaintext passwords;
- distribution-specific daemon status/start behavior works;
- proxy WebSocket and app-server initialization work;
- thread list/read/resume and one turn work;
- local Termux TUI and Dealer can observe the same thread;
- Android suspension/process death recovery is user-visible and deterministic;
- capability checks document the tested community Codex build and limitations.

### M3 — Structured actions and approvals

Complete when Dealer supports:

- command/file-change presentation;
- supported approval and user-input requests;
- steering and interruption;
- lossless request handling and duplicate-resolution prevention.

### M4 — Dealer↔Poker synchronization

Complete when Poker can:

- pair with Dealer;
- list attached host-qualified threads from all supported hosts;
- switch conversations;
- read retained history and live agent output;
- reconnect deterministically;
- preserve scroll position during live growth.

### M5 — Wearable input

Complete when Poker supports:

- reviewed Morse text;
- reviewed ASR text;
- start/steer/interrupt;
- safe limited approvals;
- escalation to Dealer for complex review.

### M6 — Production hardening

Complete when:

- lifecycle/power tests pass on DGX Spark, u4090, Fold6 Termux, Dealer, and Rokid;
- daemon/app-server update interruption is handled per distribution;
- compatibility and recovery diagnostics are user-visible;
- security review covers SSH, Android sandbox boundaries, secrets, Poker pairing, and approval safety;
- end-to-end real-hardware acceptance is recorded.

---

## 17. Immediate next job

A fresh Codex worker MUST begin with the M1 DGX Spark Dealer vertical slice.

The worker MUST NOT start by restoring a bridge, integrating tmux, adding Poker networking, building a terminal, or implementing Termux-specific lifecycle behavior first.

The first deliverable is a tested Dealer-side adapter that can connect through Tailscale SSH + proxy to the long-lived Spark daemon, initialize app-server, list and resume one thread, stream one turn, and recover from reconnect without duplicate input.

The adapter architecture MUST already separate host kind, distribution, route, and availability so u4090 and Fold6 Termux can be added without rewriting the app-server protocol layer.
