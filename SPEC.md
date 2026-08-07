# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 11
**Date:** 2026-08-07
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
                               │ secure RFCOMM bootstrap over the Android Bluetooth bond
                               │ Dealer-initiated Wi-Fi/mTLS product connection
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

### 2.6 Dealer–Poker Bluetooth bond and bootstrap

The Android Bluetooth bond between the Fold6 and RG glasses is the sole user
trust decision for Poker–Dealer. A **Poker Bluetooth bootstrap** is a bounded,
secure RFCOMM exchange with one project-private service UUID used only to:

- discover the bonded Poker installation;
- prove possession of each installation's Android-Keystore app key using fresh
  nonces and signatures;
- provision or rotate the peer app public-key pin;
- communicate Poker's current ordinary-Wi-Fi endpoint and bootstrap protocol
  capabilities; and
- detect revocation when the remembered peer is no longer `BOND_BONDED`.

Bluetooth bootstrap is not the Poker–Dealer data transport. Cards, controls,
photos, and ASR PCM MUST continue over the Dealer-initiated Wi-Fi/mTLS
connection. Normal production bootstrap MUST NOT require a Poker–Dealer pairing
code, QR code, manual IP/port entry, physical `Pair Dealer`/`Replace Dealer`
action, or second trust confirmation.

Dealer remembers the exact Android bonded-device identity after successful
bootstrap; friendly names are display-only. With no remembered identity,
exactly one bonded device answering the private Poker service may be adopted
automatically. Multiple matching bonded Poker devices fail closed as ambiguous.
Temporary Bluetooth loss does not revoke trust or tear down a healthy Wi-Fi
connection. `BOND_NONE` for the remembered peer revokes the relationship,
deletes the pinned peer transport record/endpoint, and closes the Wi-Fi
connection. Rebonding permits automatic bootstrap again.

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
- safety policy for whether an approval can be resolved on Poker;
- the Dealer-local ASR runtime, pinned model artifacts, active recognition
  session, provisional transcript slice, model catalog, installed packs,
  profiles, and download state;
- the remembered bonded Poker identity, Bluetooth-bootstrap lifecycle, pinned
  Poker app transport key and current authenticated Wi-Fi endpoint;
- device bindings, Poker font scale, synchronization revisions, and
  trust-scoped unread state.

### 3.3 Poker authority

Poker is authoritative only for:

- its current viewport;
- local scrolling and navigation;
- process-local composition state not yet acknowledged by Dealer;
- ephemeral microphone audio buffered while it is being transmitted to Dealer;
- a temporary captured photo until Dealer durably acknowledges it;
- the user's direct semantic action before Dealer accepts it.

Poker MUST NOT become the authoritative store for thread history, durable
drafts or photo assets, model profiles, bindings, or delivery state. It MAY
persist the remembered bonded Dealer identity, pinned Dealer app transport key,
enabled-listener state, trust-scoped unread identifiers and watermarks, and the
last acknowledged Dealer-owned binding map and Poker font value together with
their revisions in private backup-excluded storage. Synchronized card content
and modal input state remain process-local; Dealer resynchronizes them after
Poker restarts.

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

A human-control claim has no inactivity timeout. It changes only through an
explicit handoff, detected external control, process/generation replacement, or
the connection-loss rules below. Dealer projects a monotonically increasing
control generation to Poker. A handoff first establishes a barrier: active
wheel/gesture input is cancelled, uncommitted mode-private Morse/ASR state is
discarded, acknowledged draft content is preserved, and every pending mutation
is settled or fenced before the new generation may write. Late operations from
an older generation MUST NOT be rebased, redirected, queued, or replayed.

Every Poker mutation MUST identify the exact host, thread, target and target
revision, cursor or selection revision when applicable, control generation,
connection epoch, mode session, operation ID, and relevant turn or request
locator. Dealer accepts one winner for each revision-bound race and discards
stale callbacks after commit, cancellation, timeout, takeover, or target loss.

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
- overflow-safe WebSocket payload-length parsing;
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

Thread attachment and detachment are explicit Dealer actions. Poker Hide/Wake changes presentation only and MUST NOT attach, detach, subscribe, unsubscribe, connect, or disconnect anything. A host disconnection preserves attachment metadata and cached piles while marking availability separately; unavailable piles remain manually viewable and never cause a focus change.

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
comes first; equal transition times use stable attachment order. Every pile
remains reachable only through the horizontal `LEFT`/`RIGHT` line.

Work-state reordering MUST preserve focus by `(hostId, threadId)`, not by
horizontal index. The focused pile may move and its neighbors may change, but
state changes, synchronization, wake, and foregrounding MUST NOT change the
focused pile, card, scroll anchor, navigation/input mode, or cursor. If the
focused pile no longer exists, Poker selects the nearest surviving horizontal
neighbor, preferring the new occupant of the old index and then the preceding
pile.

Poker MUST offer Manual Hide in every thread work state. Manual Wake restores
the last valid focused pile and local anchor. A work-state transition alone
MUST NOT wake Poker. Dealer requests foregrounding only for the first
displayable content of a newly created qualifying user-visible card, or after
reconnect when such a card is newer than Poker's acknowledged unread watermark.
Later chunks, finalization, metadata, retries, snapshots, bindings, downloads,
and existing-card updates MUST NOT wake. If Poker is already foreground with
the display on, the event only updates cards and unread state.

Foregrounding MUST NOT bypass a secure keyguard. Poker uses Android's permitted
wake/overlay path, releases any wake lock after foregrounding, and then follows
the normal screen timeout. Overlay denial is optional capability loss: Poker
continues to receive data, does not nag, and Dealer offers an explicit Settings
retry. No Accessibility service, FCM path, system-wide key remap, or custom
cooldown timer is part of M4.

After the focused pile accepts a prompt or steer, it is or remains Busy and the
HUD stays visible on the same `(hostId, threadId)`. Focus remains inside that
pile's now-empty composer input; Poker MUST NOT advance to an Attention or Ready
pile or hide merely because the action was accepted.

Unavailable piles retain cached history for manual viewing. Availability,
failed turns, interrupted turns, and request-state changes never move focus.

Unread is owned per Poker pairing, not shared with Dealer. The first complete
snapshot for a new pairing establishes a zero-unread baseline. Thereafter each
newly created, nonhuman, user-visible card contributes one unread unit:
assistant text, command/file output, a server-request card, or a visible error.
Human messages, steering text, draft/photo tokens, transient notices, metadata,
and revisions of an existing card never increment it. One server request is one
card and therefore one unread unit even when it contains several questions.

A card clears only after it is finalized and its final line is visible while
that card is focused on Poker; jumping directly to its end counts. Dealer
reading does not clear it. Detach, archive, or confirmed deletion removes that
pile's unread entries; temporary unavailability preserves them. Poker persists
content-free card identifiers and watermarks in private backup-excluded storage.
Corruption discards them and establishes a fresh baseline after resync.

Only while pile switching is available, Poker reserves one footer line in
these exact forms, with singular `1 card unread` as appropriate:

```text
🔌·2 cards unread·DGX Spark:Thread name
🔌·DGX Spark:Thread name
2 cards unread·DGX Spark:Thread name
DGX Spark:Thread name
```

