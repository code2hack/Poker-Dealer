# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 7
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
┌─────────────────────────────────────────────────────────────┐
│ Host-local Codex TUI ─┐                                    │
│                        ├─ daemon-managed codex app-server   │
│                        │      └─ Unix control socket        │
│                        └─ repositories, tools, threads       │
└──────────────────────────────▲──────────────────────────────┘
                               │ SSH
                               │ codex app-server proxy
                               │
                 ┌─────────────┴──────────────┐
                 │ Dealer route selection     │
                 │ 1. trusted LAN             │
                 │ 2. embedded userspace tsnet│
                 │ 3. external Tailscale      │
                 └─────────────▲──────────────┘
                               │
                         Dealer / Fold6
                               │
                               │ authenticated Dealer↔Poker
                               ▼
                         Poker / Rokid
                               ▲
                               │
u4090 / Ubuntu / x86-64       │
┌──────────────────────────────┴──────────────────────────────┐
│ Host-local Codex TUI ─┐                                    │
│                        ├─ daemon-managed codex app-server   │
│                        │      └─ Unix control socket        │
│                        └─ repositories, tools, threads       │
└─────────────────────────────────────────────────────────────┘

Fold6 Termux / Android / ARM64
┌─────────────────────────────────────────────────────────────┐
│ Termux Codex TUI ─────┐                                    │
│                        ├─ daemon-managed codex app-server   │
│ Dealer ─ loopback SSH  │      └─ Termux-private Unix socket │
│          + proxy ──────┘                                    │
│                          repositories, tools, threads        │
└─────────────────────────────────────────────────────────────┘
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
- deterministic reconnection after route, network, SSH, proxy, daemon, app-server, Android-process, or host interruption;
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
- a system-wide VPN implemented by Dealer;
- exit-node or default-route behavior in Dealer;
- cloud account synchronization beyond Codex's own services and host-local thread stores;
- support for arbitrary non-Codex agents.

Termux remains the separate full-terminal/editor/recovery surface even though Fold6 Termux is also a first-class Codex host.

---

## 2. Durable identity and terminology

### 2.1 Host

A **host** is one supported execution environment running one Codex installation, one `CODEX_HOME`, one daemon-managed app-server, and one host-local thread store.

A host MUST have a Dealer-assigned stable `hostId` that does not depend on hostname, IP address, Tailscale address, CPU architecture, package name, or SSH endpoint.

### 2.2 Host characteristics and routes

Dealer MUST model these host characteristics separately:

- **Host kind:** Linux workstation or Android/Termux.
- **Architecture:** Linux ARM64, Linux x86-64, or Android ARM64.
- **Codex distribution:** upstream Linux or community Termux.
- **Connection routes:** an ordered list of usable SSH routes.
- **Active route:** the route currently carrying the connection, if any.
- **Availability class:** persistent or opportunistic.

The initial route types are:

```kotlin
enum class HostConnectionRoute {
    SSH_LAN,
    SSH_EMBEDDED_TSNET,
    SSH_EXTERNAL_TAILSCALE,
    SSH_LOOPBACK,
}
```

Initial host records are equivalent to:

| hostId | Host | Kind | Architecture | Distribution | Ordered routes | Availability |
| --- | --- | --- | --- | --- | --- | --- |
| `spark` | DGX Spark | Linux workstation | Linux ARM64 | upstream | LAN → embedded tsnet → external Tailscale | persistent |
| `u4090` | u4090 | Linux workstation | Linux x86-64 | upstream | LAN → embedded tsnet → external Tailscale | persistent |
| `fold6-termux` | Fold6 Termux | Android/Termux | Android ARM64 | community Termux | loopback | opportunistic |

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

### 2.4 Turn, item, connection, and card

