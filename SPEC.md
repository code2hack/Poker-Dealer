# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 9
**Date:** 2026-07-28
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

### 2.4 Turn, item, request, connection, and card

- A **turn** is one user request and the associated Codex execution/response lifecycle.
- An **item** is structured user input or Codex output within a turn, including agent messages, plans, reasoning summaries, commands, command output, file changes, and errors.
- A **server request** is a server-initiated blocking request that requires a client response or safe rejection and may reference one or more turn items.
- A **server-request locator** is the JSON-RPC `requestId` qualified by `hostId` and the observed app-server generation. `itemId` and command `approvalId` are request context, not request identity.
- A **request resolution state** advances monotonically from `PENDING` to `RESPONDING` to `RESOLVED`, or to `UNKNOWN` when Dealer cannot establish whether the response was accepted.
- A **client connection** is one initialized app-server JSON-RPC connection. It is disposable and MUST NOT be used as durable identity.
- A **Dealer projection** is Dealer's UI-oriented host/thread/turn/item state.
- A **card** is a Dealer/Poker presentation unit derived from that projection. Cards are not authoritative Codex records and MUST be rebuildable.
- A **card pile** is the ordered card history for one attached host-qualified thread.
- A **thread work state** is `BUSY`, `ATTENTION_REQUIRED`, or `READY`; host availability is a separate dimension.

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
- process-local composition state not yet acknowledged by Dealer;
- a temporary captured photo until Dealer durably acknowledges it;
- the user's direct semantic action before Dealer accepts it.

Poker MUST NOT become the authoritative store for thread history, durable
drafts or photo assets, or delivery state. Only its pairing identity persists;
Dealer resynchronizes all other recoverable state after Poker restarts.

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
8. Dealer may project that manually attached thread to Poker.

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

Thread attachment and soft-control claims are separate and scoped by `(hostId, threadId)`. Attachment starts in observer state. Dealer MAY hold control claims for several different threads concurrently, but starting a turn (`turn/start`), steering, interruption, and user-initiated request resolution for a thread require that thread's Dealer claim. Protocol-required fail-closed rejection, timer-driven no-answer, and user-confirmed Disconnect cancellation are safety responses that do not acquire a control claim. Attach, Detach, and every thread-management action are Dealer-only; Poker only receives the resulting projection.

If Dealer holds a soft-control claim and observes a newly started turn that it cannot correlate to a Dealer-originated action, it MUST revoke its claim, identify the thread as active from another client, and remain an observer. Steering, interruption, and user-initiated request resolution then require an explicit new Dealer control claim.

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

Karing or another third-party application may continue owning the phone's system VPN slot.

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

### 6.9 Multi-host session manager

Host connection and disconnection are explicit Dealer user actions represented by a durable per-host connection intent.

- **Enabled:** Dealer maintains one initialized app-server connection for that host, multiplexes every attached thread over it, and retries accidental loss indefinitely with capped backoff and phase-specific failure reporting.
- **Disabled:** Dealer closes that host's resources and MUST NOT reconnect until the user enables it again.

Dealer MUST support enabled sessions for Spark, u4090, and Fold6 Termux concurrently. It MUST NOT open one SSH, proxy, or app-server connection per thread.

Connection intent survives Dealer process death and is restored when Dealer or its service is next started. M3 MUST NOT add phone-boot autostart. Automatic reconnect MUST initialize once, then reconcile and resubscribe every attachment without blindly replaying a turn or request response.

---

## 7. App-server compatibility strategy

Dealer SHOULD remain usable across many newer Codex releases without requiring an Android update for every host update.

Literal compatibility with every historical, nightly, experimental, community, or future breaking version is not required.

Production Dealer MUST use the stable app-server API surface by default and MUST NOT globally opt into experimental APIs merely because daemon lifecycle is experimental.

Dealer's wire layer MUST:

- parse the outer JSON-RPC shape generically;
- dispatch by method string;
- ignore unknown optional fields;
- preserve unknown non-secret payloads for diagnostics;
- tolerate unknown notifications and item types;
- use string-backed values where exhaustive enums would be brittle;
- safely reject unknown server-initiated requests;
- avoid sending optional fields it does not need.

Secret-bearing payloads are an exception to diagnostic retention. Dealer MUST redact or discard raw `config/read` responses and any other payload that may expose provider configuration, headers, tokens, or credentials. Only the provider identifiers and display labels required by the UI may persist.

Dealer MUST record per host:

- Codex distribution;
- CLI and app-server version when reported;
- platform family and operating system;
- required method/capability results;
- compatibility state;
- last successful connection and smoke test.

Hosts MAY run different versions and distributions.

Dealer SHOULD expose `SUPPORTED`, `DEGRADED`, `UNSUPPORTED`, and `UNKNOWN` compatibility states.

The M3 production subset includes initialize/initialized; `thread/list`, `thread/loaded/list`, `thread/read`, `thread/start`, `thread/resume`, `thread/fork`, `thread/name/set`, `thread/archive`, `thread/unarchive`, `thread/delete`, and `thread/unsubscribe`; `turn/start`, `turn/steer`, and `turn/interrupt`; `model/list`, `config/read`, and `configRequirements/read`; required thread/turn/item notifications; and only the accepted server-request families.

