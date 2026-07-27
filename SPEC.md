# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 3  
**Date:** 2026-07-27  
**Repository:** `code2hack/Poker-Dealer`  
**Primary implementer:** fresh local Codex sessions  
**Product names:** **Dealer** = Android phone client; **Poker** = Rokid glasses client

This file is the single source of truth for the first production-capable version of Poker–Dealer. When code and this specification disagree, this specification wins until both are deliberately updated in the same commit.

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are normative.

---

## 1. Product contract

Poker–Dealer is a private mobile and wearable client for **Codex threads** that execute on the user's Linux workstations.

The supported workstations are:

1. **DGX Spark** — Ubuntu/Linux, ARM64.
2. **u4090** — Ubuntu/Linux, x86-64 with RTX 4090.

Each workstation MUST run a long-lived `codex app-server` managed directly by the experimental `codex app-server daemon` and exposed through its Unix control socket.

The user may interact with one host-qualified Codex thread through three surfaces:

- the official Codex TUI on the workstation;
- Dealer on the Fold6;
- Poker on Rokid glasses through Dealer.

The thread continues to execute on its original workstation when the user changes surfaces.

### 1.1 Intended topology

```text
DGX Spark / Ubuntu / ARM64
┌──────────────────────────────────────────────────────────────┐
│ Official Codex TUI ─┐                                       │
│                     ├─ codex app-server daemon              │
│                     │      └─ Unix control socket           │
│                     └─ repositories, tools, models, threads  │
└──────────────────────────────▲───────────────────────────────┘
                               │ Tailscale + SSH
                               │ codex app-server proxy
                               │ WebSocket frames over SSH stream
                               │
                         Dealer / Fold6
                               │
                               │ authenticated Dealer↔Poker link
                               ▼
                         Poker / Rokid
                               ▲
                               │
u4090 / Ubuntu / x86-64        │
┌──────────────────────────────┴───────────────────────────────┐
│ Official Codex TUI ─┐                                       │
│                     ├─ codex app-server daemon              │
│                     │      └─ Unix control socket           │
│                     └─ repositories, tools, models, threads  │
└──────────────────────────────────────────────────────────────┘
```

Poker MUST NOT connect directly to either workstation. Dealer is the only workstation client used by Poker.

### 1.2 Product purpose

Poker–Dealer MUST support:

- discovering Codex threads on both hosts;
- reading existing thread history;
- resuming a stored or currently loaded thread;
- observing a running turn;
- streaming structured Codex items and agent-message deltas;
- starting a new turn;
- steering an active turn when the server accepts direct input;
- interrupting an active turn;
- reviewing and resolving supported approval or user-input requests;
- switching smoothly between local TUI, Dealer, and Poker;
- deterministic reconnection after phone-network, SSH, proxy, daemon, or app-server interruption;
- a recent Dealer-owned projection for phone and glasses presentation;
- preserving complete text unless the user explicitly selects a conclusion-only presentation policy.

### 1.3 Explicit non-goals

The first product version MUST NOT attempt to provide:

- a tmux-pane backend;
- terminal screen scraping or ANSI parsing;
- a terminal emulator inside Dealer or Poker;
- arbitrary shell continuity;
- editor, REPL, or general process continuity;
- live thread migration between DGX Spark and u4090;
- native Windows support;
- a custom host conversation bridge;
- WebRTC as the workstation control transport;
- direct Poker-to-workstation networking;
- proprietary Rokid CXR transport;
- cloud account synchronization beyond Codex's own services and host-local thread stores;
- support for arbitrary non-Codex agents.

Termux + Mosh + tmux remains the separate full-terminal and recovery path.

---

## 2. Durable identity and terminology

### 2.1 Host

A **host** is one supported workstation running one Codex installation, one `CODEX_HOME`, one daemon-managed app-server, and one host-local thread store.

A host MUST have a Dealer-assigned stable `hostId` that does not depend on hostname, IP address, Tailscale address, CPU architecture, or SSH endpoint.

### 2.2 Thread

A **thread** is the durable Codex conversation and work context identified by app-server's `threadId` on one host.

The durable product identity is:

```kotlin
data class CodexThreadLocator(
    val hostId: String,
    val threadId: String,
)
```

`threadId` MUST NOT be treated as globally unique across hosts.

### 2.3 Turn

A **turn** is one user request and the associated Codex execution/response lifecycle.

### 2.4 Item

An **item** is a structured user input or Codex output within a turn, including but not limited to:

- user message;
- agent message;
- plan;
- reasoning summary;
- command execution;
- command output;
- file change;
- approval request;
- user-input request;
- system or error status.

### 2.5 Client connection

A **client connection** is one initialized app-server JSON-RPC connection. It is disposable and MUST NOT be used as durable product identity.

### 2.6 Dealer projection and cards

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

- configured hosts and SSH endpoints;
- host display names and local aliases;
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

## 4. Cross-surface continuity

### 4.1 Required continuity

The following workflow MUST be supported:

1. The user starts or resumes a thread in the official Codex TUI on DGX Spark or u4090.
2. The TUI uses the host's daemon-backed app-server.
3. The user leaves the workstation.
4. Dealer connects to the same host app-server through SSH and proxy.
5. Dealer locates and resumes or rejoins the same host-qualified thread.
6. Dealer reconstructs existing history and current status.
7. The user continues through Dealer.
8. Poker may attach to the Dealer projection of that same thread.

### 4.2 Host-bound execution

A thread remains bound to the host that owns its thread store, working directory, filesystem, tools, environment, and running processes.

Poker–Dealer MUST NOT present a thread as having moved from DGX Spark to u4090.

A future cross-host handoff MAY create or fork a new thread on another host after repository synchronization and an explicit handoff, but the resulting locator is a different `(hostId, threadId)`.

### 4.3 Multiple observers

Several initialized clients MAY observe the same thread.

Dealer MUST tolerate the local TUI and Dealer being subscribed at the same time.

### 4.4 One active human-control surface

Poker–Dealer adopts a one-writer product rule:

> Multiple clients may observe one thread, but only one intended human-control surface should actively submit turns, steer, interrupt, or resolve approvals at a time.

The first implementation MAY use a soft control indicator rather than a distributed lock.

Dealer MUST show the intended control surface and SHOULD require an explicit **Take control on phone** action before sending input when the thread is believed to be controlled locally.

Poker actions are considered Dealer-controlled because Poker routes through Dealer.

Dealer MUST still handle server rejection and races correctly; the control indicator is UX coordination, not a security primitive.

---

## 5. Workstation app-server lifecycle

### 5.1 Direct daemon dependency

Direct dependency on the experimental `codex app-server daemon` is an accepted product decision.

Dealer MUST integrate with the installed version's machine-readable daemon lifecycle commands over SSH.

The lifecycle adapter SHOULD support at least:

- querying daemon and running app-server version/status;
- starting the daemon-managed app-server;
- restarting it;
- stopping it when explicitly requested;
- bootstrapping or restoring the managed installation when the user requests onboarding or repair.

Successful daemon commands MUST be parsed as JSON. Dealer MUST NOT scrape human-oriented prose when machine-readable output is available.

### 5.2 Long-lived server requirement

The daemon-managed app-server MUST outlive individual Dealer SSH or proxy connections.

Dealer disconnection MUST NOT stop the app-server or cancel unrelated threads.

### 5.3 Unix-only support

Only Unix/Linux hosts are required.

The implementation MUST support:

- Linux ARM64 for DGX Spark;
- Linux x86-64 for u4090.

Native Windows lifecycle support is out of scope.

### 5.4 Local TUI consistency

The official Codex TUI SHOULD connect to the same local daemon-backed app-server used by Dealer.

The workstation setup MUST provide a documented launcher or startup check that ensures the daemon socket is ready before local TUI work, preventing accidental split-brain use of a separate embedded app-server process.

---

## 6. Dealer-to-host transport

### 6.1 Network path

Dealer MUST connect to each host through Tailscale and SSH.

SSH host identity MUST be pinned or verified using a user-approved trust-on-first-use flow. Host-key changes MUST require explicit review.

### 6.2 Control and app-server channels

Dealer SHOULD maintain two logical SSH uses per host:

1. short-lived lifecycle/control commands;
2. a long-lived `codex app-server proxy` stream.

Dealer MAY implement both over one multiplexed SSH connection when the library supports independent channels safely.

### 6.3 Proxy framing

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

### 6.4 App-server initialization

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

Literal compatibility with every historical, nightly, experimental, or future breaking version is not required.

### 7.2 Stable surface by default

Production Dealer MUST use the stable app-server API surface by default.

Dealer MUST NOT opt into `experimentalApi` globally merely because the daemon itself is experimental.

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

### 7.4 Version handling

Dealer MUST record independently for each host:

- local Codex CLI version;
- running app-server version when reported;
- platform family and operating system from initialization;
- compatibility state;
- last successful connection and smoke test.