- A **turn** is one user request and the associated Codex execution/response lifecycle.
- An **item** is structured user input or Codex output within a turn, including agent messages, plans, reasoning summaries, commands, command output, file changes, approvals, user-input requests, and errors.
- A **client connection** is one initialized app-server JSON-RPC connection. It is disposable and MUST NOT be used as durable identity.
- A **Dealer projection** is Dealer's UI-oriented host/thread/turn/item state.
- A **card** is a Dealer/Poker presentation unit derived from that projection. Cards are not authoritative Codex records and MUST be rebuildable.

### 2.5 Embedded tailnet

The **embedded tailnet** is a Dealer-private userspace Tailscale node based on `tsnet`.

It MUST:

- carry only Dealer-owned sockets;
- avoid Android `VpnService`;
- avoid a system TUN interface;
- avoid default-route and exit-node behavior;
- use a separate tailnet node identity from the standalone Tailscale app or Termux;
- remain behind a narrow Android module interface.

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
- SSH endpoints and ordered connection routes;
- active-route selection and fallback state;
- embedded-tailnet identity, lifecycle, and diagnostics;
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

For any supported host, this workflow MUST be supported:

1. The user starts or resumes a thread in the host-local Codex TUI.
2. The TUI uses the host's daemon-backed app-server.
3. The user changes location or client surface.
4. Dealer connects to the same host through an available route, SSH, and proxy.
5. Dealer locates and resumes or rejoins the same host-qualified thread.
6. Dealer reconstructs existing history and current status.
7. The user continues through Dealer.
8. Poker may attach to Dealer's projection of that same thread.

### 4.2 Host-bound execution

A thread remains bound to the host that owns its thread store, working directory, filesystem, tools, environment, and running processes.

Poker–Dealer MUST NOT present a thread as having moved among DGX Spark, u4090, and Fold6 Termux.

A future cross-host handoff MAY create or fork a new thread on another host after repository synchronization and an explicit handoff, but the resulting locator is a different `(hostId, threadId)`.

### 4.3 Multiple observers and one writer

Several initialized clients MAY observe the same thread.

Dealer MUST tolerate the host-local TUI and Dealer being subscribed at the same time.

Poker–Dealer adopts this coordination rule:

> Multiple clients may observe one thread, but only one intended human-control surface should actively submit turns, steer, interrupt, or resolve approvals at a time.

The first implementation MAY use a soft control indicator rather than a distributed lock.

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

Dealer MAY use upstream daemon bootstrap and update flows when supported by the installed version.

The implementation MUST support Linux ARM64 for Spark and Linux x86-64 for u4090.

### 5.4 Fold6 Termux host

Fold6 Termux uses a compatible community Termux Codex distribution. Poker–Dealer MUST NOT describe it as an official OpenAI Android release.

Termux support is capability-based. Before marking it `SUPPORTED`, Dealer or the compatibility suite MUST verify:

- daemon lifecycle commands;
- successful Unix-socket binding;
- `codex app-server proxy` connectivity;
- WebSocket and app-server initialization;
- required stable thread/turn methods;
- server-request handling;
- reconnect after proxy and daemon interruption.

Dealer MUST NOT assume that the upstream standalone installer, updater, or daemon bootstrap behavior applies unchanged to Termux.

### 5.5 Availability classes

DGX Spark and u4090 are persistent hosts.

Fold6 Termux is an opportunistic mobile host. Android may suspend or stop Termux, `sshd`, the daemon, or app-server.

Dealer MUST expose recoverable states for host-app unavailable, tailnet login required, route startup, SSH unavailable, daemon stopped, proxy unavailable, initializing, connected, suspended, backoff, and error.

### 5.6 Host-local TUI consistency

The host-local Codex TUI SHOULD connect to the same daemon-backed app-server used by Dealer.

Each host setup MUST provide a documented launcher or startup check that ensures the daemon socket is ready before local TUI work, preventing split-brain use of a separate embedded app-server process.

---

## 6. Dealer-to-host connectivity

### 6.1 Route-neutral stream boundary