`item/tool/requestUserInput` is M3's only explicitly accepted unstable request-family exception. Dealer MUST gate it with a per-host/version compatibility fixture and live qualification evidence. Supporting it MUST NOT enable broad experimental mode or authorize any other experimental method.

M3 also accepts the unstable `thread/list.ancestorThreadId` filter solely for safe Archive/Delete cascade preflight. Dealer MUST gate that field with a per-host/version compatibility fixture and live qualification evidence. This exception does not authorize any other experimental `thread/list` field or method.

M3 exposes Start, Fork, Rename, Archive, Restore, and Delete as Dealer lifecycle actions. Listing, reading, loaded-list inspection, resume, and unsubscribe are internal integration behavior. Goals, general metadata editing, manual compaction, deprecated rollback, shell/process surfaces, experimental paginated item/turn APIs, and broad experimental app-server methods are deferred.

Dealer MUST NOT implement a generic slash-command parser or intercept `/model`. Slash-equivalent product behavior exists only where this specification defines a native Dealer action.

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

Dealer discovers threads on configured, enabled, reachable hosts; it does not discover machines. For each such host, Dealer MUST exhaust cursor pagination for the relevant active or archived `thread/list` view and inspect `thread/loaded/list` when supported. It MUST include the host's supported user-facing `cli`, `vscode`, and `appServer` source kinds rather than relying on server defaults that can omit Dealer-created threads; non-user exec and subagent threads are excluded by default. Discovery runs on connection, explicit refresh, and reconciliation events; M3 MUST NOT continuously poll every host globally.

Archive/Delete preflight is a separate internal query. It MUST exhaust both active and archived `thread/list` pages filtered by `ancestorThreadId`, include all supported source kinds, and use no working-directory, model-provider, or search filter. Dealer MUST NOT infer a complete cascade from the user-facing discovery list. If the qualified filter is unavailable, Archive and Delete are disabled with an explanation.

Dealer SHOULD display host, thread name/preview, details-view ID, working directory, recency, loaded/idle/active/error state, unread state, Poker attachment, and intended control surface.

Dealer SHOULD use read-only thread APIs for browsing when possible and MUST resume/rejoin before sending turns or subscribing to full live activity when required by app-server semantics.

Thread attachment and detachment are explicit Dealer actions. Poker Hide/Wake changes presentation only and MUST NOT attach, detach, subscribe, unsubscribe, connect, or disconnect anything. A host disconnection preserves attachment metadata and cached piles while marking availability separately; unavailable piles remain manually viewable but are excluded from automatic focus.

Detach is allowed while a thread is `BUSY`; its remote turn continues. Detach is blocked while the thread is `ATTENTION_REQUIRED` until every known blocking request is answered, declined, cancelled, or interrupted. Detach removes the active pile and unsubscribes that thread from Dealer's host connection, but does not delete, archive, or interrupt the host thread.

New Thread MUST use either a working directory previously observed in that host's thread metadata or a manually entered absolute path in the host's native syntax. Fork defaults to the source thread's working directory and Resume defaults to the stored thread's working directory; both expose the same observed-path or manual-absolute-path choices. If the user changes the path, Dealer MUST reread effective configuration for that path before confirmation. M3 MUST NOT include a remote filesystem browser.

Start and Fork MUST auto-attach their result and grant initial Dealer control. Permanent Delete MUST show a phone confirmation containing host, name or preview, thread ID, working directory, descendant-cascade warning, and irreversibility warning.

Names and aliases MUST NOT be treated as unique identifiers. `(hostId, threadId)` remains authoritative.

---

## 9. Turn and item projection

### 9.1 Structured cards

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
| command/file server request | approval card |
| structured user-input server request | question/input card |
| turn completed | status and usage |
| error | system/error card |

Full agent messages, retained command output, and file-change text MUST NOT be silently summarized or semantically truncated.

Large content MAY be split into presentation continuations, but exact reassembly MUST be possible.

A growing card MUST update without forcing the viewport to the bottom while the user reads earlier content.

Dealer MUST treat transcript deltas, authoritative item completion, turn completion, and unresolved server requests as lossless. Cosmetic progress MAY be coalesced only when final authoritative state remains reconstructable.

Command and file-change cards are collapsed by default. A collapsed command card shows command, working directory, status, and exit code; a collapsed file-change card shows affected paths and count. Expansion MUST expose the complete app-server-provided retained output or diff.

`item/started` opens the corresponding live card. Command output deltas append in wire order without changing the reader's viewport. `turn/diff/updated` refreshes the aggregate diff when supported. `item/completed` is authoritative for final item content and status, and turn completion records whether the containing work completed, was interrupted, or failed. Dealer MUST NOT enable approval when the relevant command, path scope, output, or diff is incomplete.

### 9.2 Thread work state and Poker pile metadata

Dealer derives exactly one work state for each discovered or attached host-qualified thread whose authoritative state it can read:

- `BUSY` while an active turn is progressing without a known user block;
- `ATTENTION_REQUIRED` while an active turn has one or more unresolved user requests;
- `READY` when no active turn prevents a new prompt.

Host availability is orthogonal. A failed or interrupted turn transitions to `READY` with a prominent outcome card rather than inventing a fourth work state.

If current work state cannot be established for a discovered thread, Dealer shows that limitation separately and disables any action that requires `READY` until reconciliation; it does not invent a fourth work state.