`🔌` appears only while disconnected. The host name never marquees; only
the thread name marquees when needed. There are no brackets, spaces around the
middle dots, healthy-status field, second footer line, or attached-thread
header/list. The thread label falls back from explicit name to preview to
thread ID after collapsing whitespace.

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
| `TAP` | single-finger press and release on the touch panel |
| `TAPTAP` | dual-finger tap on the touch panel |

A bonded Bluetooth remote supplies ordinary Android HID key events to Poker.
Dealer's per-device bindings map complete interactions onto the same operations;
raw key messages and vendor event types MUST NOT leak into pile, card, or input
logic. Remote devices have no posture gesture: a remote may hold mapped `FN`,
but action-wheel selection still uses only glasses IMU posture.

Every source interaction has `BEGIN`, optional hold/update, and exactly one
release or cancellation. The first interaction beginning on glasses or a
remote owns canonical input until release/cancellation; competing events are
ignored and never queued. `ACTION_CANCEL`, focus loss, or device disconnection
cancels the unfinished interaction and MUST NOT synthesize a tap, dot, dash,
`FN` release, or wheel choice. Gesture timing uses the source device's monotonic
clock; cross-device wall clocks are never compared.

While the HUD is visible, `TAPTAP` performs Manual Hide unless a more specific
input-mode rule assigns it another meaning; active Morse assigns it character
deletion.
While the HUD is hidden, `TAP` performs Manual Wake and restores the last valid
focused pile/anchor without state-based selection. M4 does not require a
separate on-screen Hide control.

While the HUD is visible in navigation mode, `TAP` toggles the focused card's
collapsed or expanded details when that card supports expansion. It does
nothing to a card without an expandable presentation.

`FN` behavior depends on the accepted input mode: ordinary composer deletion,
the action wheel, Photo deletion/exit, Morse deletion/exit, or ASR deletion/exit.
It is a no-op in ordinary navigation mode.

### 10.2 Poker pile browsing

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
blinking cursor or highlighted option. Entering an option panel immediately
highlights its first Dealer-projected choice; there is no empty-selection
position. When input focus is back at the first cursor or option position, `UP`
returns to navigation mode at the end of the owning card, so one subsequent
`DOWN` re-enters the same input surface.
Within a multi-question request panel, question controls form one sequence in
wire order. `DOWN` at a nonfinal question's final cursor or option position
enters the next question at its first position; `UP` at a nonfirst question's
first position enters the previous question at its final position. Only `UP`
from the first question's first position returns to navigation mode at the end
of the owning card. At the last question's final position, `DOWN` leaves input
mode and moves to the next card's end when another card follows; otherwise it
stops. This makes later request cards reachable without resolving an earlier
one. Input mode never wraps or enters another card's input surface directly.

Switching piles preserves each pile's focused card, scroll offset, and
navigation or input mode in Poker process memory. If synchronized state removes
or changes the focused card, composer, or request panel so the
saved position is no longer valid, Poker exits stale input focus and
re-anchors that pile to its newest bottom card. Active Photo, Morse, ASR, and
pending semantic mutations are target-pinned and block pile switching until
their accepted exit or terminal rule runs.

Dealer's composer is state-sensitive and requires the thread's Dealer control claim:

- `READY` labels Send as a new turn and calls `turn/start`.
- `BUSY` labels Send as **Steer active turn** and calls `turn/steer`.
- `ATTENTION_REQUIRED` disables ordinary prompt submission until the blocking request is resolved or the turn is interrupted.

A steer MUST include the currently observed active `turnId` as `expectedTurnId`. A rejected, unsupported, or precondition-mismatched steer MUST be reported visibly, trigger authoritative reconciliation, and MUST NOT fall back to `turn/start`.

Interrupt is an explicit one-tap action bound to the currently observed `turnId`. The first tap locks the action until its outcome is known. Dealer MUST NOT queue, retarget, or replay Interrupt across reconnect; without a confirmed matching live turn it reports unavailable and reconciles authoritative state.

Poker MUST use semantic actions rather than terminal key emulation. MVP actions are reviewed new-turn text, steer, interrupt, safe approve/deny, thread switching, navigation, and scrolling.

Poker MUST NOT expose generic terminal key injection as the product input model.

Morse and ASR both produce reviewable text in the current composer or eligible
request-answer field; neither submits directly. Both bind the exact host,
thread, target, revision, cursor, control generation, and mode session. The
expanded M4 contract in section 16 defines their interaction, privacy, and
recognition rules.

Dealer SHOULD send a client-generated user-message identifier when supported. On timeout or reconnect it MUST inspect authoritative state and MUST NOT blindly replay an uncertain `turn/start`.

Dealer MUST project a submitted prompt immediately as one user card keyed by that client identifier. Its delivery state advances monotonically from `LOCAL_PENDING` to `ACCEPTED` after a successful `turn/start`, then to `DELIVERED` when authoritative `thread/read` contains the matching `userMessage.clientId`. If acceptance cannot be established, the same card becomes `UNKNOWN`. Authoritative reconciliation MUST update that card rather than adding a duplicate.

Dealer MUST maintain one durable unsent draft per `(hostId, threadId)`. Thread switching, work-state reordering, HUD visibility, route or host disconnection, Dealer restart, Detach, and Archive MUST preserve that draft. Dealer MUST clear it only after app-server accepts the exact outbound action; uncertain acceptance MUST lock the draft against duplicate submission until authoritative reconciliation. Confirmed thread deletion permanently removes its draft.

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

Dealer MUST render every structured question in wire order and support multiple questions, option lists, free-text questions with null options, and `isOther` free-text answers. Poker–Dealer treats `isSecret` as informational protocol metadata rather than an input or display restriction. While its request is pending, the answer is visible as plaintext in Dealer and Poker, with the same eligible input methods as an otherwise equivalent question. After authoritative resolution, both clients purge the answer content and retain only its resolved status; the answer MUST NOT enter resolved projection history, diagnostics, logs, notifications, or a request fingerprint. A valid shape that cannot be displayed or answered completely is safely rejected.

All questions carried by one structured user-input server request remain
vertically separated sections of that request's single card and request panel.
They share one request locator, timeout, resolution state, and atomic response;
Poker MUST NOT create a card per question or submit questions independently.

Dealer owns one authoritative pending answer buffer per exact request locator
and projects its current selections and plaintext to Poker. Both surfaces MUST
display the same unfinished response, including answers marked `isSecret`, but
only the current human-control surface may mutate it. An observer-surface edit
MUST be rejected without changing the buffer and followed by authoritative
resynchronization.

Only semantic answer content is shared. Dealer and Poker each retain their own
active question, cursor, option highlight, request-panel scroll, and input-mode
state. Editing or moving focus on one surface MUST NOT move focus on the other,
including during a human-control handoff.

The M4 Morse/ASR control-loss and handoff rules also apply while a mode targets
this request-owned buffer.

Dealer-authoritative pending request-answer buffers are process memory only.
Their committed semantic content survives pile switching, HUD visibility
changes, and temporary transport loss while the same Dealer process remains
alive, but not Dealer process death or phone reboot. This does not preserve a
Poker-local uncommitted Morse word or Dealer-local ASR slice. After Dealer
restarts, an exact request that is still pending presents an empty answer
buffer. Poker MUST clear any stale projected copy when Dealer's generation
changes and MUST NOT restore
that copy upstream. Poker process death alone does not erase a buffer still
owned by the live Dealer process.