SSH, daemon lifecycle, proxy WebSocket, and app-server protocol code MUST NOT depend directly on Tailscale, LAN, or Android VPN APIs.

The architecture MUST expose a route-neutral abstraction equivalent to:

```kotlin
interface HostTcpDialer {
    suspend fun connect(
        host: CodexHost,
        route: HostConnectionRoute,
        port: Int,
    ): DuplexByteStream
}
```

The concrete type may differ, but route selection MUST remain below the SSH layer.

Each route provider MUST report one capability state per host route:

- supported and configured;
- supported but temporarily unavailable;
- unsupported by the installed provider;
- disabled by configuration.

Route selection MUST attempt only supported, configured routes.

### 6.2 Workstation route selection

For DGX Spark and u4090, Dealer SHOULD attempt configured routes in this order:

1. `SSH_LAN` when a trusted local endpoint is reachable;
2. `SSH_EMBEDDED_TSNET` for normal remote use;
3. `SSH_EXTERNAL_TAILSCALE` as an optional fallback.

Dealer MUST display the active route and route failures in diagnostics.

Failing one route MUST NOT delete host state or thread cache. Dealer MAY move to the next route after bounded retry and endpoint validation.

Skipped routes MUST NOT replace an actionable failure from an attempted route. Diagnostics MUST retain the route label, capability state, attempted status, and failure for every evaluated route. SSH host-key failure is terminal and MUST NOT fall through to another route.

SSH host identity MUST be pinned or verified using a user-approved trust-on-first-use flow. Host-key changes MUST require explicit review regardless of route.

### 6.3 Embedded tsnet implementation

The embedded tailnet is the target primary remote route.

Dealer MUST package the Tailscale Go library behind a dedicated Android module. The first implementation SHOULD use `gomobile bind` unless a better maintained integration is selected and documented.

The Go boundary MUST expose only narrow lifecycle and connectivity operations, such as:

- start/stop embedded node;
- return login/auth state and browser login URL;
- return node name and connection diagnostics;
- open/close a per-destination loopback TCP tunnel; or
- expose/close an authenticated loopback SOCKS5 endpoint.

Go `net.Conn` objects and Tailscale implementation types MUST NOT leak into Compose, repositories, ViewModels, SSH protocol code, or app-server protocol code.

Any loopback listener created by the embedded module MUST:

- bind only to loopback;
- use an ephemeral port where practical;
- use authentication or process-confined access when another local process could connect;
- close when the owning Dealer session ends;
- avoid logging credentials.

### 6.4 Android VPN coexistence

The embedded node MUST NOT request Android `VpnService` ownership.

Hiddify, Clash, or another application may continue owning the phone's system VPN slot.

This avoids the VPN-slot conflict but does not guarantee perfect underlay behavior. The other VPN may still affect:

- coordination-server access;
- UDP hole punching;
- direct peer connections;
- DERP relay access;
- DNS resolution;
- latency and battery use.

Dealer MUST test and report direct, relayed, degraded, and unavailable states honestly.

Where the other VPN supports per-app bypass, excluding Dealer SHOULD be documented as the preferred configuration, but it MUST NOT be presented as universally available.

### 6.5 Tailnet enrollment and identity

The embedded node MUST have its own tailnet identity, expected to be named similarly to `dealer-fold6`.

Interactive browser enrollment SHOULD be the default onboarding flow.

Auth-key onboarding MAY be implemented later, but reusable auth keys MUST NOT be persisted as plaintext.

Dealer MUST store Tailscale node state only in app-private storage and MUST support explicit logout/reset.

Tailnet policy SHOULD restrict the Dealer node to the minimum required services, initially SSH on Spark and u4090.

Tailscale identity does not replace SSH authentication or host-key verification.

### 6.6 Fold6 Termux route

Dealer and Termux are separate Android applications. Dealer MUST NOT rely on direct access to Termux-private files, process descriptors, or Unix sockets.