M3 MUST produce deterministic pile ordering and focus metadata; M4 renders it on Poker. Attached piles are ordered left-to-right as:

```text
BUSY | ATTENTION_REQUIRED | READY
```

Poker MUST NOT present a separate attached-thread list. The attached
host-qualified card piles themselves form one horizontal line in the
deterministic order above. `RIGHT` moves to the next pile and `LEFT` moves to
the previous pile. At the first or last pile, movement toward the missing
neighbor stops; pile navigation never wraps around.

Busy piles are ordered by Busy activity from oldest to newest. A transition into
`BUSY` or an accepted Send or Steer refreshes that pile's Busy activity and
moves it to the right edge of the Busy group, including a Steer accepted while
the pile was already Busy. Streaming output alone MUST NOT reorder it. Piles
without observed Busy activity and equal activity values use stable attachment
order.

Within `ATTENTION_REQUIRED` and `READY`, the oldest transition into that state
comes first; equal transition times use stable attachment order. Automatic
focus priority is the first Attention pile, then the first Ready pile. Busy
piles remain reachable through manual navigation.

Work-state reordering MUST preserve visible focus by
`(hostId, threadId)`, not by horizontal index. The focused pile may move and
its neighbors may change, but a visible HUD remains on that pile. Automatic
focus selection applies when the HUD is hidden or when another explicit rule
advances focus.

When the HUD is already visible, a newly Attention or Ready pile MUST NOT steal focus; Poker indicates the state change in place. When the HUD is hidden, a new transition into Attention or Ready wakes it and selects the highest-priority eligible pile. Existing eligible piles MUST NOT immediately undo a user's manual Hide.

Poker MUST offer Manual Hide in every thread work state. Manual Wake selects the oldest Attention pile, then the oldest Ready pile. If every available pile is Busy, it restores the last viewed attached pile when possible, otherwise the first attached Busy pile.

After the focused pile accepts a prompt or steer, it is or remains Busy and the
HUD stays visible on the same `(hostId, threadId)`. Focus remains inside that
pile's now-empty composer input; Poker MUST NOT advance to an Attention or Ready
pile or hide merely because the action was accepted.

Failed and interrupted outcomes wake and focus like Ready. Unavailable piles
retain cached history for manual viewing but are excluded from automatic focus.

---

## 10. Input and semantic actions

Dealer MUST support reviewed turn start, turn steering when accepted, interruption, supported user-input answers, approval resolution, and taking control on phone.

### 10.1 Canonical Poker operations

Poker interaction logic MUST consume source-neutral operations rather than
raw Rokid or Bluetooth events. The initial Rokid mappings are:

| Operation | Rokid built-in interaction |
| --- | --- |
| `DOWN` | single-finger swipe forward on the touch panel |
| `UP` | single-finger swipe backward on the touch panel |
| `RIGHT` | double-finger swipe forward on the touch panel |
| `LEFT` | double-finger swipe backward on the touch panel |
| `FN` | function button |
| `TAP` | single-finger tap on the touch panel |
| `TAPTAP` | dual-finger tap on the touch panel |

A Bluetooth remote adapter MAY map its messages to the same operations. Raw
controller messages and vendor event types MUST NOT leak into pile, card, or
input-mode logic.

While the HUD is visible, `TAPTAP` performs Manual Hide. While it is hidden,
`TAP` performs Manual Wake using the accepted Attention-then-Ready focus
priority. M4 does not require a separate on-screen Hide control.

While the HUD is visible in navigation mode, `TAP` toggles the focused card's
collapsed or expanded details when that card supports expansion. It does
nothing to a card without an expandable presentation.

`FN` is reserved and has no behavior in M4. Its short-press and hold behavior
begin in M5.

### 10.2 Poker pile browsing in M4

One card pile is treated as zero or more history cards followed by its newest
bottom card and then the thread composer when ordinary input is available.
Every unresolved server-request card owns its own request panel immediately
after that card, so concurrent requests remain independently reachable.

In navigation mode, `DOWN` and `UP` normally scroll the focused card. If the
HUD bottom is already at that card's end, another `DOWN` jumps to the next
card's end rather than its start. If the HUD top is already at that card's
start, another `UP` jumps to the previous card's start rather than its end. A
card shorter than the HUD is always at both its start and end, so these
operations immediately perform the corresponding card jump. An unresolved
request card is the exception: `DOWN` at its end enters that card's request
panel instead of jumping to the next card.

At the oldest card's start, `UP` stops. At the bottom card's end, `DOWN` stops
when no composer or request panel is available. Vertical pile navigation never
wraps around.

At the end of the bottom card, `DOWN` moves focus into the composer when it is
available. Entering a composer or request panel starts input mode and shows a
blinking cursor or highlighted option. When input focus is back at the first
cursor or option position, `UP` returns to navigation mode at the end of the
owning card, so one subsequent `DOWN` re-enters the same input surface.
At the final cursor or option position, `DOWN` stops; input mode never wraps
or jumps directly into another card.

M4 implements only this input-mode presentation, focus, and navigation seam.
It MUST NOT enter or submit text, start or steer a turn, or resolve a pending
request from Poker. M5 activates those semantic actions after their review and
safety rules are implemented.