For a structured question with non-null `autoResolutionMs`, Dealer owns one
deadline and projects only the remaining duration to Poker; devices never
compare wall clocks. The countdown continues through wheel, Morse, ASR,
disconnection, process restart, and reboot. Reconciliation of the same request
preserves its earliest established deadline. At expiry Dealer responds once
with `{ "answers": {} }`, purges every pending answer buffer, exits a mode bound
to the request, and rejects late input; it MUST NOT invent a choice or free-text
answer. This behavior requires a compatibility fixture and live qualification
proof for the host/version. A null timeout waits until the user answers, sends
no answer, interrupts the turn, or the server clears the request. Resolved
question cards remain in history with answered, no-answer, or auto-resolved
status.

Resolved command and file approval cards remain in history with the known decision. If another client resolved a request and app-server does not reveal the decision, Dealer shows **resolved elsewhere** rather than guessing.

M3 resolves requests only in Dealer. M4 allows Poker to resolve a supported server request only when the complete request and relevant scope can be displayed, the request type is understood, the response is limited and unambiguous, the user intentionally decides, and Dealer still has the matching unresolved request.

An eligible approval panel MUST list vertically every choice produced by
Dealer's safe response derivation above, including `acceptForSession` and an
exact server-proposed execpolicy amendment when present. Poker MUST NOT remove a
choice merely to shorten the HUD list or reorder Dealer's projected choices. A
choice is absent only when the connected protocol does not make it safely
available; if required request or choice material cannot be rendered
completely, Poker escalates resolution to Dealer instead.

In an eligible free-text structured-question panel, including one marked
`isSecret`, ordinary Unicode `w`/`b`/`dw` editing, Morse, and ASR edit that
request's plaintext-visible answer buffer and MUST NOT mutate the thread
composer. Photo remains disabled. Primary remains disabled until the complete
request response is valid.

In an eligible option question, `DOWN` and `UP` move only the highlight and
MUST NOT mutate its answer. `TAP` selects the highlighted option into the
request-owned response buffer but does not submit any part of the request.
Each option question is single-select: selecting another option replaces its
previous answer and MUST NOT accumulate selections. M4 MUST NOT infer
multi-select behavior merely because the protocol response represents answers
as a list. Primary remains the only whole-request submission action.

When an option question has `isOther: true`, Dealer appends one synthetic
**Other** choice after every server-provided option without reordering those
options. Selecting **Other** replaces the question's selected answer and opens
a request-owned plaintext answer buffer editable through Morse or ASR; it MUST
NOT mutate the thread composer. Photo remains disabled.

Selecting a named option MUST NOT erase that question's unsent **Other** text.
Returning to **Other** while the request remains editable restores the text,
but the atomic response serializes only the currently selected named option or
**Other** answer. Submission, cancellation, timeout, or authoritative
resolution discards every inactive **Other** buffer for that request.

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
- Poker disconnect/restart;
- temporary Bluetooth loss or Wi-Fi endpoint change;
- app-key loss/reinstall while the remembered Bluetooth bond remains intact; or
- Bluetooth bond removal/rebond.

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

Dealer↔Poker recovery uses the connection epoch and snapshot protocol in ADR
0001. A new authenticated socket closes the old epoch. Poker installs a complete
snapshot before enabling semantic writes. A transient reconnect may restore
write eligibility only when the Dealer process, control generation, and exact
thread claim are unchanged; any mismatch returns Poker as observer. Modal modes,
unfinished gestures, uncommitted Morse/ASR state, and pending camera input never
resume automatically.

Poker preserves the last complete snapshot read-only while staging a newer one.
Deltas newer than snapshot revision `R` remain in a bounded queue until atomic
install and acknowledgment; gaps, wrong bases, overflow, or staging failure
restart with a new snapshot. Normal live output is also bounded: a slow Poker
connection closes and resynchronizes rather than dropping semantic revisions.
Protocol-major mismatch blocks synchronization and actions while retaining any
compatible read-only snapshot; same-major optional capability mismatch disables
only the affected feature.

---

## 13. Dealer and Poker platform decisions

### 13.1 Dealer

Dealer MUST be a native Android Kotlin application using:

- Jetpack Compose;
- coroutines and `Flow`/`StateFlow`;
- Room for hosts, thread attachment metadata, recent projection, unread state, and pending actions;
- DataStore for non-secret preferences;
- Android Keystore-backed protection for SSH credentials, host-key pins, and Poker app transport identities;
- a foreground service while live host, embedded-tailnet, or Poker connections are enabled.

Dealer is a Codex client, not a terminal emulator.

Dealer and Termux are independent Android apps. No shared UID or private-filesystem coupling is required or allowed.

The embedded tailnet module MAY contain Go/native code, but its public Android-facing API MUST remain narrow and lifecycle-safe.

### 13.2 Poker

Poker MUST be an ordinary native Android application compatible with the observed Android 12/API 32 glasses environment.

Poker MUST use `minSdk = 28`, public Android APIs, shared pure-Kotlin modules,
and a foreground service for the Dealer listener when enabled. A bonded,
enabled Poker starts that listener after boot with one silent, content-free
notification and exposes the secure RFCOMM bootstrap service. Android force-stop
is respected and requires a manual app open. On Android 12/API 31 and later,
Dealer and Poker request `BLUETOOTH_CONNECT` only as an Android capability
permission; it is not a second Poker–Dealer trust ceremony.

Dealer and Poker MUST request camera and microphone permission lazily at first
mode use. Denial or later revocation exits or refuses the mode, returns to
ordinary input, and shows `Photo unavailable` or `ASR unavailable` for one
second. The app MUST NOT repeatedly prompt; Dealer exposes an explicit Settings
path and the sanitized reason. For ASR, permission belongs to the device owning
the selected source. Pre-start denial makes that source unavailable and may
trigger the accepted one-time fallback to glasses; Poker then requests/uses its
own microphone permission. If the fallback permission/source is unavailable,
ASR remains unavailable. Revocation after recording starts always exits and
never hot-switches.

### 13.3 Dealer↔Poker trust bootstrap and transport

The normal production topology is:

```text
Fold6 / Dealer ── secure RFCOMM over existing Android Bluetooth bond ── Poker / RG
       │                     bootstrap/discovery only                    │
       └──────────── Dealer-initiated ordinary Wi-Fi/mTLS ──────────────┘
                              product data plane
```

The Bluetooth bond is the sole user trust decision. Dealer automatically
enumerates bonded devices and probes Poker's private secure RFCOMM service. The
bootstrap exchanges only bounded trust/endpoint metadata and app-key
proof-of-possession; it does not carry cards, photos, ASR audio, or ordinary
semantic operations.

After bootstrap, Dealer initiates the existing mutually authenticated TCP
connection. Poker listens only on the active ordinary hotspot/Wi-Fi interface,
using the hardware-qualified TCP port. The validated Fold6-hotspot topology
remains the production acceptance topology, while an ordinary shared Wi-Fi LAN
is also valid when peer-to-peer TCP is reachable.

No CXR, ADB tunnel, proprietary companion channel, numeric pairing code, manual
IP/port form, QR code, or second Poker trust confirmation may be required.
Bluetooth bootstrap, app-key rotation, bond revocation, connection epochs,
heartbeat, synchronization, and content-free Poker persistence follow ADR 0001.

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
- unresolved request deadlines and earliest-deadline reconciliation metadata;
- the remembered bonded Poker identity, pinned app transport key, authenticated
  Poker Wi-Fi endpoint, Bluetooth-bootstrap state, synchronization, key-binding,
  listener, and font-scale state;