Loopback SSH plus `codex app-server proxy` is the required boundary for phone-local host access.

Termux loopback access SHOULD use a dedicated Dealer SSH key rather than a reusable password.

### 6.7 SSH control and proxy channels

Dealer SHOULD maintain two logical SSH uses per host:

1. short-lived lifecycle/control commands;
2. a long-lived `codex app-server proxy` stream.

Dealer MAY implement both over one multiplexed SSH connection when the library supports independent channels safely.

### 6.8 Proxy framing and initialization

`codex app-server proxy` connects to the host's Unix socket and proxies the raw WebSocket stream.

Dealer MUST implement:

- the WebSocket HTTP Upgrade handshake over the SSH exec channel;
- text-frame encoding and decoding;
- ping/pong and close handling as needed;
- fragmented/coalesced transport reads;
- bounded ingress and egress queues;
- message-size limits;
- clean cancellation and reconnect.

TCP connect, SSH connect and command execution, WebSocket upgrade, app-server request/response, turn-notification inactivity, and reconnect inspection MUST have separate configurable bounds. Cancellation MUST actively close the underlying TCP stream, SSH session, proxy channel, and WebSocket so blocked Java reads return.

A turn MUST NOT use one short whole-turn timeout. The initial implementation uses a notification-inactivity timeout that resets after each app-server notification.

The proxy stream MUST NOT be treated as newline-delimited JSON.

Immediately after WebSocket establishment, Dealer MUST send one `initialize`, wait for its response, send `initialized`, and block other product requests until initialization completes.

---

## 7. App-server compatibility strategy

Dealer SHOULD remain usable across many newer Codex releases without requiring an Android update for every host update.

Literal compatibility with every historical, nightly, experimental, community, or future breaking version is not required.

Production Dealer MUST use the stable app-server API surface by default and MUST NOT globally opt into experimental APIs merely because daemon lifecycle is experimental.

Dealer's wire layer MUST:

- parse the outer JSON-RPC shape generically;
- dispatch by method string;
- ignore unknown optional fields;
- preserve raw payloads for diagnostics;
- tolerate unknown notifications and item types;
- use string-backed values where exhaustive enums would be brittle;
- safely reject unknown server-initiated requests;
- avoid sending optional fields it does not need.

Dealer MUST record per host:

- Codex distribution;
- CLI and app-server version when reported;
- platform family and operating system;
- required method/capability results;
- compatibility state;
- last successful connection and smoke test.

Hosts MAY run different versions and distributions.

Dealer SHOULD expose `SUPPORTED`, `DEGRADED`, `UNSUPPORTED`, and `UNKNOWN` compatibility states.

The MVP stable subset includes initialize/initialized, thread list/read/start/resume/fork/archive, loaded-list when available, turn start/steer/interrupt, thread/turn/item notifications, and supported server-initiated approvals or user-input requests.

Method availability MUST be verified against the installed server rather than inferred solely from version numbers.

---

## 8. Host and thread discovery

Dealer MUST show every configured host independently with:

- display name;
- host kind and architecture;
- distribution;
- ordered routes and active route;
- availability class;
- embedded-tailnet state when relevant;
- SSH, daemon, proxy, and app-server state;
- Codex/app-server version;
- active thread count when known;
- compatibility state.

Dealer MUST support paginated thread listing and SHOULD display host, thread name/preview, details-view ID, working directory, recency, loaded/idle/active/error state, unread state, Poker attachment, and intended control surface.

Dealer SHOULD use read-only thread APIs for browsing when possible and MUST resume/rejoin before sending turns or subscribing to full live activity when required by app-server semantics.

Names and aliases MUST NOT be treated as unique identifiers. `(hostId, threadId)` remains authoritative.

---

## 9. Turn and item projection

Dealer MUST project structured app-server events rather than infer messages from terminal pixels.

Typical mappings are:

| Codex item or event | Dealer/Poker presentation |
| --- | --- |
| user message | user card |
| agent message | agent card |
| agent-message delta | growing open agent card |
| plan | plan card |
| reasoning summary | optional reasoning card |
| command execution/output | command card with expandable output |
| file change | file-change card |
| approval request | approval card |
| user-input request | question/input card |
| turn completed | status and usage |
| error | system/error card |

Full agent messages, retained command output, and file-change text MUST NOT be silently summarized or semantically truncated.

Large content MAY be split into presentation continuations, but exact reassembly MUST be possible.

A growing card MUST update without forcing the viewport to the bottom while the user reads earlier content.

Dealer MUST treat transcript deltas, authoritative item completion, turn completion, and unresolved server requests as lossless. Cosmetic progress MAY be coalesced only when final authoritative state remains reconstructable.

---

## 10. Input and semantic actions

Dealer MUST support reviewed turn start, turn steering when accepted, interruption, supported user-input answers, approval resolution, and taking control on phone.

Poker MUST use semantic actions rather than terminal key emulation. MVP actions are reviewed new-turn text, steer, interrupt, safe approve/deny, thread switching, navigation, and scrolling.

Poker MUST NOT expose generic terminal key injection as the product input model.

Morse and ASR both produce reviewable text drafts. ASR partial and final states MUST be distinguished, audio MUST not be retained by default, and selected host/thread/action MUST be shown before confirmation.

Dealer SHOULD send a client-generated user-message identifier when supported. On timeout or reconnect it MUST inspect authoritative state and MUST NOT blindly replay an uncertain `turn/start`.

Dealer MUST project a submitted prompt immediately as one user card keyed by that client identifier. Its delivery state advances monotonically from `LOCAL_PENDING` to `ACCEPTED` after a successful `turn/start`, then to `DELIVERED` when authoritative `thread/read` contains the matching `userMessage.clientId`. If acceptance cannot be established, the same card becomes `UNKNOWN`. Authoritative reconciliation MUST update that card rather than adding a duplicate.

---

## 11. Approvals and safety

Dealer MUST treat server-initiated requests as structured blocking requests requiring a response or explicit rejection.

Poker MAY resolve an approval only when the complete action and relevant scope can be displayed, the request type is understood, the decision is limited and unambiguous, the user intentionally decides, and Dealer still has the matching unresolved request.

Broad filesystem or network access, persistent grants, destructive commands, large diffs, unknown request types, and incomplete displays SHOULD require Dealer phone review.

Dealer MUST prevent duplicate resolution by Poker and Dealer. Late responses MUST be rejected locally when the request is no longer pending.

---

## 12. Reconnection and recovery

Dealer MUST treat these as normal recoverable events:

- Android process or network interruption;
- LAN availability changes;
- embedded-tailnet login, startup, direct/relay transition, or disconnect;
- third-party VPN underlay changes;
- external Tailscale route changes;
- SSH disconnect;
- proxy EOF;
- daemon restart or app-server update;
- workstation sleep/reboot;
- Termux application stop, `sshd` stop, or Android suspension;
- Poker disconnect/restart.

After host transport failure, Dealer SHOULD:

1. mark the host unavailable without deleting cache;
2. reevaluate configured routes;
3. reconnect through the best available route with bounded backoff;
4. reconnect SSH;
5. query distribution-specific daemon state;
6. ensure app-server is running when possible;
7. reopen proxy and repeat initialization;
8. list/read/resume attached threads;
9. compare authoritative state with local projection;
10. settle pending actions without blind replay;
11. resynchronize Poker.

When Termux is unavailable, Dealer SHOULD distinguish open/start Termux, restore `sshd`, start daemon, repair/update distribution, and retry after Android suspension.

When embedded tailnet is unavailable, Dealer SHOULD distinguish login required, engine startup failure, underlay unavailable, peer unreachable, relayed/degraded, and policy denied where diagnosable.