Switching piles preserves each pile's focused card, scroll offset, and
navigation or input mode in Poker process memory. If synchronized state
removes or changes the focused card, composer, or request panel so the
saved position is no longer valid, Poker exits stale input focus and
re-anchors that pile to its newest bottom card.

Dealer's composer is state-sensitive and requires the thread's Dealer control claim:

- `READY` labels Send as a new turn and calls `turn/start`.
- `BUSY` labels Send as **Steer active turn** and calls `turn/steer`.
- `ATTENTION_REQUIRED` disables ordinary prompt submission until the blocking request is resolved or the turn is interrupted.

A steer MUST include the currently observed active `turnId` as `expectedTurnId`. A rejected, unsupported, or precondition-mismatched steer MUST be reported visibly, trigger authoritative reconciliation, and MUST NOT fall back to `turn/start`.

Interrupt is an explicit one-tap action bound to the currently observed `turnId`. The first tap locks the action until its outcome is known. Dealer MUST NOT queue, retarget, or replay Interrupt across reconnect; without a confirmed matching live turn it reports unavailable and reconciles authoritative state.

Poker MUST use semantic actions rather than terminal key emulation. MVP actions are reviewed new-turn text, steer, interrupt, safe approve/deny, thread switching, navigation, and scrolling.

Poker MUST NOT expose generic terminal key injection as the product input model.

Morse and ASR both produce reviewable text drafts. ASR partial and final states MUST be distinguished, audio MUST not be retained by default, and selected host/thread/action MUST be shown before confirmation.

Dealer SHOULD send a client-generated user-message identifier when supported. On timeout or reconnect it MUST inspect authoritative state and MUST NOT blindly replay an uncertain `turn/start`.

Dealer MUST project a submitted prompt immediately as one user card keyed by that client identifier. Its delivery state advances monotonically from `LOCAL_PENDING` to `ACCEPTED` after a successful `turn/start`, then to `DELIVERED` when authoritative `thread/read` contains the matching `userMessage.clientId`. If acceptance cannot be established, the same card becomes `UNKNOWN`. Authoritative reconciliation MUST update that card rather than adding a duplicate.

Dealer MUST maintain one durable unsent draft per `(hostId, threadId)`. Thread switching, automatic focus changes, HUD visibility, route or host disconnection, Dealer restart, Detach, and Archive MUST preserve that draft. Dealer MUST clear it only after app-server accepts the exact outbound action; uncertain acceptance MUST lock the draft against duplicate submission until authoritative reconciliation. Confirmed thread deletion permanently removes its draft.

---

## 11. Approvals and safety

Dealer MUST treat server-initiated requests as structured blocking requests requiring a response or explicit rejection.

M3 supports only:

1. command-execution approval;
2. file-change approval;
3. structured user-input questions.

An unsupported, unknown, or malformed server request MUST be safely rejected. A temporarily incomplete request keeps its controls disabled while Dealer performs bounded authoritative reread or reassembly; if complete review material still cannot be obtained, Dealer safely rejects it so Codex cannot wait forever for a response Dealer cannot provide.

Requests render as inline cards in their host-qualified thread pile, never as one global blocking modal. Several threads and several requests within a thread may require attention concurrently, and the user may resolve them in any order.

Dealer MUST key each pending card and response action by the server-request locator, never by `itemId`. A command `approvalId` remains part of its request fingerprint because one item may produce multiple approval callbacks. Dealer advances its local app-server generation whenever daemon status proves replacement or continuity cannot be established. Across a reconnect, it reconciles a reissued request only when the same-ID behavior is proven for that host/version and the request method and normalized scope fingerprint also match.

Dealer's safe semantic approval set is:

- accept once;
- accept for the current session;
- decline;
- cancel;
- accept a server-proposed execpolicy amendment after rendering the complete amendment.

For command approval, Dealer MUST intersect this set with `availableDecisions` when the connected server supplies that optional field. When it is absent, Dealer MAY expose only response choices proven for that host protocol by compatibility fixtures; an execpolicy amendment is offered only when the request contains the exact proposal. The current file-change request has no runtime `availableDecisions` field, so its adapter uses only the proven `accept`, `acceptForSession`, `decline`, and `cancel` response union. M3 MUST NOT include an amendment editor. If no safe response is known, Dealer rejects the request.

Command-approval `command` and `cwd` fields are nullable. A request may instead carry an authoritative network scope, in which case Dealer MUST completely render its network destination host and protocol before offering only the proven `accept`, `acceptForSession`, `decline`, or `cancel` choices allowed by `availableDecisions` when present. A complete network-only request MUST NOT be rejected merely because command or working directory is absent; a request with neither completely renderable command scope nor network scope fails closed. M3 does not offer network-policy amendments.

Every request card follows the monotonic resolution state defined in section 2.4. The first local action wins and moves `PENDING` to `RESPONDING`; other controls lock immediately. The card remains visible until authoritative `serverRequest/resolved` moves it to `RESOLVED`. A lost or ambiguous response becomes `UNKNOWN`, is never replayed, and is settled only by authoritative thread, turn, or request reconciliation. Dealer MAY rely on a reissued request only for a host/version where capability checks and live evidence prove that behavior.