- durable composer photo assets and their draft/pending/uncertain references;
- the validated ASR catalog snapshot, installed model packs, pinned revisions,
  selected default, versioned profiles, and resumable download jobs/partials;
- separate Dealer and Poker font scales;
- embedded-tailnet non-secret preferences and diagnostics.

Dealer MUST retain all app-server-provided command output and file-change text for attached threads in durable, Dealer-private, file-backed storage rather than depending on process memory. This retained projection MUST survive route loss, host disconnection, and Dealer process restart. Cache belonging only to detached or archived threads MAY be evicted because app-server remains authoritative and can be reread; unresolved server requests and outbound actions whose acceptance is unknown MUST NOT be evicted.

Dealer MUST NOT silently truncate retained content. If storage or reassembly fails, the affected card MUST be marked incomplete and any approval that requires the missing content MUST fail closed until complete review material is restored.

Transcript projections, output, diffs, drafts/assets, pending-action records,
Poker state, ASR catalogs/profiles/packs/downloads, and temporary ASR spools MUST
remain in Dealer-private storage excluded from Android backup and MUST NOT be
written to logs. Corrupt or incomplete derived projection may be discarded and
rebuilt; uncertain actions and referenced photo assets MUST NOT be guessed away.

Streaming raw ASR audio and provisional slices remain in bounded process memory
and MUST NOT enter Room, DataStore, Android backup, logs, or recovery snapshots.
An offline recognizer MAY use only current-slice temporary files in
device-encrypted, backup-excluded app-private storage. It MUST rotate/delete them
under section 16 and purge abandoned files at startup without transcription or
replay.

On startup Dealer deletes a staged or committed photo asset only when no draft
token and no pending or uncertain submission references it. Uncertainty always
retains the asset. Confirmed deletion of a thread removes its unreferenced
assets; accepting the exact submitted turn removes the corresponding draft
assets only after app-server acceptance is authoritative.

App upgrades MUST migrate trust, drafts/assets, unread state, bindings, request
deadlines, and the ASR catalog/pack/default/profile/download referential set
atomically. A failed durable-data migration
MUST retain the previous data and fail visibly rather than erase or partially
rewrite it. Derived snapshots may be discarded and resynchronized. An
incompatible new ASR model/profile schema is a side-by-side candidate with a
fresh default profile, not a guessed migration of user settings.

Poker persists no card text. Its private backup-excluded state is limited to
the remembered bonded Dealer identity, pinned Dealer app transport key,
listener state, trust-scoped unread IDs/watermarks, and the last-acknowledged
Dealer-owned binding map and Poker font value with their revisions. Corrupt
derived Poker state establishes a fresh unread baseline and resynchronizes
bindings/font. Corrupt or missing app-key peer state is cleared and may be
reprovisioned automatically only while the remembered Android Bluetooth bond is
still present; absence of that bond remains untrusted.

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

1. models one persistent Linux workstation with ordered route metadata; the M1
   proof used u4090 while DGX Spark was unavailable at that time;
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

### M4 — Poker interaction and multimodal input

M4 includes Dealer↔Poker synchronization and every formerly planned M5
wearable-input feature. M5 is retired. Implementation SHOULD remain a sequence
of narrow tracer bullets: synchronization/piles, canonical operations/bindings,
composer/wheel, Morse, ASR/model management, Photo, then integrated hardware
acceptance.

M4 is complete when production Dealer and Poker automatically bootstrap trust from their Android Bluetooth bond and recover securely;
render retained/live horizontal `BUSY | ATTENTION_REQUIRED | READY` piles with
the accepted navigation, unread, footer, and wake behavior; support glasses and
one bound HID remote; provide reviewed composer and eligible request-panel
input; implement fixed-wheel Send/Steer/Interrupt, Photo, Morse, and Dealer-local
ASR; and pass the acceptance gate below.

#### Composer and draft transactions

Composer-draft editing, photo capture, and ordinary turn submission apply
only while focus is in the thread composer. They MUST NOT appear in, mutate, or
submit a server-request panel; request panels retain their own bounded answer
input and resolution rules.

The composer draft is an ordered mixture of text and atomic photo tokens. Poker
renders each photo token as `📷`, but the token retains the identity of its
underlying unsent image and MUST remain distinguishable from an ordinary typed
emoji. Submission serializes the token as an actual image input in its draft
position; it MUST NOT send the rendering emoji as a substitute for the image.
Photo capture inserts each successful token as one ordered block at the
Photo-session cursor; it does not add whitespace. Deleting a photo token must
delete its corresponding unsent image through the transaction below.

#### Photo

Photo is available only from ordinary thread-composer input while Dealer is
authenticated and the target is editable. Selection sends a start request bound
to the exact host, thread, draft revision, cursor, control generation, and fresh
session ID. Dealer pins that target and session before Poker opens the camera.
The accepted cursor becomes both the original composer-return position and the
initial Photo-session cursor.
Failure leaves ordinary input active and shows `Photo unavailable` for one
second; Dealer retains the sanitized cause. Photo never targets a request panel
and has no offline queue.

Poker uses public Camera2 directly, not CameraX or a proprietary Rokid media
API. The accepted RG path is API 32/Camera2 `LIMITED`, 480×640 preview,
4032×3024 JPEG, sensor orientation 270°, and 1×–8× zoom. Photo replaces the
whole green transparent HUD with the live 480×640 preview; the captured JPEG
remains native full color. Preview orientation is a display transform
only; still capture uses the native JPEG orientation/EXIF path. Capture uses the
HAL still template with default quality and processing, no flash controls, no
format/resolution/HDR controls, and no post-capture encoding.

Each Photo session starts at 1× zoom. `DOWN` multiplies zoom by 1.25 and `UP`
divides by 1.25 within the device range; preview and capture use the same zoom.
Zoom persists only within that session. `TAP` captures. Long `FN` exits only
from live preview; it does not reopen the wheel. Deliberate exit closes Camera2,
deletes all remaining Poker session copies, unlocks the composer, and restores
its cursor immediately after the last remaining session token or at the
original cursor if none remain. It shows no `Photo exited` notice. Photo has no
inactivity timeout.

Capture has a five-second deadline and transfer has a fifteen-second deadline.
During capture Poker freezes the preview; during transfer it shows the actual
captured JPEG. Every operation, including short/long `FN`, zoom, another capture,
Hide, and pile switching, is blocked until success or terminal failure. Success
requires Dealer to validate exact length, actual format, and SHA-256 digest,
atomically store the bytes, and insert one `📷` token at the pinned session
cursor. Poker retains an exact backup-excluded session copy solely for
display/deletion and returns to live preview so the user may take another photo.
Multiple successful captures form an ordered block and advance the session
cursor immediately after each token.

Failure or timeout discards all staging and the temporary capture, inserts no
token, shows `Photo not added` for one second, and returns to live preview when
the session remains viable. There is no Retry/Discard panel, transfer
cancellation, reconnect retry, partial-upload ledger, or offset resume; the user
may take another photo. Capture, transfer, and deletion use hard monotonic
one-winner deadlines; timeout/commit fences discard every late callback. An
acknowledgment already durably committed for the
same asset/session is idempotent and MUST NOT duplicate the token.