An app-server restart during an active turn MUST produce an explicit recovered, interrupted, failed, or unknown outcome based on authoritative state. Dealer MUST not fabricate completion.

One-shot Dealer operations MUST expose truthful lifecycle states. Closing all host resources after success MUST produce `COMPLETED` or `RECOVERED`, never `CONNECTED`. User cancellation MUST produce `CANCELLED`, stop the foreground operation, and remain available from Dealer UI and its foreground-service notification. Run state MUST be service-owned and remain visible across Activity recreation and service rebinding for the life of the application process.

On Poker reconnect, Dealer SHOULD send a deterministic retained snapshot followed by idempotent live updates.

---

## 13. Dealer and Poker platform decisions

### 13.1 Dealer

Dealer MUST be a native Android Kotlin application using:

- Jetpack Compose;
- coroutines and `Flow`/`StateFlow`;
- Room for hosts, thread attachment metadata, recent projection, unread state, and pending actions;
- DataStore for non-secret preferences;
- Android Keystore-backed protection for SSH credentials, host-key pins, and Poker pairing secrets;
- a foreground service while live host, embedded-tailnet, or Poker connections are enabled.

Dealer is a Codex client, not a terminal emulator.

Dealer and Termux are independent Android apps. No shared UID or private-filesystem coupling is required or allowed.

The embedded tailnet module MAY contain Go/native code, but its public Android-facing API MUST remain narrow and lifecycle-safe.

### 13.2 Poker

Poker MUST be an ordinary native Android application compatible with the observed Android 12/API 32 glasses environment.

Poker MUST use `minSdk = 28`, public Android APIs, shared pure-Kotlin modules, and a foreground service for the Dealer listener when enabled.

### 13.3 Dealer↔Poker transport

The validated topology remains:

```text
Fold6 / Dealer / hotspot owner / TCP client
                  │
                  │ authenticated bidirectional transport
                  ▼
Rokid / Poker / hotspot client / TCP listener
```

Dealer initiates. Poker listens. No CXR, ADB tunnel, or proprietary companion channel may be required.

---

## 14. Domain model direction

Shared domain code SHOULD use concepts equivalent to:

```kotlin
data class CodexHost(
    val id: String,
    val displayName: String,
    val kind: CodexHostKind,
    val architecture: HostArchitecture,
    val distribution: CodexDistribution,
    val connectionRoutes: List<HostConnectionRoute>,
    val activeConnectionRoute: HostConnectionRoute?,
    val availabilityClass: HostAvailabilityClass,
    val connectionState: HostConnectionState,
    val codexVersion: String?,
    val appServerVersion: String?,
)

data class CodexThreadLocator(
    val hostId: String,
    val threadId: String,
)
```

Wire-schema types, route/transport types, app-server projection types, and Compose UI types SHOULD remain separate.

Raw JSON, Go networking types, and Tailscale internals MUST NOT leak into Compose UI classes.

---

## 15. Persistence and secrets

Dealer MUST persist:

- host IDs and display names;
- host kind, architecture, distribution, ordered routes, active-route observations, and availability class;
- LAN and tailnet endpoint configuration;
- SSH endpoint configuration and approved host-key pins;
- daemon/app-server capability observations;
- attached thread locators;
- local aliases and presentation policy;
- recent projection and unread state;
- pending actions with idempotency metadata;
- Poker pairing and synchronization state;
- embedded-tailnet non-secret preferences and diagnostics.

Dealer MUST NOT persist plaintext private keys, passwords, bearer tokens, ChatGPT credentials, Tailscale auth keys, node/state keys, OAuth tokens, or local proxy credentials in Room or DataStore.

Tailscale engine state MUST remain in Dealer-private storage. Secrets requiring encryption MUST use Android Keystore-backed protection or the embedded library's protected state mechanism.

Codex authentication remains owned by Codex on each host.

---

## 16. Milestones

### M0 — Architecture reset

Complete when the tmux backend is removed and all default-branch guidance consistently uses Codex host/thread terminology.