Dealer MUST render every structured question in wire order and support multiple questions, option lists, free-text questions with null options, and `isOther` free-text answers. An `isSecret` answer uses masked, non-durable input and MUST NOT enter projection history, drafts, cache, diagnostics, logs, notifications, or a request fingerprint; Dealer retains only its resolved status. A valid shape that cannot be displayed or answered completely is safely rejected.

For a structured question with non-null `autoResolutionMs`, Dealer shows a countdown and responds with the current no-answer payload `{ "answers": {} }` when it expires; it MUST NOT invent a choice or free-text answer. This unstable request behavior requires a compatibility fixture and live qualification proof for the host/version. A null timeout waits until the user answers, sends no answer, interrupts the turn, or the server clears the request. Resolved question cards remain in history with answered, no-answer, or auto-resolved status.

Resolved command and file approval cards remain in history with the known decision. If another client resolved a request and app-server does not reveal the decision, Dealer shows **resolved elsewhere** rather than guessing.

M3 resolves requests only in Dealer. Beginning in M5, Poker MAY resolve an approval only when the complete action and relevant scope can be displayed, the request type is understood, the decision is limited and unambiguous, the user intentionally decides, and Dealer still has the matching unresolved request.

Broad filesystem or network access, persistent grants, destructive commands, large diffs, unknown request types, and incomplete displays SHOULD require Dealer phone review.

Dealer MUST prevent duplicate resolution by every surface. Late responses MUST be rejected locally when the request is no longer pending.

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

Automatic route or transport recovery preserves the same-process Dealer control claim. Dealer process death and explicit host Disconnect reset every claim on that host to observer while preserving durable connection intent, attachments, drafts, cached piles, and unread state as applicable.

Explicit Disconnect is different from accidental loss. If known requests are pending, Dealer MUST show the affected host and request scope and obtain confirmation. For each command or file request it sends `cancel` at most once when that response is proven valid for the host protocol; for each structured question it sends the current no-answer response `{ "answers": {} }` at most once. If no safe response exists, Dealer MUST interrupt each distinct confirmed matching turn at most once. Dealer waits for a short bounded resolution window, then closes the host connection regardless. An unconfirmed response or interrupt becomes `UNKNOWN` and MUST NOT be replayed; the next Enable reconciles it. Busy turns that are not blocked on those requests continue on the host.

While a host is disconnected, attachment and work-state cache remain intact and availability is shown separately. Re-enabling the host creates one new initialized connection, rereads/resumes all attachments, and reconciles request and turn state from authoritative state and `serverRequest/resolved` notifications. It MAY consume reissued requests with the same request IDs only where compatibility fixtures and live evidence prove that behavior. It never blindly replays `turn/start`, `turn/steer`, Interrupt, or a request response.

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
- durable enabled/disabled host connection intent;
- LAN and tailnet endpoint configuration;
- SSH endpoint configuration and approved host-key pins;
- daemon/app-server capability observations;
- attached thread locators;
- per-thread unsent drafts;
- local aliases and presentation policy;
- recent projection and unread state;
- pending actions with idempotency metadata;
- Poker pairing and synchronization state;
- embedded-tailnet non-secret preferences and diagnostics.

Dealer MUST retain all app-server-provided command output and file-change text for attached threads in durable, Dealer-private, file-backed storage rather than depending on process memory. This retained projection MUST survive route loss, host disconnection, and Dealer process restart. Cache belonging only to detached or archived threads MAY be evicted because app-server remains authoritative and can be reread; unresolved server requests and outbound actions whose acceptance is unknown MUST NOT be evicted.

Dealer MUST NOT silently truncate retained content. If storage or reassembly fails, the affected card MUST be marked incomplete and any approval that requires the missing content MUST fail closed until complete review material is restored.

Transcript projections, output, diffs, drafts, and pending-action records MUST remain in Dealer-private storage excluded from Android backup and MUST NOT be written to logs. M3 MUST demonstrate recovery after Dealer process death and, once the user next starts Dealer, after a same-phone Android reboot. Corrupt or incomplete cache derived from app-server SHOULD be discarded and rebuilt from authoritative host state when possible.

M3 does not guarantee recovery after app uninstall, Clear data, factory reset, unrecoverable device storage failure, or loss of the phone. Host-retained thread state remains rediscoverable; Dealer-only drafts and uncertain local actions are unrecoverable after loss of Dealer-private storage. Device migration, cloud backup, and a separate Dealer content-encryption/passcode layer are outside M3.

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
- Karing or the user's current third-party VPN is tested on Fold6;
- direct and DERP-relayed behavior is recorded;
- foreground-service, battery, reconnect, and route-fallback behavior is recorded.

M1T completed on the real Fold6 on 2026-07-28. Direct routing and third-party-VPN coexistence are recorded in `docs/evidence/fold6-m1t-routing-2026-07-28.md`; lifecycle, fallback, cancellation, genuine DERP relay, and unplugged idle/direct/relayed battery observations are recorded in `docs/evidence/fold6-m1t-lifecycle-2026-07-28.md`. The evidence retains its documented single-device and short-duration limits.

### M2 — workstation and route continuity

Complete when:

- Spark and u4090 both work;
- LAN and embedded-tsnet routes are selectable and observable;
- optional external-Tailscale fallback works or is explicitly disabled;
- host-local TUI and Dealer observe the same daemon-backed thread;
- Dealer exposes safe take-control behavior;
- host versions may differ without breaking core use.