In live preview, short `FN` deletes only the latest photo committed by the
current Photo session. If none exists, it is a no-op and preview continues. If
one exists, Poker keeps showing that photo while every operation is blocked and
Dealer performs a five-second revision-bound deletion of both token and asset.
Success deletes its Poker session copy, moves the session cursor back to the
token's former position, shows `Photo deleted` for 500 ms, and resumes preview.
Failure or timeout
retains the photo and token, shows `Photo not deleted` for one second, and
resumes preview. A stale deletion callback cannot remove another token.

Camera2 disconnect/error during live preview exits Photo, preserves committed
photos, shows `Photo unavailable` for one second, and never loops reopening the
camera. When the pinned target remains valid, that exit unlocks the composer
and restores the current Photo-session cursor. HUD/background loss,
Dealer↔Poker loss, target/control loss, or a process loss immediately closes
Camera2 and forces mode exit even during a pending operation: uncommitted
capture/staging and all Poker session copies are discarded, committed Dealer
tokens/assets remain authoritative, and late callbacks are fenced. A
deliberate Dealer takeover exits live preview immediately; while capture,
transfer, or deletion is pending, takeover is disabled until that operation
wins or reaches its deadline. Forced exit shows no `Photo exited` notice.

Before capture and every temporary write, Poker checks Android's native
low-storage threshold. Before accepting transfer and every staging or durable
write, Dealer checks the same native threshold. Crossing either threshold or a
write failure discards only the uncommitted capture, shows `Photo not added`
for one second, and resumes preview if viable; Dealer shows `Insufficient
storage`. This is not a byte/pixel ceiling.

Photo bytes use identified asset chunks on the existing mutually authenticated
connection. The 4 KiB frame boundary is only a chunk boundary and MUST NOT add
an asset-size ceiling or another endpoint/transport. Poker's exact session
copies are app-private and backup-excluded, with no thumbnail, downscale,
re-encoding, or metadata change. Each is deleted with its photo, all remaining
copies are deleted on Photo exit, and abandoned copies are purged on Poker
startup. Dealer retains committed assets while referenced by a draft token or a
pending/uncertain submission and deletes them only under section 15's safe
orphan rules.

Poker and Dealer MUST preserve the captured image bytes and all embedded
metadata. They MUST NOT strip metadata, automatically downscale, or re-encode
the image as part of capture, transfer, draft storage, or submission. Embedded
location, device, and timestamp metadata therefore remains part of the image
submitted to Codex when present.

This byte-preservation guarantee ends at the app-server input boundary: Dealer
sends the exact captured bytes encoded in the data URL. Image decoding,
resampling, tokenization, or other backend or model processing after the
app-server accepts that input is outside Poker–Dealer's control and MUST NOT be
covered by its no-downscale claim.

Dealer submits each photo as an inline `image` data URL in the ordered app-server
input array. Each image input MUST set `detail` to `original`; this is a backend
processing request and does not extend the byte-preservation guarantee beyond
the boundary above. Accepted capture formats are PNG, JPEG, WebP, and
non-animated GIF. Poker–Dealer validates the actual image format and never
converts an unsupported capture. If the selected host rejects or cannot
represent `detail: original`, Dealer MUST surface an incompatible-image error
and retain the exact draft and photo assets. It MUST NOT automatically retry
with `high` or `auto`, downgrade the request, or replay the turn submission.
Dealer MUST NOT use `localImage` or stage files on the execution host because
Poker, Dealer, and the selected host do not share a filesystem.

Poker–Dealer MUST NOT impose a project-specific photo byte-size or pixel-size
ceiling unless the user explicitly changes this decision. M4 qualifies the
unaltered 12-megapixel capture path on Spark and Fold6 Termux and adds mixed
text/image compatibility fixtures for `turn/start` and `turn/steer`. An
unsupported or rejected image submission remains visible, retains the exact
draft and photo assets, and MUST NOT silently fall back to text or emoji alone.

In ordinary composer or eligible free-text request input outside active Morse
or ASR, Android's native Unicode word segmentation defines motion. Punctuation
and emoji are standalone user-visible words; each photo token is one indivisible
word. `DOWN` moves to the next start like Vim `w`, `UP` moves to the previous
start like `b`, and short `FN` deletes through the next boundary like `dw`. At
the first position another `UP` exits to navigation at the owning card's end;
at the final position `DOWN` stops.

Text-only `dw` is optimistic but revision-bound: Poker shows the deletion
immediately, permits only one outstanding mutation, and blocks further edits
until Dealer acknowledges. Rejection or uncertain acceptance installs the
authoritative draft rather than guessing a merge. Cursor position is
surface-local; after another surface changes the draft revision, Poker
re-enters at the draft end instead of mapping a stale cursor.

Deleting a photo token outside Photo mode is pessimistic. The visible token and
asset remain while all draft edits are blocked for a five-second exact-token
transaction. Success atomically removes both; failure or timeout retains both
and shows `Photo not deleted` for one second. No text mutation may absorb or
partially delete a photo token.

#### Device bindings

Dealer owns one complete revisioned binding map for the glasses and each bonded
Bluetooth HID device. The glasses start with the seven canonical defaults;
remotes start unbound. One physical control maps to at most one canonical
operation, while an operation may have several controls. Rebinding replaces the
old mapping. Incomplete maps are valid. Dealer offers one screen with a device
selector, seven operation rows and their control lists, `Bind`/remove actions,
reset-glasses-defaults, clear-remote, and synchronization status; no gesture
diagram or system-wide remapper is required.

Device identity is the exact Android device descriptor; friendly names are
display only. Remote button identity is descriptor plus Android `keyCode`;
scan codes are ignored, so buttons producing the same key code are
indistinguishable. Temporary disconnect retains the map. Confirmed unbonding
deletes it; a newly paired descriptor starts unbound with no name-based reuse.

Learning selects one connected device and operation, shows
`Bind <operation> — press control` on both surfaces, suppresses normal actions,
and captures the next complete gesture or press/release from that device. It
applies immediately with no confirmation or arbitrary timeout. Unsupported
input shows `Cannot bind` and continues waiting. Cancel, disconnect, focus or
control loss exits unchanged and clears the notice. Capture ignores key-repeat,
chords, sequences, double-click synthesis, and incomplete interactions. Mapped
`TAP` and `FN` preserve short/long duration; mapped `TAPTAP` is one atomic
operation.

Dealer sends the whole map with one revision; Poker installs it atomically or
continues using the previous map and reports unsynchronized. Each interaction
snapshots the active map at `BEGIN`, so a mid-hold update cannot reinterpret its
release. Binding edits are enabled only while Poker is connected and idle in
ordinary navigation/input with no gesture, wheel, modal mode, or pending
operation. Edits are never queued for later.

A remote becomes managed after it has at least one Poker binding. While Poker
is foreground, all key events Android delivers from that managed device are
consumed: mapped keys invoke Poker and unbound keys are no-ops. A never-managed
remote retains normal Android behavior. Poker cannot capture, consume, or wake
from remote keys while backgrounded, and M4 adds no Accessibility service,
global key hook, or system-wide Rokid-operation remapping.

#### Action wheel and Primary

Holding `FN` opens the action wheel only from ordinary composer input or an
eligible request-panel input. Navigation-mode `FN` is a no-op. The layout is
fixed and has no Dealer configuration:

```text
             Photo
Morse                    ASR
            Primary
```