### M1 — Transport-neutral one-workstation app-server slice

Complete when Dealer:

1. models the first available persistent Linux workstation with ordered route metadata, currently u4090 while DGX Spark is unavailable;
2. exposes a route-neutral TCP/duplex-stream boundary;
3. connects through one available route, preferably trusted LAN;
4. establishes SSH;
5. queries/starts daemon;
6. opens proxy WebSocket;
7. completes initialize/initialized;
8. lists and resumes a thread;
9. renders history;
10. starts and streams one turn;
11. reconnects without duplicate input.

External Tailscale MAY be used temporarily if LAN is unavailable, but M1 MUST NOT hardwire the SSH layer to it.

Do not include Poker, Morse, ASR, terminal features, or broad experimental APIs in M1.

M1 completed on u4090 through trusted-LAN SSH on 2026-07-27. The implementation and recorded evidence are transport-neutral; the proof did not claim embedded-tsnet or Fold6 validation. See `docs/evidence/u4090-m1-2026-07-27.md`. Connection lifecycle hardening required before M1T is recorded in `docs/evidence/u4090-m1-hardening-2026-07-27.md` so the original proof limitations remain intact.

### M1T — Embedded tsnet Android spike

Complete when:

- a pinned Tailscale Go dependency builds reproducibly into an Android ARM64 AAR/module;
- Dealer can enroll a distinct tailnet node without `VpnService`;
- Dealer can open an SSH-capable loopback tunnel or authenticated SOCKS route to the selected workstation;
- the M1 SSH/app-server stack runs through embedded tsnet without code duplication;
- node state survives Dealer restart;
- logout/reset works;
- logs and storage pass secret review;
- Hiddify/Clash coexistence is tested on Fold6;
- direct and DERP-relayed behavior is recorded;
- foreground-service, battery, reconnect, and route-fallback behavior is recorded.

### M2 — remaining workstation and route continuity

Complete when:

- Spark and u4090 both work;
- LAN and embedded-tsnet routes are selectable and observable;
- optional external-Tailscale fallback works or is explicitly disabled;
- host-local TUI and Dealer observe the same daemon-backed thread;
- Dealer exposes safe take-control behavior;
- host versions may differ without breaking core use.

### M2T — Fold6 Termux host

Complete when loopback SSH, distribution-specific daemon behavior, proxy/app-server APIs, local TUI coexistence, one turn, and Android suspension recovery work with a tested community build.

### M3 — Structured actions and approvals

Complete when Dealer supports command/file-change presentation, approvals/user input, steering/interruption, lossless request handling, and duplicate-resolution prevention.

### M4 — Dealer↔Poker synchronization

Complete when Poker pairs, lists attached host-qualified threads, switches conversations, reads retained/live output, reconnects deterministically, and preserves scroll position during live growth.

### M5 — Wearable input

Complete when Poker supports reviewed Morse/ASR, start/steer/interrupt, safe limited approvals, and escalation to Dealer.

### M6 — Production hardening

Complete when:

- lifecycle/power tests pass on Spark, u4090, Fold6 Termux, Dealer, and Rokid;
- embedded-tailnet and daemon/app-server update interruption are handled;
- compatibility and recovery diagnostics are visible;
- security review covers SSH, Tailscale state, native bridge, Android sandbox boundaries, secrets, Poker pairing, and approval safety;
- end-to-end real-hardware acceptance is recorded.

---

## 17. Immediate next job

A fresh Codex worker MUST begin with M1T, the embedded-tsnet Android spike defined above. It MUST reuse the M1 route-neutral stream, SSH, proxy WebSocket, app-server, projection, and reconnect implementation without introducing transport dependencies above the dialer boundary.

The worker MUST NOT implement a system VPN, restore a bridge, integrate tmux as backend, add Poker networking, build a terminal, or start Termux-specific lifecycle behavior during M1T.