The shared workstation slice works against daemon-backed local-TUI threads on Spark and u4090, including LAN-to-embedded-tailnet fallback and host-qualified soft control. See `docs/evidence/workstations-m2-2026-07-28.md`. Both hosts used Codex `0.145.0`. On 2026-07-28 the user explicitly accepted progression to M3 without the separate live mixed-version proof. That proof remains deferred compatibility evidence; broad mixed-version compatibility is not claimed.

### M2T — Fold6 Termux host

Complete when loopback SSH, distribution-specific daemon behavior, proxy/app-server APIs, local TUI coexistence, one turn, and Android suspension recovery work with a tested community build.

M2T completed on the tested Fold6 community build on 2026-07-28. Capability, local-TUI
coexistence, and the first turn are recorded in `docs/evidence/fold6-m2t-turn-2026-07-28.md`;
bounded proxy, `sshd`, daemon, and Termux-process recovery without replay is recorded in
`docs/evidence/fold6-m2t-recovery-2026-07-28.md`.

### M3 — Structured actions and approvals

Complete when Dealer supports the multi-host session manager; configured-host thread discovery; manual attachment and control; Dealer-only lifecycle actions; deterministic work-state projection; complete command/file presentation; the three accepted request families; steering/interruption; Start/Fork/Resume settings; phone notifications; recovery; and duplicate prevention.

Dealer's Start, Fork, and Resume flows MUST expose working directory, provider, model, and permission settings, plus reasoning effort when the selected catalog model advertises choices. Provider, model, permission, and reasoning-effort values inherit app-server state unless explicitly overridden; working-directory behavior follows the selected action and section 8.

For `thread/start`, `thread/fork`, and `thread/resume`, Dealer MUST inherit app-server provider, model, and permission settings by default and omit each override unless the user explicitly changes it. An invalid or unavailable explicit override MUST fail visibly; Dealer MUST NOT silently substitute another provider, model, or permission mode.

A successful `thread/resume` response does not prove that explicit overrides took effect. Dealer MUST verify that every explicitly requested override is present or otherwise authoritatively observable and equals the effective setting. A missing, unverifiable, or mismatched value is unavailable: Dealer reports it, unsubscribes that thread from the Dealer connection, leaves it detached, and does not grant control. It MUST NOT evict another subscriber; the user may unload the thread from other clients and retry.

For provider selection in each flow, Dealer MUST call `config/read` for the action's effective host and working directory. The picker MUST show the effective host default and each configured `model_providers` entry using its `name` as the label and table key as the protocol ID; a missing name falls back to the ID. Dealer MUST retain or log neither provider configuration nor credentials beyond the identifiers and labels required by the picker. Dealer MUST allow exact provider-ID entry when discovery is unavailable or incomplete.

The model control MUST remain editable and inherit the effective model unless the user supplies an override. Dealer MAY offer `model/list` results, including a host-configured model catalog, as suggestions, but it MUST exhaust `nextCursor`, submit and persist each entry's exact `model` wire value, and MUST NOT treat that non-provider-scoped catalog as proof that a model is supported by the selected provider. Dealer MUST NOT query a provider's model endpoint directly; it submits the exact selected combination to app-server and surfaces any rejection without fallback.

When the selected catalog model advertises reasoning-effort choices, Dealer MUST expose them and MUST allow only those choices. An explicit selection is retained as a pending per-thread choice and applied to the next `turn/start`; it is not sent through thread Start/Fork/Resume or `turn/steer`. An unlisted custom model inherits host reasoning settings.

The host owns provider definitions, credentials, model runtimes, and provider egress routing. M3 MUST NOT include a provider/credential/runtime editor, a per-thread provider proxy setting, or UI for service tier, personality, reasoning summary, instructions, or other inherited host configuration.

Browsing a discovered stored thread MUST remain non-subscribing through `thread/read`. Choosing **Attach** opens that thread's Resume settings, and confirmation calls `thread/resume` before adding the host-qualified thread to Dealer's attachment set. Recovery after an accidental connection loss MUST resume existing attachments silently with inherited settings and MUST NOT reopen the Resume settings.

Confirming New Thread MUST immediately call `thread/start` with the selected host, working directory, and explicit overrides, then auto-attach the returned empty `READY` thread, grant initial Dealer control, and open its composer. The first prompt remains a separate reviewed `turn/start`; M3 MUST NOT present thread creation and first-turn acceptance as one atomic operation.

Attaching with inherited settings is an observer action and MAY occur in any thread work state. Supplying a provider, model, or permission override during Resume is a control-bearing action: it MUST require an explicit Dealer control claim and a `READY` thread. Resume settings MUST be read-only for `BUSY` and `ATTENTION_REQUIRED` threads.

Rename MAY be used in any work state. Fork MUST be disabled unless the source thread is `READY`; Restore applies only to archived threads. Before Archive or Delete, Dealer MUST establish the complete cascade scope and current state from authoritative metadata. The action is disabled if any affected thread is not `READY`, any affected thread is marked ephemeral, or the cascade scope or state is unknown. M3 MUST NOT fork a partial active turn or archive/delete active or user-blocked work.