The wheel snapshots head posture at hold begin and uses relative pitch/roll,
never absolute orientation. A central dead zone performs no action. Outside it,
dominant pitch/roll selects a sector; a ±5° diagonal band selects none. A
candidate must remain stable for 100 ms, with hysteresis around its boundary.
Posture older than 250 ms cancels the wheel. Release confirms only the currently
stable highlight; otherwise it is a no-op. Thresholds, dead zone, hysteresis,
diagonal band, and timing remain Poker-calibratable hardware constants, but the
four sector assignments are fixed. A remote-held mapped `FN` uses exactly the
same glasses-posture rules.

A contextually unavailable action disables its sector without moving the
others. Release revalidates the exact target, control generation, wheel session,
and displayed semantic action. A changed or unavailable candidate becomes a
no-op; Poker never substitutes another action.

Primary's displayed meaning is derived when the wheel opens. It remains eligible
at release only when the same derived action and all of its preconditions still
hold:

- a request panel that Poker may safely resolve means submit its current valid
  response;
- `READY` with a nonempty draft means Send through `turn/start`;
- `BUSY` with a nonempty draft means Steer through `turn/steer` and its required
  `expectedTurnId`;
- `BUSY` with an empty draft is displayed as `Interrupt` and means semantic Interrupt
  through `turn/interrupt`; and
- an ineligible request panel, `READY` with an empty draft, ordinary composer
  input while `ATTENTION_REQUIRED`, unknown state, missing control, or a pending
  or uncertain conflicting action disables Primary.

Primary is the only request-panel submission gesture. `TAP` MUST NOT send or
resolve a server request.

After Primary submits a response, that panel remains focused with its controls
locked until the matching authoritative resolution. Poker then exits to
navigation mode at the end of the same request card; it MUST NOT jump to another
request or the newest card. This specific resolution rule overrides the generic
stale-input fallback in M4.

A draft is empty only when its text is blank and it contains no photo tokens.
`Interrupt` is a turn-ID-bound semantic app-server action and never terminal
key emulation.

Send or Steer freezes the exact ordered draft, cursor, and every asset reference
until the app-server outcome is known, disabling editing, deletion, Photo,
Morse, ASR, and another Primary action. Acceptance clears that exact draft/assets,
leaves Poker focused in the same pile's empty composer, and moves that pile to
the right edge of `BUSY`; it never jumps to `ATTENTION_REQUIRED` or `READY`.
Rejection restores the draft and cursor unchanged. Unknown acceptance keeps all
of it locked and reconciles without replay. A pending Interrupt locks only Primary: the user may
continue preparing the composer, and after reconciliation the next displayed
Primary meaning follows current state; prepared text is never auto-submitted.

The wheel opens only after Android's standard long-press timeout. Releasing
`FN` earlier performs the current mode's explicit short-`FN` action; where none
is defined it is a no-op.

Selecting Morse or ASR from the action wheel targets the currently focused
editable text field: the thread composer, an eligible free-text request-answer
field, or an active **Other** answer field. A committed word or slice updates
that target at its cursor but does not submit it; the existing composer or
request panel remains the review surface, and Primary remains the only
submission action.

Morse binds after local target validation. ASR sends Dealer a start request for
the exact target, control generation, selected pack/profile, and configured
audio source. Poker shows `Preparing…` and locks that target, but MUST NOT open
the microphone until Dealer confirms the authenticated connection, control,
editable target, permission, loaded runtime, verified pack, valid profile, and
fresh session ID. Long `FN` during preparation cancels without `ASR exited`;
late acceptance is fenced out. Any failed prerequisite returns to ordinary
input and shows `ASR unavailable` for one second, with the exact sanitized cause
only in Dealer. There is no fallback to Rokid/Android speech, cloud recognition,
Poker-local inference, or a Codex host.

The configured microphone is snapshotted per session. Glasses microphone is the
default. If another selected source is unavailable before start, Dealer silently
falls back only to the glasses microphone; once recording starts, source loss
terminates the session and never hot-switches.

Morse and ASR are modal and remain bound to that originating pile, field, and
cursor. While either mode is active, `LEFT`, `RIGHT`, Manual Hide, and every
operation without an explicit mapping below do nothing. The user MUST exit with
a long `FN` before switching piles or fields, navigating ordinary input, hiding
the HUD, or reopening the action wheel.

#### Morse

An interaction `BEGIN` received before the inter-character deadline suspends
that timer and latches buffer/operation eligibility at the first contact,
including the TAP-versus-TAPTAP decision. No character may finalize while a
dash or two-finger chord is held. Resolving a valid dot/dash appends to the
latched character and restarts the full interval. A cancelled gesture, or a
gesture that resolves to an operation blocked by its latched state, resumes the
previously remaining interval; it never applies after the timer later clears.

In Morse mode, releasing a single-finger `TAP` before Android's standard
long-press threshold enters a dot; holding it through that threshold and then
releasing it enters a dash. Each released symbol restarts the configurable
inter-character quiet interval. Dealer configures 300–2000 ms in 50 ms steps,
default 700 ms; Poker snapshots the value for the whole mode session. M4's valid
sequence table is the printable written-character set defined by ITU-R
M.1677-1: its Latin letters (including
accented e), figures, and printable punctuation or signs. Named operational
signals without a written character, including **understood**, **error**,
**invitation to transmit**, **wait**, **end of work**, and **starting signal**,
are not text input and remain invalid.

The HUD does not render the current dot-dash buffer or a character hint for it.
When the quiet interval expires, a valid sequence appends its decoded character
to the current unconfirmed word; an invalid sequence appends nothing. Poker then
clears the dot-dash buffer and starts an empty next character position in either
case. Finishing a character neither adds a space nor commits the word.

While the dot-dash buffer is nonempty, `TAPTAP`, `DOWN`, `UP`, and every short
or long `FN` are unavailable. An operation begun in that state is ignored in
full and MUST NOT be queued or applied after the buffer clears. When the buffer
is empty, `TAPTAP` deletes only the last finished character of the current
unconfirmed word; if that word is empty, it does nothing.

Poker projects only the finished-character prefix to Dealer for completion; it
MUST NOT project the raw dot-dash buffer. Dealer computes at most one suffix
hint locally after at least two finished Latin letters, using the bundled pinned
SCOWL/ESDB American level-60 word list. Matching ranks by commonness, then
shorter completion, then alphabetically. Completion MUST NOT inspect
surrounding draft text, request answers, thread history, or learned input
history, and MUST NOT use a language model. It also MUST NOT query the selected
host or app-server, start a Codex turn, or place the prefix or hint in thread
history. The ghost suffix is ephemeral and revision-bound to the exact session,
target, and prefix; stale hints cannot commit.

When a completion is present, `DOWN` commits that completion followed by one
space and starts an empty next word without leaving Morse, preserving the hint's
displayed casing. Without a current completion, `DOWN` does nothing and leaves
the word unchanged. `UP` ignores any completion and commits directly decoded
Latin letters as lowercase, with digits and punctuation unchanged, followed by
one space. It then starts an empty next word without leaving Morse. A short
`FN` deletes the current unconfirmed word; when no character has been entered
for it, `FN` instead deletes the most recent word committed during the current
Morse session. If that session has committed no word, `FN` does nothing.
Holding `FN` past the long-press threshold MUST NOT open the action wheel.
Releasing that long `FN` exits Morse immediately, discards the current
uncommitted word, preserves committed text, and shows `Morse exited` for 500 ms.