DGX Spark and u4090 MAY run different Codex versions.

Dealer MUST NOT require exact version equality between hosts.

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

Method availability MUST be verified against the installed server rather than assumed solely from a hard-coded version number.

---

## 8. Thread discovery and attachment

### 8.1 Host dashboard

Dealer MUST show both hosts independently with:

- display name;
- architecture;
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

Dealer SHOULD use read-only thread APIs for browsing when possible and MUST resume/rejoin a thread before sending turns or subscribing to its full live activity when required by app-server semantics.

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

High-volume cosmetic progress or command-output deltas MAY be coalesced or degraded under backpressure only when the authoritative completed item can reconstruct the final state.

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
- The selected thread and action type MUST be shown before confirmation.

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
- Poker disconnect/restart.

### 12.2 Host reconnect procedure

After app-server transport failure, Dealer SHOULD:

1. mark the connection unavailable without deleting cached state;
2. reconnect SSH with exponential backoff and jitter;
3. query daemon status/version;
4. ensure app-server is running;
5. reopen the proxy;
6. repeat WebSocket and app-server initialization;
7. list loaded/stored threads as needed;
8. read or resume attached threads;
9. compare authoritative turn/item state with the local projection;
10. settle pending outbound actions without blind replay;
11. resynchronize Poker from Dealer's rebuilt projection.

### 12.3 Daemon updates

The daemon updater MAY replace and restart app-server independently of Dealer.

Dealer MUST not assume one permanent app-server connection.

An app-server restart during an active turn MUST produce an explicit recovered, interrupted, failed, or unknown outcome based on authoritative state. Dealer MUST not fabricate successful completion.

### 12.4 Poker resynchronization

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

### 13.2 Poker

Poker MUST be an ordinary native Android application using public Android APIs and compatible with the observed Android 12/API 32 glasses environment.

Poker MUST use:

- `minSdk = 28`;
- a foreground service for the Dealer listener when enabled;
- public Android input and audio APIs;
- platform-facing interfaces around HUD, input, audio, power, and networking.

### 13.3 Dealer↔Poker transport

The previously validated topology remains fixed:

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
    val architecture: HostArchitecture,
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
- SSH endpoint configuration;
- user-approved host-key pins;
- daemon/app-server compatibility observations;
- attached thread locators;
- local aliases and presentation policy;
- recent thread projection and unread state;
- pending outbound actions with idempotency metadata;
- Poker pairing and synchronization state.

Dealer MUST NOT persist plaintext private keys, passwords, bearer tokens, or ChatGPT credentials in Room or DataStore.

Codex authentication remains owned by Codex on each workstation unless a future explicitly designed flow says otherwise.

---

## 16. Milestones

### M0 — Architecture reset

Complete when:

- tmux backend and bridge are removed;
- README, CONTEXT, SPEC, AGENTS, ADRs, models, mocks, tests, and CI consistently use Codex host/thread terminology;
- no default-branch planning document instructs a worker to implement the old backend.

### M1 — One-host Dealer vertical slice

Implement on DGX Spark first:

1. configured SSH host;
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

### M2 — Multi-host and control-surface continuity

Complete when:

- DGX Spark ARM64 and u4090 x86-64 both work;
- threads are always host-qualified;
- local TUI and Dealer can observe the same daemon-backed thread;
- Dealer exposes intended control and safe take-control behavior;
- host versions may differ without breaking core use.

### M3 — Structured actions and approvals

Complete when Dealer supports:

- command/file-change presentation;
- supported approval and user-input requests;
- steering and interruption;
- lossless request handling and duplicate-resolution prevention.

### M4 — Dealer↔Poker synchronization

Complete when Poker can:

- pair with Dealer;
- list attached host-qualified threads;
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

- lifecycle/power tests pass on both hosts, Fold6, and Rokid;
- daemon/app-server update interruption is handled;
- compatibility and recovery diagnostics are user-visible;
- security review covers SSH, secrets, Poker pairing, and approval safety;
- end-to-end real-hardware acceptance is recorded.

---

## 17. Immediate next job

A fresh Codex worker MUST begin with the M1 one-host Dealer vertical slice on DGX Spark.

The worker MUST NOT start by restoring a bridge, integrating tmux, adding Poker networking, or building a terminal.

The first deliverable is a tested Dealer-side adapter that can connect through SSH + proxy to the long-lived daemon, initialize app-server, list and resume one thread, stream one turn, and recover from reconnect without duplicate input.