Because app-server Archive may also archive spawned descendants, Dealer MUST require confirmation when descendants exist and MUST show the host, selected thread, descendant count, and cascade warning. Dealer MUST reconcile the actual archived set from server notifications. Restore operates on only the selected archived thread; archived descendants remain separately restorable.

After server-confirmed Archive, Dealer MUST remove every actually archived thread from its attachment set while keeping its history available through the Archived view. Restore MUST leave a thread detached until the user explicitly attaches it again. After server-confirmed Delete, Dealer MUST remove the deleted thread and descendants from attachments and permanently remove their Dealer-local cached projections.

M3 permission selection MUST offer only these presets:

- **Host default:** omit permission overrides.
- **Ask on phone:** `sandbox = workspace-write`, `approvalPolicy = on-request`, and `approvalsReviewer = user`.
- **Auto review:** `sandbox = workspace-write`, `approvalPolicy = on-request`, and `approvalsReviewer = auto_review`.
- **Read-only:** `sandbox = read-only` and `approvalPolicy = never`.
- **YOLO:** `sandbox = danger-full-access` and `approvalPolicy = never`.

M3 MUST NOT expose a granular permission-policy editor. Dealer MUST use stable `configRequirements/read` to disable with an explanation any preset whose sandbox mode or approval policy is known to be disallowed. A null or unadvertised constraint is unknown, not unsupported. Because stable M3 does not consume experimental reviewer constraints, Dealer submits the exact selected reviewer value when reviewer support is unadvertised and surfaces any rejection without fallback or remapping.

While any host connection intent is enabled, M3 MUST keep the shared multi-host session manager active through Dealer's Android foreground service when the UI is backgrounded or the screen is off. It MUST use one silent ongoing notification that opens Dealer.

When Dealer is backgrounded or the screen is off, a genuine new transition to `ATTENTION_REQUIRED` MUST produce a high-priority Android notification and a genuine new transition to `READY` MUST produce a normal-priority Android notification. Initial baseline and reconnect reconciliation MUST NOT notify. Notifications MUST be keyed by `(hostId, threadId)`, update rather than duplicate, and open the exact thread or pending request. M3 MUST NOT expose inline approval, denial, reply, or interrupt actions in Android notifications; those actions require Dealer's complete in-app presentation.

An unlocked notification MAY show only the host label, thread name, and work state. It MUST NOT include response text, commands, diffs, question content, or approval details. Its public lock-screen form MUST reveal only that Poker–Dealer needs attention.

#### M3 acceptance evidence

M3 is complete only when:

- compatibility fixtures and focused unit tests cover every introduced method, request family, item, notification, request-state transition, work-state transition, and no-replay boundary;
- an opt-in JVM live protocol test passes against u4090;
- an isolated disposable u4090 thread, using explicit interactive permissions only for that test, proves harmless command and file-change approvals without changing host defaults or existing YOLO work;
- a real Fold6 Dealer run against u4090 proves thread discovery and lifecycle, Start/Fork/Resume settings, all three request families, complete command/file presentation, steer, interrupt, phone notifications, process-death/same-phone-reboot/transport recovery, and duplicate prevention;
- a narrow real-Fold6 smoke keeps u4090 and Spark connected simultaneously and demonstrates independent concurrent thread states and focus metadata;
- recorded evidence omits secrets, provider configuration, private endpoints, and thread IDs.

This evidence MUST NOT claim mixed-version compatibility. Spark and u4090 have not yet been proven concurrently on different supported Codex versions.

### M4 — Dealer↔Poker synchronization

Complete when Poker pairs, lays out attached host-qualified card piles horizontally in `BUSY | ATTENTION_REQUIRED | READY` order without a separate thread list, switches piles with `LEFT` and `RIGHT`, implements the accepted navigation/input-mode boundary, reads retained/live output, receives work-state transitions that drive the accepted HUD wake/focus behavior, offers Manual Hide in every work state and Manual Wake, reconnects deterministically, and preserves scroll position during live growth.

### M5 — Wearable input

Complete when Poker supports reviewed Morse/ASR, start/steer/interrupt, safe limited approvals, and escalation to Dealer.

M5 draft editing, photo capture, and ordinary turn submission apply only while
focus is in the thread composer. They MUST NOT appear in, mutate, or submit a
server-request panel; request panels retain their own bounded input and
resolution rules.

The composer draft is an ordered mixture of text and atomic photo tokens. Poker
renders each photo token as `📷`, but the token retains the identity of its
underlying unsent image and MUST remain distinguishable from an ordinary typed
emoji. Submission serializes the token as an actual image input in its draft
position; it MUST NOT send the rendering emoji as a substitute for the image.
Photo capture inserts its token at the current composer cursor and shifts later
draft content; it does not always append at the end. Deleting a photo token
deletes its corresponding unsent image.

Poker shows no photo preview in the HUD. It transfers a capture over the
authenticated Dealer connection, and the `📷` token appears only after Dealer
has durably stored the image and acknowledged it. Poker then deletes its
temporary copy. Dealer retains the image while its token exists and throughout
pending or uncertain submission. Dealer deletes it only when the token is
deleted, the exact submission is confirmed accepted, or the thread is confirmed
deleted.