`DOWN`, `UP`, and short-`FN` mutations are serialized against the exact target
revision. Poker blocks another mutation until Dealer acknowledges. A lost
acknowledgment retries only the same operation ID and base revision; a stale or
unreconcilable result installs authoritative text, exits Morse, and shows
`Morse interrupted` for one second. Short `FN` may delete only the current
unconfirmed word or the exact most recent word committed by this Morse session;
it never deletes pre-session text by guess.

Morse input remains ordinary prompt text. Poker–Dealer adds no structured skill
input or skill-name completion; `$...` mentions continue to rely on Codex's
model-driven skill activation.

#### ASR models and profiles

Dealer bundles a pinned Android ARM64 `sherpa-onnx` runtime and validated catalog
but no large model pack. ASR is unavailable until installation. The first pack
successfully installed becomes the sole default; later installs and revisions
never replace or activate it automatically. M4's required baselines are a
Parakeet Unified INT8 streaming 560 ms pack and Moonshine v2 tiny quantized
offline pack. The catalog MUST expose every currently available pack supported
by the bundled sherpa runtime, CPU ONNX Runtime backend, and a Dealer adapter;
entries incompatible with that runtime/backend are unavailable and excluded.

Each catalog entry declares an immutable pack ID/revision, family/adapter,
canonical Hugging Face artifact paths, digests, download/temporary/installed
space requirements, displayed `Size` (installed bytes), languages, licenses,
backend, default profile, and profile schema. Unavailable packs are omitted;
there is no unavailable filter. A manual Update button fetches the catalog
directly, without a signature requirement, and validates schema/runtime
compatibility before atomic replacement. Failure retains the prior catalog and
all installed state. Refresh never deletes installed packs or changes the
default. Catalog/model downloads are data-only: users cannot enter arbitrary
model URLs and a pack cannot supply executable/native runtime code.

Dealer's model panel lists installed/downloading packs with language, `Size`,
state, and default marker. `+` opens the remaining catalog and immediately
queues a selected pack while leaving the catalog open. That panel searches
name/family/language, filters by language and streaming/offline mode, sorts by
model name, and has no quality ranking, recommendation badge, unavailable row,
or unavailable filter. One transfer runs at a time and additional requests are
FIFO; pausing the active transfer lets the next queued transfer run. A
downloading row shows a green progress bar, percentage, ETA, and current mirror
or canonical source; tapping it pauses/resumes. A Ready row tap makes that pack
the default for future sessions. Its menu can cancel/delete a download and
remove partials. An installed row's `⋮` menu contains `Edit profile` and
`Delete`.

Downloads use Dealer's ordinary Android network and either the canonical source
or one optional Dealer-wide, credential-free, parseable HTTPS mirror. Each job
snapshots the configured mirror URL when queued; later setting changes affect
only new jobs. Mirror
resolution preserves the manifest's pinned digests; failure or wrong bytes is
shown and falls back to the canonical source. Persistent backup-excluded jobs
that were running may resume after process death/reboot only when URL, pinned
revision, and HTTP validator still allow a Range request; otherwise they restart
from byte zero. A paused job remains paused across restart.
Dealer preflights declared download, temporary, and installed space, then
verifies every digest and installs atomically. There is no arbitrary pack-size
ceiling. A metered connection may require the already accepted one explicit
size confirmation, but never a Wi-Fi-only policy.

New model revisions install side by side and start with their catalog-supplied
default profile; user settings are never cloned automatically. A missing,
digest-invalid, or unloadable installed pack is `Repair needed`; ASR does not
fall back. Explicit Repair redownloads the same pinned revision while retaining
its profile/default reference. A pack cannot be deleted while default or active;
deletion of another installed pack requires confirmation and removes its pack
and profile. Catalog removal does not invalidate an already installed pack.

Profiles use one strict versioned Dealer JSON envelope containing the pack ID,
revision, and model-specific `settings`. Dealer generates every supported field
with a default value. The monospaced raw-JSON editor validates the whole profile
atomically, identifies errors, rejects unknown/inapplicable/out-of-range fields,
and keeps the prior valid profile on failure. Profiles cannot alter artifact
URLs/paths/hashes, family/backend, 16 kHz mono input, transport, or Poker
operations. There is no Import Profile UI. Editing is blocked while ASR uses
that pack; saving invalidates its warm recognizer. Each profile includes
`warmRetentionSeconds` default 300; zero unloads immediately and Android memory
pressure may unload earlier. Only the default pack may remain warm when idle.
Changing the default unloads the previous idle recognizer immediately, but
never unloads a recognizer owned by an active snapshotted session; that instance
unloads when the session ends.

M4 adds no voice enrollment, read-500-words workflow, fine-tuning, adaptation
history, or learned personalization. Runtime tuning exists only through fields
explicitly declared by the selected pack's profile schema.

ASR start snapshots the exact pack revision, complete validated profile,
microphone source, target, and control generation. Mid-session catalog, default,
profile, source, or tuning changes affect only a later session.

#### ASR session

The selected Android audio path produces 16 kHz mono signed little-endian PCM16.
For the default glasses source, Poker sends whole-sample, session-scoped frames
with contiguous first-sample offsets; a gap, overlap, duplicate, malformed
alignment, or wrong session terminates recognition rather than guessing. There
is no audio codec or replay. The transport reserves capacity for control and
fence messages and bounds queued audio to an initially calibratable target of
about two seconds/64 KiB.

The endpoint owning the selected microphone owns the session's monotonic sample
counter and stamps every fence without wall-clock conversion. For glasses audio,
Poker snapshots its next-sample offset when the canonical `DOWN`/short-`FN`
interaction occurs. For Dealer-phone audio, Dealer snapshots its local
next-sample offset when that serialized canonical operation is accepted. That
source-owned offset is authoritative for the pre/post-fence split.

Dealer owns recognition and one uncommitted transcript slice and projects it as
visually provisional text on both surfaces. Interim display updates coalesce to
at most 10 Hz using the newest state; fence acknowledgments, commits, failures,
and terminal results bypass coalescing. Engine-final results and speech-pause
punctuation remain provisional. Pauses never commit: the model's native
punctuation is preferred, otherwise the snapshotted profile's deterministic
pause rules apply. Only `DOWN` commits.

A streaming pack updates the slice continuously. An offline pack uses the
pinned Silero VAD declared by its manifest and a current-slice temporary spool.
Long input is internally divided at most every 15 seconds, preferring a nearby
speech-silence boundary, then the lowest-energy boundary, then the exact limit.
These internal segments are implementation detail: one user `DOWN` slice is
atomic, and failure of any segment commits none of it. Closing an offline slice
rotates immediately to a new temporary file so capture for the next slice
continues while the prior slice is decoded; another `DOWN` or short `FN` remains
blocked until the closed slice is settled.

There is no fixed ASR recording-duration or audio-size ceiling. Dealer uses
Android's native low-storage threshold. Crossing it or failing a spool write
terminates ASR, discards current/queued uncommitted audio, deletes temporary
files, preserves committed text, and shows `ASR failed` for one second with
`Insufficient storage` in Dealer.

`DOWN` captures the next-sample offset as a session-scoped audio fence. Dealer
MUST consume every preceding sample before finalizing and atomically committing
the fenced slice to its target; Poker MUST NOT commit its last displayed
partial. Samples at or after the fence belong to the next slice. Until Dealer
acknowledges the fence, another `DOWN` or short `FN` is unavailable; capture may
continue only within the bounded audio queue. The fence follows all pre-fence
audio but MUST precede post-fence audio. Acknowledgment starts an empty next
slice without leaving ASR.

A short `FN` also captures a next-sample fence. A current slice is nonempty when
it contains any uncommitted sample, buffered/spooled audio, or provisional text.
When nonempty, Dealer consumes all pre-fence audio, discards that exact
slice, and starts the next slice with post-fence samples. When it is empty,
short `FN` revision-deletes only the exact most recent slice committed by this
ASR session. If none exists it is a no-op. Until acknowledgment, another
`DOWN`/short-`FN` is blocked while capture continues only within the bounded
queue.

Holding `FN` past the long-press threshold MUST NOT open the action wheel.
Releasing that long `FN` stops capture, terminates the Dealer session, discards
queued audio and the current uncommitted slice, preserves committed text, and
shows `ASR exited` for 500 ms.

Every fence/mutation uses the complete operation identity in section 4.3 and a
monotonic session revision. Dealer accepts at most one nontermination mutation
at a time and acknowledges duplicates idempotently. Lost acknowledgment
reconciles the same operation ID/base revision and is never replayed as a new
commit or delete. Long-`FN` remains available while a fence is pending: if the
commit won, its text remains and termination discards only later state; if
termination won, the fence is rejected. Dealer discards every late audio frame,
recognition result, or mutation after termination.

Audio-queue overflow terminates ASR, preserves committed slices, discards only
queued/current uncommitted state, and shows `ASR overloaded` for one second.
Recognition or atomic-commit failure likewise terminates, commits none of the
affected slice, preserves earlier slices, cleans temporary audio, and shows
`ASR failed` for one second. Dealer retains the precise sanitized cause.

Before a deliberate Dealer takeover of human control while any Morse or ASR
target has an uncommitted segment, Dealer MUST disclose that the segment will be
discarded and require confirmation. No extra confirmation is required when the
segment is empty. Involuntary process, control, or connection loss still exits
immediately without waiting for confirmation.

Loss of human control, Dealer process/generation replacement, Dealer↔Poker
connection, Poker foreground, ASR audio focus/source, or target authority exits
the affected mode immediately. Poker discards an active uncommitted Morse word;
Dealer terminates ASR and discards queued audio, temporary spools, and the
uncommitted slice. Committed text remains unchanged and discarded state is
never resumed. Execution-host loss alone does not exit a composer-bound mode
because Dealer owns that draft, but Primary is unavailable; a request-bound
mode exits when request authority becomes unknown.

If the bound composer or request-answer field disappears or becomes
non-editable, Poker exits an active Morse or ASR mode immediately and follows
M4's stale-input reanchor. Poker discards the Morse word; for ASR it stops
capture and Dealer discards queued audio and the uncommitted slice. Neither
surface may recreate the target or apply discarded input if that target later
returns. Forced exits show no `Morse exited` or `ASR exited` prompt; those 500
ms prompts are reserved for deliberate long-`FN` exits.

#### Notices, accessibility, font scale, and diagnostics

Poker has one non-reflowing transient-notice overlay slot. A newer notice
replaces the older one; notices are never queued or replayed and expire on their
normal monotonic duration while the HUD is hidden or another pile is focused.
Showing or removing a notice MUST NOT change content geometry or scroll anchors.

Dealer and Poker use native bidirectional text/layout and Android accessibility
scaling. Each also has its own product scale from 0.75× through 2.00× in 0.05×
steps, default 1.00×, applied in addition to Android's scale. Dealer's value is
local. Dealer owns Poker's value, synchronizes it live/on reconnect, and Poker
persists the last acknowledged value across reboot. Geometry changes preserve
the focused card and first visible semantic line, or the active cursor.

Both applications expose semantic accessibility labels for `Photo`,
`Disconnected`, option text, operation names, and other state; accessibility
MUST NOT depend on interpreting `🔌`, `📷`, or gesture-only meaning. M4 does
not set `FLAG_SECURE` or otherwise block screenshots/recordings.

Dealer diagnostics for the paired Poker show connection state, last sanitized
failure, negotiated protocol versions/capabilities, synchronization revision,
binding-sync state, and wake capability. They MUST NOT contain card text,
drafts, photos, audio, network endpoints, pairing material, credentials, or
other secrets.

M4 adds no thermal-protection policy. Android memory pressure may unload a warm
recognizer; measured thermal policy is deferred until evidence shows it is
needed.

#### M4 acceptance evidence

M4 is complete only after compatibility fixtures and focused tests cover every
new message, snapshot/delta/epoch transition, control fence, request deadline,
binding revision, draft/asset mutation, mode operation, and no-replay boundary,
and production builds pass on the real Fold6 and RG glasses against both Spark
and Fold6 Termux. Recorded real-device evidence MUST cover:

- built-in controls and one bonded HID remote, including learning and remote-FN
  wheel posture;
- automatic bootstrap over an existing Bluetooth bond with no typed code/IP/port
  or second trust prompt, app-key reprovision after Keystore loss/reinstall,
  ambiguous/non-bonded peer rejection, bond removal/rebond revocation, reconnect
  heartbeat/backoff, and forced snapshot recovery;
- horizontal piles, unread/footer clearing, foreground wake without focus
  change, request countdown/expiry, font scaling, and accessible labels;
- Camera2 open/preview/capture/zoom, repeated Photo capture, transfer/deletion
  success and deadlines, exact JPEG/metadata preservation, and safe cleanup;
- Morse timing, completion, commit/delete reconciliation, and forced exits;
- Parakeet streaming and Moonshine offline ASR paths, source selection,
  backpressure, fences, punctuation, failures, cleanup, and committed-text
  preservation; and
- mixed text/image `turn/start` and `turn/steer` compatibility on Spark and
  Fold6 Termux without any project photo-size ceiling.

This gate does not make u4090 a current build/test prerequisite and does not
restrict other catalog models trusted under the `sherpa-onnx` contract.

### M6 — Production hardening

Complete when:

- lifecycle/power tests pass on Spark, u4090, Fold6 Termux, Dealer, and Rokid;
- embedded-tailnet and daemon/app-server update interruption are handled;
- compatibility and recovery diagnostics are visible;
- security review covers SSH, Tailscale state, native bridge, Android sandbox boundaries, secrets, Bluetooth-bond bootstrap/app-key provisioning, and approval safety;
- end-to-end real-hardware acceptance is recorded.

---

## 17. Immediate next job

M3 is closed. Before implementation, create narrow M4 issues in this order:

1. Bluetooth-bond trust/bootstrap, app-key provisioning, connection epochs,
   retained/live synchronization, piles, unread, footer, wake, and recovery;
2. canonical interaction lifecycle, glasses defaults, HID bindings, and learning;
3. ordinary composer/request editing, control fences, fixed action wheel, and
   Send/Steer/Interrupt;
4. Morse;
5. Dealer-local ASR runtime, catalog/download/profile management, streaming and
   offline sessions;
6. Photo; and
7. integrated production-build and real-hardware acceptance.

Each issue MUST preserve the complete cross-cutting security, no-replay,
accessibility, persistence, and migration rules rather than creating a second
backend or speculative abstraction. Do not add an attached-thread list, M5,
terminal behavior, generic slash-command parsing, per-thread provider proxying,
broad experimental app-server APIs, cross-host migration, proprietary Rokid
transport, or u4090 as a current build/test fallback.