Poker and Dealer MUST preserve the captured image bytes and all embedded
metadata. They MUST NOT strip metadata, automatically downscale, or re-encode
the image as part of capture, transfer, draft storage, or submission. Embedded
location, device, and timestamp metadata therefore remains part of the image
submitted to Codex when present.

Dealer submits each photo as an inline `image` data URL in the ordered app-server
input array. Accepted capture formats are PNG, JPEG, WebP, and non-animated GIF;
Poker–Dealer validates the actual image format and never converts an unsupported
capture. It MUST NOT use `localImage` or stage files on the execution host
because Poker, Dealer, and the selected host do not share a filesystem.

Poker–Dealer MUST NOT impose a project-specific photo byte-size or pixel-size
ceiling unless the user explicitly changes this decision. M5 qualifies the
unaltered 12-megapixel capture path on Spark and Fold6 Termux and adds mixed
text/image compatibility fixtures for `turn/start` and `turn/steer`. An
unsupported or rejected image submission remains visible, retains the exact
draft and photo assets, and MUST NOT silently fall back to text or emoji alone.

In composer input, `DOWN` moves to the start of the next Unicode word or photo
token like Vim `w`, and `UP` moves to the previous one like Vim `b`. Each photo
token is one indivisible word. At the first draft position, another `UP` exits
to navigation mode at the bottom card's end as specified for M4; at the final
position, `DOWN` stops. A short `FN` deletes from the cursor through the next
word-motion boundary like Vim `dw`; deleting a photo token is atomic.

Holding `FN` opens an action wheel, posture highlights one candidate while the
button remains held, and releasing the button selects it. The M5 wheel offers:

- start Morse typing;
- start ASR recording;
- take a photo and insert a token into the current host-qualified thread's
  unsent composer draft; and
- perform the context-sensitive Primary action.

The default layout maps left relative roll to Morse, upward relative pitch to
ASR, right relative roll to Photo, and downward relative pitch to Primary.
Releasing `FN` inside a small origin dead zone performs no action. Dealer MUST
allow the four candidate actions to be rearranged among the noncentral sectors
and synchronize that layout to Poker. Poker MUST keep the synchronized sector
assignments fixed until Dealer changes the layout. A contextually unavailable
action disables its sector; it MUST NOT cause the other actions to shift.

Primary is derived when the wheel opens and revalidated when `FN` is released:

- `READY` with a nonempty draft means Send through `turn/start`;
- `BUSY` with a nonempty draft means Steer through `turn/steer` and its required
  `expectedTurnId`;
- `BUSY` with an empty draft is displayed as `ESC` and means semantic Interrupt
  through `turn/interrupt`; and
- `READY` with an empty draft, `ATTENTION_REQUIRED`, unknown state, missing
  control, or a pending or uncertain conflicting action disables Primary.

A draft is empty only when its text is blank and it contains no photo tokens.
The `ESC` label MUST NOT emit or emulate a terminal Escape key. It reuses the
turn-ID-bound Interrupt locking, reconciliation, and no-replay rules above.

The wheel captures origin posture when the hold begins and uses relative roll
and pitch, never absolute head orientation. Its sector thresholds and origin
dead zone MUST remain tunable and be calibrated on the real glasses.
The wheel opens only after Android's standard long-press timeout. Releasing
`FN` before that threshold performs the composer deletion above in ordinary
composer input. Active ASR and Morse modes retain their own short-press rules;
outside those three contexts, a short `FN` has no action.

After ASR starts, pressing `FN` again commits the current transcript segment
and `TAP` abandons it.

In Morse mode, `TAP` enters a dot, `TAPTAP` enters a dash, a short `FN`
finishes the composition and opens review, and a long `FN` cancels the current
composition.

M5 design note: consider host-skill name completion while composing Morse input to reduce keystrokes. Until that design is reviewed, earlier milestones pass `$...` mentions as ordinary prompt text and rely on Codex's model-driven skill activation; Dealer does not add structured skill input or skill completion in M3.

### M6 — Production hardening

Complete when:

- lifecycle/power tests pass on Spark, u4090, Fold6 Termux, Dealer, and Rokid;
- embedded-tailnet and daemon/app-server update interruption are handled;
- compatibility and recovery diagnostics are visible;
- security review covers SSH, Tailscale state, native bridge, Android sandbox boundaries, secrets, Poker pairing, and approval safety;
- end-to-end real-hardware acceptance is recorded.

---

## 17. Immediate next job

A fresh Codex worker SHOULD implement M3 through the existing shared Android/app-server stack:

1. replace the one-shot host run with the multi-host session manager and durable connection intent;
2. add configured-host thread discovery, manual attachments, soft control, and lifecycle actions;
3. derive the accepted thread work states, pile ordering, focus metadata, and phone notifications;
4. implement complete command/file projection, the three accepted server-request families, steering, interruption, and monotonic reconciliation;
5. add the accepted Start/Fork/Resume settings and permission presets;
6. satisfy the M3 acceptance evidence gate.

Do not add Poker networking or HUD rendering before M4; Morse, ASR, and Poker actions before M5; a terminal; generic slash-command parsing; per-thread provider proxying; broad experimental app-server APIs; cross-host migration; or changes to the completed Fold6 Termux route.

The deferred Spark/u4090 mixed-version proof MAY be performed independently, but it is not an M3 prerequisite and no broad version-compatibility claim may be made without it.
