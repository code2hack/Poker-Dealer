# Poker–Dealer Implementation Specification

**Status:** Normative implementation contract, revision 1  
**Date:** 2026-07-21  
**Repository:** `code2hack/Poker-Dealer`  
**Primary implementer:** local Codex worker  
**Product names:** **Poker** = Rokid HUD app; **Dealer** = Android companion app

This file is the single source of truth for the first production-capable version of Poker–Dealer. When code and this specification disagree, this specification wins until it is deliberately amended in the same commit as the behavior change.

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are normative.

---

## 1. Product contract

Poker–Dealer is a private, bidirectional wearable client for selected **tmux panes**.

- A tmux pane that the user attaches in Dealer becomes one **conversation**.
- Dealer connects to tmux hosts, discovers panes, receives terminal output, converts recent output into ordered message cards, persists recent history, and synchronizes those cards to Poker.
- Poker displays one conversation at a time as a scrollable pile of recent cards.
- Poker accepts text input through Morse code and ASR transcription using Rokid controls and supported Bluetooth HID rings.
- Dealer routes confirmed input back to the exact tmux pane from which the conversation originated.
- Full text is preserved. Long text is scrollable, not summarized or silently truncated.
- Agent replies may optionally expose an explicit conclusion view, but the complete answer remains available unless the user configures conclusion-only display.

The intended topology is:

```text
Termux tmux ─┐
             ├─ Poker–Dealer Bridge ── secure WebSocket ── Dealer on Android
DGX Spark ───┘                                             │
                                                           │ Rokid SDK transport
                                                           ▼
                                                        Poker HUD
                                                           │
                                            button / touch / ring / microphone
                                                           │
                                                           └──────────► Dealer ─► exact tmux pane
```

### 1.1 MVP definition

The MVP is complete only when all of the following work on real hardware:

1. Dealer can connect to a local Termux bridge and a remote DGX Spark bridge.
2. Dealer can discover tmux sessions, windows, and panes on both hosts.
3. The user can attach at least three panes and assign readable aliases.
4. New output from each attached pane becomes cards in the correct conversation.
5. Poker can switch conversations, navigate the recent card pile, and read a 20,000-character card without semantic truncation.
6. A live card can grow while the user reads it without forcing the viewport to the bottom.
7. Poker can compose a reply using Morse code, review it, and send it to the selected pane.
8. Poker can record speech from an available Rokid microphone path, show partial/final transcription, require review, and send the confirmed text to the selected pane.
9. Disconnecting and reconnecting Dealer, Poker, or a bridge causes deterministic resynchronization without duplicate cards or replies.
10. No component exposes arbitrary shell execution over the network.

---

## 2. Scope

### 2.1 In scope

- Rokid HUD application named **Poker**.
- Android companion application named **Dealer**.
- A host-side daemon/CLI named **`poker-dealer-bridge`** for Termux and Linux.
- Local Termux tmux servers.
- Remote Linux tmux servers, initially DGX Spark over Tailscale.
- One attached conversation per tmux pane.
- Recent card history, live card updates, scrolling, conversation switching, unread counts, and connection state.
- Plain text, Unicode, Markdown-like text, code, and terminal output.
- Optional explicit conclusion presentation for agent replies.
- Morse input.
- ASR transcription.
- Rokid built-in function button and touch panel.
- Bluetooth rings that expose standard Android/Rokid key, media-button, switch, or HID events.
- Secure pane discovery, output streaming, and constrained text/key input.
- Mock transports so all non-vendor behavior can be developed and tested without Rokid hardware or proprietary SDK artifacts.

### 2.2 Explicitly out of scope for v1

- ChatGPT, Discord, Telegram, WhatsApp, Feishu, email, SMS, or Android notification mirroring.
- A full terminal emulator UI on Poker.
- Images, video, file transfer, or rich terminal graphics.
- Mouse input into tmux.
- Arbitrary remote command execution.
- Automated shell command approval based only on speech.
- Vendor-specific reverse engineering of a Bluetooth ring.
- Cross-device account sync or a cloud backend.
- Generative summarization of terminal output.
- Maintaining complete tmux scrollback forever.
- Multi-user collaboration.
- Publishing proprietary Rokid SDK binaries in the repository.

---

## 3. Fixed naming and identifiers

### 3.1 Applications and packages

- Android companion display name: `Dealer`
- Android package: `com.code2hack.dealer`
- Rokid HUD display name: `Poker`
- Poker package: `com.code2hack.poker`
- Host bridge binary: `poker-dealer-bridge`
- Protocol name: `poker-dealer`

### 3.2 Definitions

- **Host:** A machine or Android/Termux environment running one or more tmux servers and one bridge.
- **Tmux server:** A tmux instance selected by default socket, `-L socket-name`, or `-S socket-path`.
- **Pane locator:** Runtime coordinates identifying one pane on one host and tmux server.
- **Conversation:** Dealer’s durable logical attachment to a pane locator.
- **Card:** A user, agent, terminal, or system message shown in chronological order.
- **Card pile:** Recent cards belonging to one conversation.
- **Deck:** The set of attached conversations visible in Poker.
- **Open card:** A card that may receive further content or replacement revisions.
- **Committed card:** A card whose current turn is complete. It may still receive a correction revision.
- **Transport:** The Dealer↔Poker communications implementation, independent of product logic.

### 3.3 Pane identity

A pane locator MUST contain:

```kotlin
data class PaneLocator(
    val hostId: String,
    val tmuxServerId: String,
    val paneId: String,          // tmux runtime ID, e.g. "%17"
    val sessionId: String?,      // metadata, e.g. "$1"
    val windowId: String?,       // metadata, e.g. "@4"
    val sessionName: String?,
    val windowName: String?,
    val paneIndex: Int?,
    val paneTitle: String?,
    val currentCommand: String?
)
```

`paneId` is authoritative only for the lifetime of that tmux pane. Names and indices are metadata and MUST NOT be treated as durable unique IDs. Dealer assigns its own stable `conversationId` and marks the conversation **stale** when the original pane disappears. Automatic reassignment to a new pane MUST NOT occur without an exact configured reattachment rule or user confirmation.

---

## 4. Platform and SDK constraints

### 4.1 Dealer

Dealer MUST be a native Android application written in Kotlin.

Required architectural choices:

- Jetpack Compose for phone UI.
- Kotlin coroutines and `Flow`/`StateFlow` for asynchronous state.
- Room for recent history and attachment persistence.
- DataStore for non-secret preferences.
- Android Keystore-backed encryption for bridge pairing secrets and certificate pins.
- OkHttp or Ktor client for bridge WebSockets. Pick one and use it consistently.
- A foreground service while live bridge and Rokid connections are enabled.
- `minSdk = 33` unless a verified Rokid SDK requirement forces a higher value.
- `compileSdk` and `targetSdk` MUST be pinned to the latest stable SDK installed when the project is bootstrapped; they MUST NOT use preview SDKs without a separate build flavor.

Dealer MUST keep all Rokid-specific classes behind a transport interface. The core application MUST compile and run using a loopback/mock transport without Rokid SDK artifacts.

### 4.2 Poker

Poker MUST follow the official Rokid YodaOS-Sprite/CXR-S sample project structure for the exact glasses firmware and SDK obtained by the user. Do not assume ordinary Android APIs are all available until verified on hardware.

Poker SHOULD use Kotlin where supported by the official sample. Shared protocol/domain code MUST remain pure Kotlin/JVM without Android UI dependencies. If the vendor build cannot consume the shared Kotlin module directly, define the protocol once as JSON Schema and generate or mirror minimal DTOs; do not fork protocol semantics.

### 4.3 Rokid SDK selection

The primary planned integration is:

- **Dealer:** Rokid **CXR-M** mobile SDK.
- **Poker:** Rokid **CXR-S** glasses SDK.
- **Link:** the custom data channel described by the official YodaOS-Sprite documentation.

Current public Rokid materials also list **CXR-L** for Android/iOS apps connected through the Rokid companion application. CXR-L is a contingency adapter, not core architecture.

The worker MUST begin with a capability spike before binding product code to any vendor method names. The spike MUST determine and record in code/configuration:

1. Exact glasses model, firmware, OS build, CXR-S version, and mobile SDK version.
2. Whether the available mobile route is CXR-M, CXR-L, or both.
3. Pairing and authorization lifecycle.
4. Bidirectional custom data-channel callbacks.
5. Maximum reliable frame size and whether ordering is guaranteed.
6. Disconnect/reconnect callback behavior.
7. Whether Poker can access microphone PCM, a vendor ASR transcript, or both.
8. Which function-button, touch-panel, and gesture callbacks reach Poker.
9. Whether standard Android `KeyEvent` events from a paired ring reach Poker.
10. Background/suspend behavior on both devices.

The implementation MUST expose:

```kotlin
interface PokerTransport {
    val state: StateFlow<PokerTransportState>
    val incomingFrames: Flow<ByteArray>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(frame: ByteArray)
}
```

Concrete implementations:

- `LoopbackPokerTransport` — mandatory, used by tests and developer mode.
- `RokidCxrMTransport` — primary real Dealer adapter.
- `RokidCxrLTransport` — optional only if required by available SDK access.

Poker MUST implement the matching glasses-side transport using CXR-S. No domain/UI class may import CXR-M, CXR-L, or CXR-S types.

### 4.4 Proprietary SDK handling

- Vendor AAR/JAR/SO files, credentials, application IDs, private Maven tokens, and signing keys MUST NOT be committed.
- Add a local, ignored `rokid.properties` or Gradle property mechanism for artifact paths and credentials.
- CI MUST build a `mock` flavor without proprietary dependencies.
- A real `rokid` flavor MAY be excluded from public CI but MUST have a documented local build task encoded in Gradle task names and error messages.

---

## 5. Repository shape

The worker MUST bootstrap a monorepo using this logical layout. Minor Gradle directory differences are acceptable; module responsibilities are not.

```text
/
├── SPEC.md                       # this specification
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── apps/
│   ├── dealer/                   # Android companion application
│   └── poker/                    # Rokid HUD application
├── shared/
│   ├── protocol/                 # pure Kotlin DTOs, codecs, sequencing, chunking
│   ├── domain/                   # pure Kotlin cards/conversations/input state
│   └── testing/                  # fixtures and fake transports
├── bridge/                       # Rust bridge workspace/crate
│   ├── Cargo.toml
│   └── src/
├── tooling/                      # build/test scripts only when needed
└── .github/workflows/            # public/mock CI
```

### 5.1 Bridge language

`poker-dealer-bridge` MUST be implemented in Rust.

Reasons captured as requirements:

- one native daemon/CLI binary;
- reliable process and byte-stream handling;
- explicit memory and concurrency behavior;
- mature async WebSocket/TLS ecosystem;
- a VT parser can consume tmux output without shelling out for every byte;
- it can be built natively in Termux and for Linux x86_64/aarch64.

Recommended crates are not mandatory, but substitutions require equal testability:

- `tokio`
- `tokio-tungstenite` or `axum` WebSocket support
- `rustls`
- `serde`, `serde_json`
- `clap`
- `tracing`
- `hmac`, `sha2`, `rand`
- `uuid`
- `vt100` or an equivalent maintained terminal parser
- `thiserror`

The bridge MUST compile with stable Rust and pass `rustfmt`, `clippy -D warnings`, and tests.

---

## 6. High-level architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ tmux host                                                    │
│                                                              │
│ tmux server(s)                                               │
│   ├── session / window / pane %3                             │
│   ├── session / window / pane %17                            │
│   └── session / window / pane %24                            │
│             │                                                │
│             │ tmux control mode + capture-pane recovery      │
│             ▼                                                │
│ poker-dealer-bridge                                          │
│   ├── discovery                                              │
│   ├── control-mode client(s)                                 │
│   ├── terminal parsing                                      │
│   ├── constrained input injection                            │
│   ├── pairing/authentication                                │
│   └── WSS server                                             │
└───────────────────────────────┬──────────────────────────────┘
                                │
                                │ Bridge Protocol v1
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ Dealer                                                       │
│   ├── HostRegistry                                           │
│   ├── TmuxRepository                                         │
│   ├── AttachmentRegistry                                     │
│   ├── CardAssembler                                          │
│   ├── Room database                                          │
│   ├── PokerSyncEngine                                        │
│   ├── InputRouter                                            │
│   ├── AsrCoordinator                                         │
│   ├── Input-device diagnostics                               │
│   └── foreground connection service                          │
└───────────────────────────────┬──────────────────────────────┘
                                │
                                │ Poker Protocol v1 over Rokid transport
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ Poker                                                        │
│   ├── conversation deck                                      │
│   ├── recent card cache                                      │
│   ├── card reader and scroll state                           │
│   ├── input normalization                                    │
│   ├── Morse state machine                                    │
│   ├── ASR capture/review UI                                  │
│   └── reconnect/sync client                                  │
└──────────────────────────────────────────────────────────────┘
```

Dealer is authoritative for conversations, cards, reply status, and sync cursors. The bridge is authoritative for current tmux topology. Poker is authoritative only for its local viewport, input-composition state, and temporary unsent draft.

---

## 7. Domain model

### 7.1 Host

```kotlin
data class TmuxHost(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val transportSecurity: HostSecurityMode,
    val enabled: Boolean,
    val lastSeenAtMs: Long?,
    val state: HostConnectionState
)

enum class HostSecurityMode {
    LOOPBACK_PINNED,
    TLS_PINNED_HMAC
}
```

Dealer SHOULD ship with creation helpers for:

- `Termux (local)` — `127.0.0.1`, loopback only.
- `DGX Spark (Tailscale)` — user-provided Tailscale hostname/IP and pinned bridge identity.

### 7.2 Tmux server

```kotlin
data class TmuxServer(
    val id: String,
    val hostId: String,
    val selectorType: TmuxSelectorType,
    val selectorValue: String?,
    val tmuxVersion: String?,
    val state: TmuxServerState
)

enum class TmuxSelectorType { DEFAULT, SOCKET_NAME, SOCKET_PATH }
```

The bridge MUST invoke tmux with an argv array. It MUST NOT interpolate selector values into shell strings.

### 7.3 Conversation

```kotlin
data class Conversation(
    val id: String,
    val locator: PaneLocator,
    val alias: String,
    val captureProfile: CaptureProfile,
    val presentationPolicy: PresentationPolicy,
    val inputPolicy: InputPolicy,
    val state: ConversationState,
    val lastSequence: Long,
    val unreadCount: Int
)

enum class ConversationState {
    ATTACHING,
    ONLINE,
    OFFLINE,
    STALE,
    DETACHED,
    ERROR
}

enum class CaptureProfile {
    RAW_LINES,
    SCREEN_DIFF,
    SHELL_OSC133,
    STRUCTURED_AGENT
}
```

Default capture profile: `SCREEN_DIFF`.

### 7.4 Card

```kotlin
data class Card(
    val id: String,
    val conversationId: String,
    val sequence: Long,
    val revision: Long,
    val groupId: String?,
    val partIndex: Int?,
    val partCount: Int?,
    val role: CardRole,
    val state: CardState,
    val fullText: String,
    val conclusion: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val delivery: DeliveryState?,
    val source: CardSource
)

enum class CardRole { USER, AGENT, TERMINAL, SYSTEM }
enum class CardState { OPEN, COMMITTED, CORRECTED, FAILED }
enum class DeliveryState { LOCAL_PENDING, ACCEPTED, DELIVERED, REJECTED, UNKNOWN }
enum class CardSource { POKER_INPUT, DEALER_INPUT, TMUX_OUTPUT, STRUCTURED_EVENT, SYSTEM }
```

Normative rules:

1. `sequence` is monotonically increasing per conversation and assigned only by Dealer.
2. `revision` is monotonically increasing per card.
3. Poker MUST ignore a card update with a revision not greater than the stored revision.
4. `fullText` MUST preserve all normalized semantic text.
5. `conclusion` is optional and additive. It MUST NOT replace or mutate `fullText` in storage.
6. Cards larger than **512 KiB UTF-8** MUST be split at a newline boundary into continuation cards with a shared `groupId`. No content may be dropped.
7. A single transport frame MUST NOT be assumed to hold a complete card.

### 7.5 Presentation policy

```kotlin
data class PresentationPolicy(
    val agentDisplayMode: AgentDisplayMode,
    val autoOpenOnNewOutput: Boolean,
    val markReadWhenVisible: Boolean,
    val softWrap: Boolean,
    val retainCards: Int,
    val retainHours: Int,
    val retainBytes: Long
)

enum class AgentDisplayMode {
    FULL,
    CONCLUSION_ONLY,
    CONCLUSION_THEN_FULL
}
```

Defaults:

- `agentDisplayMode = CONCLUSION_THEN_FULL`
- `autoOpenOnNewOutput = false`
- `markReadWhenVisible = true`
- `softWrap = true`
- `retainCards = 200`
- `retainHours = 48`
- `retainBytes = 5 MiB` per conversation

Eviction MUST remove the oldest cards until all configured limits are satisfied. The currently open card and pending user cards MUST not be evicted.

### 7.6 Input policy

```kotlin
data class InputPolicy(
    val defaultSubmitMode: SubmitMode,
    val requireReviewForAsr: Boolean,
    val requireReviewForMorse: Boolean,
    val allowMultilineAutoSubmit: Boolean,
    val maxInputUtf8Bytes: Int,
    val allowedKeyCommands: Set<KeyCommand>
)

enum class SubmitMode { PASTE_ONLY, PASTE_AND_ENTER }
```

Defaults:

- ASR and Morse always require review.
- `PASTE_AND_ENTER` for single-line text.
- Multiline text defaults to `PASTE_ONLY` unless explicitly enabled per conversation.
- Maximum one reply is 64 KiB UTF-8.

---

## 8. tmux bridge

### 8.1 Process model

The bridge runs one daemon per host user account. It may manage multiple tmux servers.

Commands:

```text
poker-dealer-bridge serve
poker-dealer-bridge pair
poker-dealer-bridge doctor
poker-dealer-bridge list-servers
poker-dealer-bridge emit ...        # optional structured-agent input
```

Configuration lives in an OS-appropriate user config directory and MUST be file-permission protected. Secrets MUST never appear in process arguments or logs.

### 8.2 tmux compatibility

- Minimum supported tmux version: **3.2**.
- On startup, the bridge MUST run `tmux -V` for each configured executable and expose the result.
- Unsupported versions MUST produce a clear host/server capability error; the bridge MUST not silently degrade into unsafe shell scraping.

### 8.3 Discovery

For each configured server, enumerate panes using `list-panes -a -F` with tab-delimited fields including at least:

```text
session_id
session_name
window_id
window_index
window_name
pane_id
pane_index
pane_title
pane_current_command
pane_dead
pane_width
pane_height
pane_in_mode
pane_pipe
```

The bridge MUST correctly escape or length-prefix field values. It MUST not parse human-formatted default tmux output.

Discovery events:

- initial full topology snapshot;
- session/window/pane add;
- rename/metadata change;
- pane death/close;
- server unavailable/recovered.

### 8.4 Primary output source: tmux control mode

The bridge MUST use tmux control mode as the primary live event source.

Properties relied upon:

- commands and responses are framed with `%begin`, `%end`, and `%error`;
- pane output arrives as `%output` or `%extended-output` notifications;
- lifecycle notifications report sessions/windows/panes changing;
- output is escaped and must be decoded before terminal parsing.

The bridge SHOULD maintain one control-mode client per attached tmux session unless a verified implementation can receive all required pane output through fewer clients. It MUST set flags that prevent its virtual client from changing ordinary client layout size. It MUST implement backpressure and recover from `%pause`, “too far behind,” process exit, and server restart.

The bridge MUST NOT claim or overwrite a pane’s `pipe-pane` configuration.

### 8.5 Bootstrap and recovery: `capture-pane`

Use `capture-pane` for:

- first attachment snapshot;
- recovery after control-mode reconnect;
- sequence-gap repair;
- terminal parser resynchronization;
- explicit diagnostics.

Preferred normalized snapshot behavior:

- print to stdout;
- join wrapped lines where appropriate;
- capture a bounded tail of scrollback;
- omit ANSI formatting unless a parser test specifically needs it;
- preserve Unicode and meaningful whitespace.

No capture operation may block the bridge event loop. Each pane MUST have rate limiting and cancellation.

### 8.6 Control-mode decoder

The decoder MUST:

- distinguish command response blocks from asynchronous notifications;
- decode tmux octal escapes exactly;
- preserve arbitrary UTF-8 byte sequences until decoding boundaries are complete;
- tolerate output split across OS reads;
- reject malformed frames without crashing the daemon;
- expose fixture-driven tests copied from real tmux sessions;
- maintain a monotonic event sequence per bridge connection.

### 8.7 Terminal parsing

Maintain an independent terminal parser per attached pane.

Inputs:

- decoded `%output` bytes;
- pane width/height changes;
- initial snapshot;
- parser reset events.

Outputs:

- normalized visible screen;
- normalized appended printable text where determinable;
- cursor and alternate-screen state;
- stable snapshots for card extractors.

Normalization MAY:

- apply carriage-return overwrite semantics;
- remove ANSI/OSC control sequences after applying their terminal effect;
- normalize CRLF to LF;
- remove NUL bytes;
- trim cells beyond terminal line width;
- expand tabs deterministically.

Normalization MUST NOT:

- paraphrase;
- summarize;
- reorder lines;
- drop printable Unicode;
- collapse distinct paragraphs;
- remove code or command text because it appears repetitive.

### 8.8 Capture profiles

#### 8.8.1 `RAW_LINES`

- Convert printable output into line-oriented chunks.
- Coalesce consecutive output separated by less than `settleMs`.
- Default `settleMs = 800`.
- Commit after idle or when the card reaches 512 KiB.
- Role is `TERMINAL` unless a local Dealer/Poker input created the matching user turn.

#### 8.8.2 `SCREEN_DIFF`

- Debounce terminal screen revisions.
- Normalize lines and compare with the previous stable screen using a deterministic diff.
- Add inserted output to the current card.
- When rewrites affect the current open card, issue a replacement revision rather than appending duplicated text.
- UI chrome filters MAY be configured per conversation as explicit regexes. No default regex may remove ordinary text.
- This is the default for terminal UIs and interactive agents.

#### 8.8.3 `SHELL_OSC133`

- Use shell integration prompt/output markers when present.
- Produce exact user-command and command-output regions when markers are valid.
- Fall back to `SCREEN_DIFF` when markers disappear.
- Never infer markers from text that merely resembles a prompt.

#### 8.8.4 `STRUCTURED_AGENT`

- Accept explicit local events from an agent wrapper or `poker-dealer-bridge emit`.
- Structured events may specify role, message ID, full text, conclusion, begin/append/commit, and awaiting-input state.
- Structured events are preferred over terminal heuristics for the same message.
- Raw tmux output remains available for recovery but MUST not duplicate a structured card.

### 8.9 Card extraction and turn correlation

The bridge sends terminal events; Dealer owns final card sequence assignment.

Rules:

1. A confirmed Dealer/Poker reply immediately creates a local `USER` card in Dealer with `LOCAL_PENDING` state.
2. The bridge acknowledges input acceptance or rejection.
3. On successful paste/key injection, Dealer marks the card `DELIVERED`.
4. Output that follows the submitted input is associated with an open `AGENT` card for agent-oriented profiles or `TERMINAL` card otherwise.
5. The open output card may receive append or replacement revisions.
6. Idle commit is heuristic, not proof that the process is waiting for input.
7. Input typed by another tmux client cannot always be classified as a user message. Unless shell markers or structured events prove the role, classify it as terminal content.
8. A pane can emit output without any user turn; create a terminal/system card rather than inventing a user card.

### 8.10 Safe input injection

The bridge exposes only these input operations:

- paste UTF-8 text;
- optionally send `Enter` after paste;
- send an allowlisted tmux key token;
- cancel a pending bridge request before execution when possible.

For paste:

1. Validate host/server/pane target and attachment authorization.
2. Reject NUL and payloads above the configured limit.
3. Spawn tmux directly with argv; never invoke `sh -c`.
4. Load content through tmux buffer stdin using a unique buffer name.
5. Paste that buffer into the target pane.
6. Delete the buffer.
7. Send `Enter` as a separate allowlisted key only when requested.

Text MUST never be interpolated into a shell command or tmux format string.

Default allowed key tokens:

```text
Enter, Escape, Tab, BSpace,
Up, Down, Left, Right,
C-c, C-d, C-z,
PageUp, PageDown
```

Additional keys require an explicit per-conversation allowlist. There is no generic “run command” operation.

### 8.11 Bridge transport and pairing

- Bridge serves WebSocket over TLS (`wss`) for non-loopback endpoints.
- On first start, bridge generates a long-lived TLS identity and a 256-bit pairing secret.
- Dealer pins the bridge certificate/public-key fingerprint during pairing.
- Each connection performs a fresh HMAC-SHA256 challenge-response using the pairing secret.
- Replay protection MUST include nonce, timestamp window, and connection ID.
- Secrets are never logged.
- Loopback plaintext MAY be supported only for `127.0.0.1` and still requires application authentication.
- Remote binding MUST default to a specifically configured Tailscale address, never `0.0.0.0`.
- The bridge MUST reject unauthenticated pane listing as well as input.
- Multiple failed authentication attempts MUST be rate-limited.

### 8.12 Bridge protocol v1

Use UTF-8 JSON text frames for control and normalized text. Use binary frames only if later required for efficiency; v1 does not need binary bridge frames.

Common envelope:

```json
{
  "protocol": "poker-dealer-bridge",
  "version": 1,
  "type": "pane.output",
  "message_id": "01J...",
  "connection_id": "01J...",
  "sent_at_ms": 1784600000000,
  "sequence": 418,
  "reply_to": null,
  "payload": {}
}
```

Required client→bridge types:

```text
client.hello
auth.response
host.snapshot.request
pane.attach
pane.detach
pane.snapshot.request
pane.input.paste
pane.input.key
ping
```

Required bridge→client types:

```text
server.hello
auth.challenge
auth.result
host.snapshot
host.delta
pane.attached
pane.detached
pane.output.begin
pane.output.append
pane.output.replace
pane.output.commit
pane.state
pane.snapshot
pane.input.result
error
pong
```

Every mutating request MUST have a unique `message_id`; the bridge MUST retain a bounded deduplication cache so reconnect/retry cannot paste a reply twice.

Example input request:

```json
{
  "protocol": "poker-dealer-bridge",
  "version": 1,
  "type": "pane.input.paste",
  "message_id": "reply-92",
  "connection_id": "dealer-1",
  "sent_at_ms": 1784600000000,
  "sequence": 91,
  "payload": {
    "tmux_server_id": "spark-default",
    "pane_id": "%17",
    "text": "Proceed with the transport abstraction.",
    "submit_mode": "paste_and_enter"
  }
}
```

Example result:

```json
{
  "protocol": "poker-dealer-bridge",
  "version": 1,
  "type": "pane.input.result",
  "message_id": "result-92",
  "connection_id": "bridge-1",
  "sent_at_ms": 1784600000150,
  "sequence": 419,
  "reply_to": "reply-92",
  "payload": {
    "status": "delivered",
    "tmux_server_id": "spark-default",
    "pane_id": "%17"
  }
}
```

---

## 9. Dealer application

### 9.1 Layering

Dealer MUST separate:

```text
UI
Application/use cases
Domain
Persistence
Bridge transport
Poker transport
Android platform services
Vendor SDK adapters
```

No Compose screen may issue bridge or Rokid SDK calls directly.

### 9.2 Core services

Required interfaces/classes, names may vary but responsibilities may not:

- `HostRepository`
- `BridgeConnectionManager`
- `TmuxTopologyRepository`
- `AttachmentRepository`
- `ConversationRepository`
- `CardRepository`
- `CardAssembler`
- `PokerSyncEngine`
- `InputRouter`
- `AsrCoordinator`
- `InputDeviceRepository`
- `DealerConnectionService`

### 9.3 Database

Room MUST persist at least:

- hosts;
- encrypted-secret references and certificate fingerprints;
- tmux server definitions;
- pane attachment definitions;
- conversations and settings;
- cards and revisions/current content;
- per-conversation sequence cursors;
- Poker sync cursor;
- pending input requests and idempotency IDs;
- input-device mappings;
- Morse calibration/settings.

Card text MUST not be included in routine analytics or crash breadcrumbs. Debug export is an explicit user action.

### 9.4 Screens

#### 9.4.1 Connection dashboard

Show:

- Poker connected/disconnected/authorizing;
- each bridge connected/disconnected/error;
- attached pane count;
- unread card count;
- foreground service state;
- last synchronization time.

#### 9.4.2 Hosts

User can:

- pair a bridge;
- add/edit endpoint;
- verify certificate fingerprint;
- enable/disable autoconnect;
- run diagnostics;
- revoke a pairing;
- view tmux version and server availability.

#### 9.4.3 Pane discovery

Hierarchical list:

```text
Host
  tmux server
    session
      window
        pane
```

Each pane row shows pane ID, title, current command, state, and attachment status. Attach/detach is explicit.

#### 9.4.4 Conversation settings

Per conversation:

- alias;
- capture profile;
- agent display mode;
- conclusion marker/regex settings;
- history limits;
- automatic opening policy;
- submit mode;
- allowed key commands;
- multiline behavior;
- UI chrome filters;
- reattachment rule;
- clear history;
- detach.

#### 9.4.5 Conversation preview

Dealer MUST provide a phone preview of the same cards and statuses visible in Poker. This is essential for debugging output extraction without wearing glasses.

#### 9.4.6 Input diagnostics

Show raw events from:

- Rokid button/touch callbacks relayed by Poker;
- phone-connected Bluetooth ring;
- Poker-connected ring events relayed by Poker;
- Android `KeyEvent`, device ID, descriptor, vendor/product IDs where available;
- down/up timestamps and repeat count.

Allow learning and assigning mappings.

### 9.5 Foreground service

Dealer maintains live sockets and the glasses link in a foreground service while enabled.

- Use the Android foreground-service type(s) justified by the actual implementation, including `connectedDevice` for the glasses link.
- Add `microphone` only when Dealer itself opens the phone microphone, and start that capture from a visible user action in compliance with while-in-use restrictions.
- Do not use WorkManager for live WebSocket or glasses connections.
- Persistent notification must state connection counts and provide a stop action.
- Stopping the service disconnects transports but does not delete attachments/history.
- Autostart after reboot MUST follow current Android restrictions; never silently bypass platform controls.

### 9.6 Card assembly

Dealer receives bridge output events and converts them to cards.

Requirements:

- exactly-once logical application using bridge `message_id` and sequence;
- open-card append/replace/commit support;
- deterministic role assignment;
- normalization only as defined in Section 8.7;
- full content retained even when Poker initially displays a conclusion;
- split oversized cards without data loss;
- transactionally persist card revision before synchronizing to Poker;
- unread count increments only for committed or materially updated cards not currently visible in Poker.

### 9.7 Conclusion handling

Dealer MUST NOT call an LLM or generative summarizer in v1.

A conclusion may come only from:

1. a `conclusion` field in a structured-agent event;
2. explicit configured markers, for example:

```text
<<<POKER_CONCLUSION>>>
Use CXR-M/CXR-S behind a transport abstraction.
<<<END_POKER_CONCLUSION>>>
```

3. a user-configured deterministic section rule such as an exact Markdown heading.

If no explicit conclusion is available:

- `FULL` shows full text;
- `CONCLUSION_THEN_FULL` falls back to full text;
- `CONCLUSION_ONLY` MUST also fall back to full text and show a small “no conclusion field” indicator rather than hiding the answer.

Conclusion extraction MUST never mutate `fullText`.

---

## 10. Poker application

### 10.1 UI states

Top-level states:

```text
BOOTING
AUTHORIZING
DISCONNECTED
SYNCING
DECK
CARD_READER
ASR_LISTENING
ASR_REVIEW
MORSE_COMPOSE
INPUT_SENDING
ERROR
```

A transient disconnect while reading MUST preserve the visible card and scroll offset. Input send is disabled until Dealer is connected, but an unsent local draft may remain queued for explicit confirmation after reconnect.

### 10.2 Deck

Each conversation entry shows:

- alias;
- host name;
- session/window/pane metadata in compact form;
- unread count;
- online/offline/stale status;
- optional “input expected” indicator when known;
- timestamp of newest card.

Deck ordering default:

1. pinned conversations;
2. conversations with unread cards, newest first;
3. remaining conversations, newest first.

### 10.3 Card reader

Display one card at a time with:

- conversation alias;
- role/source label;
- timestamp;
- delivery/open/committed status;
- full or conclusion presentation according to policy;
- body text;
- card position, e.g. `3 / 12`;
- scroll progress;
- live-update indicator.

Long text requirements:

- soft wrap by default;
- preserve paragraphs, code indentation, punctuation, and Unicode;
- scroll vertically without shrinking text to unreadable size;
- virtualize visual lines; do not allocate a UI node per character;
- remember scroll offset per card while cached;
- do not force-scroll when the user has moved away from the bottom;
- when a live card grows and the user is not following, show a compact “new lines” indicator;
- a command toggles conclusion/full view when both exist;
- no ellipsis may imply text was discarded; any viewport clipping must have a visible continuation indicator.

### 10.4 Card navigation

Navigation has two levels:

- **Within card:** vertical scroll/page.
- **Between cards:** previous/next card.

The default bindings in Section 12 MUST be remappable because exact Rokid event semantics vary by firmware.

### 10.5 Cache

Poker cache defaults:

- 20 newest cards for active conversation;
- 5 newest cards for each inactive conversation;
- 2 MiB global decoded-text ceiling;
- current visible card and draft are pinned;
- request older pages from Dealer on demand.

Poker is never the source of truth for delivered card history. On cache loss it requests a snapshot from Dealer.

### 10.6 Privacy

- Poker MUST not retain microphone audio after ASR finalization/cancellation.
- Poker SHOULD keep card cache ephemeral unless vendor lifecycle requires a small persistent cache.
- A privacy mode may hide body text until explicit open.
- Disconnection/error screens MUST not expose terminal content in logs.

---

## 11. Dealer↔Poker protocol v1

### 11.1 Encoding and frame size

- UTF-8 JSON for control and card text.
- Application-level frame size is negotiated in `hello`.
- Until measured, assume a conservative **4096-byte maximum frame**.
- `card.append` text chunks MUST default to at most 2048 UTF-8 bytes so envelope overhead remains below the conservative limit.
- Never split inside a UTF-8 code point.
- If the vendor SDK supplies a smaller limit, negotiate and use it.
- Compression is out of scope for v1.

### 11.2 Envelope

```json
{
  "protocol": "poker-dealer",
  "version": 1,
  "type": "card.append",
  "message_id": "01J...",
  "session_id": "01J...",
  "sent_at_ms": 1784600000000,
  "sequence": 184,
  "reply_to": null,
  "conversation_id": "conv-17",
  "payload": {}
}
```

- `sequence` is monotonic for the active Dealer↔Poker session.
- `message_id` enables dedupe.
- Gaps trigger `sync.request` rather than speculative application.
- Acknowledgement means Poker accepted the protocol frame, not that a tmux reply was delivered.

### 11.3 Required Dealer→Poker messages

```text
server.hello
sync.snapshot
sync.complete
conversation.upsert
conversation.remove
conversation.state
card.begin
card.append
card.replace
card.commit
card.delete
history.page
reply.status
asr.partial
asr.final
asr.error
input.mapping
error
ping
```

### 11.4 Required Poker→Dealer messages

```text
client.hello
sync.request
ack
history.request
conversation.visible
card.visible
reply.submit
reply.cancel
input.raw
input.command
asr.start
asr.audio.begin
asr.audio.chunk or vendor audio-channel equivalent
asr.audio.end
asr.cancel
ping
```

### 11.5 Card synchronization

Start a card:

```json
{
  "type": "card.begin",
  "conversation_id": "conv-17",
  "payload": {
    "card_id": "card-184",
    "card_sequence": 184,
    "revision": 1,
    "role": "agent",
    "state": "open",
    "created_at_ms": 1784600000000,
    "conclusion": null
  }
}
```

Append:

```json
{
  "type": "card.append",
  "conversation_id": "conv-17",
  "payload": {
    "card_id": "card-184",
    "revision": 2,
    "chunk_index": 0,
    "text": "The implementation should use tmux control mode..."
  }
}
```

Replace/resynchronize full current content:

```json
{
  "type": "card.replace",
  "conversation_id": "conv-17",
  "payload": {
    "card_id": "card-184",
    "revision": 3,
    "full_text_utf8_bytes": 8120,
    "chunk_index": 0,
    "chunk_count": 4,
    "text": "..."
  }
}
```

Commit:

```json
{
  "type": "card.commit",
  "conversation_id": "conv-17",
  "payload": {
    "card_id": "card-184",
    "revision": 4,
    "updated_at_ms": 1784600004500
  }
}
```

### 11.6 Reply submission

Poker freezes the target conversation and referenced card when composition starts.

```json
{
  "type": "reply.submit",
  "message_id": "reply-92",
  "conversation_id": "conv-17",
  "payload": {
    "reply_to_card_id": "card-184",
    "input_method": "asr",
    "text": "Proceed with the transport abstraction.",
    "submit_mode": "paste_and_enter"
  }
}
```

Dealer response stages:

```text
accepted    — validated and persisted locally
sending     — bridge request in progress
delivered   — bridge reports successful tmux injection
rejected    — validation or pane state rejected it
unknown     — connection lost after possible execution; do not blindly retry
```

An `unknown` result requires user action or bridge idempotency reconciliation. It MUST NOT auto-resubmit under a new request ID.

### 11.7 Resynchronization

On connection/reconnection Poker sends:

```json
{
  "type": "sync.request",
  "payload": {
    "last_session_id": "old-session",
    "last_sequence": 183,
    "conversation_cursors": {
      "conv-17": 180,
      "conv-24": 51
    }
  }
}
```

Dealer either:

- sends missing events if retained and session-compatible; or
- sends a bounded full snapshot of conversations and cached cards.

Snapshot application MUST be transactional from Poker’s point of view: do not show half-replaced deck state.

---

## 12. Input subsystem

### 12.1 Normalized input events

All hardware-specific callbacks become normalized events before UI commands are chosen.

```kotlin
sealed interface PhysicalInputEvent {
    val deviceId: String
    val eventTimeMs: Long

    data class KeyDown(...): PhysicalInputEvent
    data class KeyUp(...): PhysicalInputEvent
    data class Tap(...): PhysicalInputEvent
    data class Swipe(...): PhysicalInputEvent
    data class TouchDown(...): PhysicalInputEvent
    data class TouchUp(...): PhysicalInputEvent
    data class VendorGesture(...): PhysicalInputEvent
}
```

Do not map based solely on Android key code. Mapping identity SHOULD include device descriptor/vendor/product ID when available.

### 12.2 Default reading bindings

These are defaults, not assumptions about hardware availability:

| Physical action | Default Poker command |
|---|---|
| Touch swipe up/down | Scroll card up/down |
| Touch swipe left/right | Previous/next card |
| Function button single press | Select/open or toggle follow-live |
| Function button long press and hold | ASR push-to-talk; release ends capture |
| Function button double press | Enter Morse compose mode |
| Ring previous/next | Scroll; at card boundary move card |
| Ring select | Select/open |
| Ring long press | ASR push-to-talk |

Every mapping MUST be editable from Dealer after raw-event diagnostics.

### 12.3 Input focus safety

- Composition captures `conversationId` at start.
- Switching the visible conversation does not retarget an existing draft.
- Review screen always displays destination alias and pane metadata.
- No ASR or Morse text auto-sends.
- A stale/offline pane blocks send and retains the draft.
- Command mode and text mode are visually distinct.
- Dictated phrases such as “control C” are text unless the user explicitly chooses a key command.

---

## 13. Morse input

### 13.1 State machine

```text
IDLE
  └─ enter Morse mode
COMPOSING_SYMBOL
  ├─ press/release -> dot or dash
  ├─ character gap -> decode character
  ├─ word gap -> append space
  ├─ backspace -> remove symbol/character
  └─ finish -> REVIEW
REVIEW
  ├─ send -> SENDING
  ├─ edit -> COMPOSING_SYMBOL
  └─ cancel -> IDLE
SENDING
  ├─ delivered -> IDLE
  └─ error -> REVIEW
```

### 13.2 Timing

Use a configurable timing unit rather than hardcoded independent thresholds.

```kotlin
data class MorseTiming(
    val unitMs: Long = 160,
    val dotMaxUnits: Double = 2.0,
    val characterGapUnits: Double = 3.0,
    val wordGapUnits: Double = 7.0,
    val adaptive: Boolean = true
)
```

- Press duration below `dotMaxUnits * unitMs` is dot; otherwise dash.
- Character and word gaps follow unit multiples.
- Dealer provides a calibration flow using at least 10 dots and 10 dashes.
- Adaptive timing may update a bounded moving estimate but MUST preserve a reset option.
- Key-down and key-up timestamps are required. A ring emitting only completed clicks cannot use duration Morse; support a two-key dot/dash mapping instead.

### 13.3 Character set

MVP MUST support:

- A–Z;
- 0–9;
- period, comma, question mark, apostrophe, slash, hyphen, parentheses, colon, equals, plus, quotation mark, at sign;
- space;
- backspace;
- clear;
- send;
- cancel.

Unknown sequences remain visible as dots/dashes and produce no silent replacement character.

### 13.4 UI

Poker shows:

```text
MORSE · Spark / codex

.... . .-.. .-.. ---
HELLO_

Swipe back: delete
Hold confirm: review
```

The exact commands are remappable. Review shows the complete decoded text and exact tmux destination.

### 13.5 Tests

Unit tests MUST cover:

- every supported character;
- boundary durations;
- character and word gaps;
- deletion in partial symbol and decoded text;
- adaptive calibration bounds;
- disconnect during composition;
- device lacking key-up;
- no duplicate send on repeated hardware event.

---

## 14. ASR input

### 14.1 Provider abstraction

```kotlin
interface AsrProvider {
    val capabilities: AsrCapabilities
    fun transcribe(audio: Flow<PcmChunk>, config: AsrConfig): Flow<AsrEvent>
}
```

Required event types:

```text
started
partial(text)
final(text)
no_speech
error(code, message)
ended
```

### 14.2 Provider priority

At runtime use the first verified provider in this order:

1. Rokid SDK native ASR transcript, if exposed and acceptable.
2. Rokid microphone PCM streamed to Dealer and passed to a recognizer that supports an injected audio source.
3. Dealer-local offline ASR implementation, if packaged/configured.
4. Phone microphone + Android on-device/system `SpeechRecognizer` as an explicit fallback.

The UI MUST show the active microphone and recognizer source. It MUST NOT silently use the phone microphone when the user expects the glasses microphone.

### 14.3 Audio format

Preferred interchange format when PCM is available:

- mono;
- signed PCM 16-bit little-endian;
- 16 kHz unless the SDK only provides another rate;
- timestamped chunks;
- sequence number per audio stream;
- maximum capture duration default 60 seconds.

If the Rokid SDK has a dedicated audio stream, use it rather than base64-encoding PCM into small JSON frames. The control protocol still carries stream start/end metadata.

### 14.4 Android recognizer injection

On Android API 33+, a recognizer may accept a `ParcelFileDescriptor` through `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Provider support is implementation-dependent. Dealer MUST probe it and must not assume every installed recognition service honors the supplied audio source.

If unsupported, choose another provider and report the reason in diagnostics.

### 14.5 UX and safety

Flow:

```text
long press
  -> start capture
  -> partial text appears
release / endpoint
  -> final text
  -> review with exact destination
  -> Send / Redo / Cancel
```

Rules:

- no auto-send;
- partial transcript is visually provisional;
- punctuation and vocabulary bias may be configured with project, host, session, and command names;
- user can edit the final transcript on Dealer; Poker editing is limited to Morse/backspace in v1;
- audio is deleted from memory/storage immediately after finalization or cancellation;
- no audio content in logs;
- capture start must be a deliberate user action;
- disconnect during capture ends capture and preserves only the latest text draft when possible.

### 14.6 Tests

- mock PCM stream to partial/final transcript;
- recognizer rejects injected audio source;
- 60-second timeout;
- cancel and resource cleanup;
- target conversation frozen during navigation;
- repeated start/stop;
- no audio persistence;
- no duplicate reply submission.

---

## 15. Bluetooth ring support

### 15.1 Supported v1 class

V1 supports rings that appear as standard input devices to either Poker or Dealer:

- keyboard/HID;
- media buttons;
- accessibility switch-like key events;
- vendor SDK events only when the user supplies official documentation and library.

No BLE protocol reverse engineering is required.

### 15.2 Pairing locations

- **Ring paired to Poker:** Poker normalizes events and sends diagnostics/commands to Dealer.
- **Ring paired to Dealer:** Dealer normalizes events; it forwards relevant commands to Poker’s active UI state or directly controls composition.

The active input owner MUST be visible in diagnostics to avoid double handling.

### 15.3 Learn mode

Dealer provides a wizard:

1. Select detected device.
2. Press/hold each physical control.
3. Record down/up, duration, repeat, and key code.
4. Assign Poker command.
5. Validate with a live preview.
6. Save mapping by stable device identity.

Mappings MUST be exportable as redacted JSON without secrets or conversation content.

---

## 16. Reliability and offline behavior

### 16.1 Connection state

Each link has an explicit state machine:

```text
DISABLED
CONNECTING
AUTHENTICATING
SYNCING
CONNECTED
BACKING_OFF
ERROR
```

Use bounded exponential backoff with jitter. User-initiated reconnect bypasses the current delay. Authentication failures do not loop aggressively.

### 16.2 Idempotency

- Every mutating operation has a stable request ID.
- Dealer persists pending reply IDs before transmission.
- Bridge deduplicates recent input request IDs.
- Poker deduplicates protocol message IDs and card revisions.
- Reconnect retries reuse the same request ID.
- A reply with uncertain execution state is surfaced as `UNKNOWN`, not automatically repeated.

### 16.3 Ordering

- Bridge events have connection sequence numbers.
- Dealer↔Poker events have session sequence numbers.
- Card sequence is independent and per conversation.
- Gaps trigger snapshot/resync.
- Never apply later card append chunks before missing earlier chunks; request replace/snapshot instead.

### 16.4 Backpressure

- Bridge bounds per-pane output queues.
- Dealer bounds bridge and Poker queues.
- Poker transport sends high-priority input/status before background history pages.
- When output outpaces transport, coalesce revisions and send a current `card.replace`; do not enqueue every intermediate token forever.
- Any data loss at a lower layer triggers `capture-pane`/card snapshot repair and a visible diagnostic counter.

### 16.5 Pane lifecycle

- Pane death marks conversation stale and commits any open card with a system note.
- A restarted pane with a new pane ID is not silently substituted.
- User-configured reattachment may match host + tmux server + exact session/window aliases + current command, but Dealer must display that reassignment.
- Server restart triggers topology refresh and conversation state transition.

---

## 17. Security and privacy

### 17.1 Threat model

Protect against:

- an unauthenticated local/network client listing panes;
- replaying a prior reply;
- sending a reply to the wrong pane;
- shell injection through reply text;
- leaking pairing secrets in logs/backups;
- logging sensitive terminal content;
- accidentally exposing bridge on public interfaces;
- duplicate input after reconnect;
- a compromised Poker transport issuing arbitrary tmux commands.

### 17.2 Mandatory controls

- TLS pinning and HMAC pairing for remote bridges.
- Loopback binding by default.
- Tailscale-interface binding for Spark.
- Strict protocol schema validation.
- No arbitrary shell endpoint.
- Direct process argv execution.
- tmux buffer stdin for text.
- Key-token allowlist.
- Per-conversation attachment authorization.
- 64 KiB input maximum.
- Rate limiting.
- Secrets in Android Keystore and protected host files.
- Content-redacted production logs.
- Debug content logging disabled by default and automatically time-limited when enabled.
- An always-visible destination on input review.

### 17.3 Data retention

- Dealer stores only configured recent history.
- Poker stores only its bounded recent cache.
- Audio is not retained.
- Clearing a conversation history deletes its cards and sync cursor but not necessarily the tmux pane scrollback.
- Detaching may optionally clear history; default is retain until normal eviction.

---

## 18. Observability and diagnostics

### 18.1 Structured logs

Use structured logs with categories:

```text
bridge.connection
bridge.auth
tmux.control
tmux.topology
tmux.output
card.assembly
poker.transport
poker.sync
input.device
input.morse
input.asr
reply.delivery
```

Production logs include IDs, counts, durations, and error codes, not card text, reply text, audio, or secrets.

### 18.2 Metrics shown in diagnostics

- current protocol/SDK versions;
- last connected times;
- message/frame counts;
- sequence gaps;
- reconnect count;
- dedupe count;
- current queue depths;
- dropped/coalesced intermediate revisions;
- card output latency;
- reply delivery latency;
- negotiated Rokid frame limit;
- active ASR provider and audio source;
- raw input-device events;
- tmux version and control-mode state.

### 18.3 Doctor command

`poker-dealer-bridge doctor` checks:

- tmux executable and version;
- configured sockets;
- control-mode startup;
- pane listing;
- TLS identity permissions;
- bind address;
- config permissions;
- ability to load/paste into a disposable test pane only when explicitly requested.

It MUST not modify a real pane by default.

---

## 19. Testing strategy

### 19.1 Bridge unit tests

Mandatory fixtures/tests:

- control-mode block parser;
- `%output` octal decoder;
- split read boundaries;
- invalid protocol lines;
- UTF-8 across chunks;
- terminal resize;
- alternate screen;
- carriage-return progress output;
- screen diff;
- RAW_LINES idle coalescing;
- structured-agent dedupe;
- HMAC challenge and replay rejection;
- WebSocket message validation;
- input request dedupe;
- argv-safe tmux invocation;
- payload and key allowlists.

### 19.2 Bridge integration tests

CI starts a real temporary tmux server/socket and fixture programs.

Scenarios:

1. discover sessions/windows/panes;
2. attach and receive output;
3. Unicode output;
4. progress line rewritten with carriage returns;
5. alternate-screen fixture;
6. pane resize;
7. pane close/recreate;
8. bridge reconnect and snapshot repair;
9. paste literal text containing quotes, dollar signs, backticks, newlines, and shell metacharacters without interpretation;
10. paste+Enter reaches exactly the chosen pane;
11. duplicate request ID does not paste twice;
12. unauthorized request cannot list or mutate panes.

### 19.3 Shared protocol/domain tests

- JSON round trips;
- version rejection;
- frame chunking/reassembly at UTF-8 boundaries;
- sequence-gap detection;
- card revision ordering;
- card split at 512 KiB without data loss;
- history eviction;
- conclusion fallback;
- no semantic truncation;
- sync snapshot transaction behavior;
- reply state machine.

### 19.4 Dealer tests

- repositories with in-memory Room;
- host connect/reconnect;
- topology updates;
- attach/detach/stale lifecycle;
- bridge event to card transaction;
- foreground service state;
- mock Poker transport sync;
- pending reply survives process recreation;
- unknown delivery does not auto-resend;
- input device mapping;
- ASR provider selection;
- content redaction in logs.

### 19.5 Poker tests

- deck ordering;
- unread counts;
- cache eviction;
- long-card rendering model;
- scroll preservation across revisions;
- no force-scroll when reading above bottom;
- conclusion/full toggle;
- disconnect and snapshot resync;
- input focus frozen to original conversation;
- Morse state machine;
- ASR state machine;
- duplicate hardware-event suppression.

### 19.6 Hardware test matrix

Record exact versions for every run.

Required real-device tests:

- Dealer phone model/Android build;
- Rokid glasses model/firmware;
- CXR mobile and glasses SDK versions;
- Termux tmux version;
- Spark tmux version;
- ring model/firmware when used.

Hardware scenarios:

1. 1 KiB, 4 KiB, 16 KiB, and 100 KiB card transfer.
2. Negotiated frame-size boundary and one byte above.
3. 30-minute continuous agent output.
4. Glasses sleep/wake.
5. Dealer background/foreground.
6. Phone network switch.
7. Tailscale disconnect/reconnect.
8. Button press, long press, double press.
9. All touch gestures.
10. Ring down/up timing.
11. Glasses microphone audio path.
12. ASR partial/final/cancel.
13. Two panes producing output simultaneously.
14. Reply while another conversation receives output.
15. No duplicate reply after forced reconnect.

### 19.7 Performance targets

Measured on a healthy local/Tailscale connection:

- tmux output event to Dealer card update: p95 ≤ 500 ms, excluding extractor idle commit;
- Dealer card update to first visible Poker update: p95 ≤ 700 ms;
- confirmed Poker reply to bridge delivery result: p95 ≤ 750 ms;
- Poker input gesture feedback: ≤ 100 ms;
- no dropped final text under a sustained 50 KiB/minute aggregate output rate;
- memory remains bounded by configured caches/queues.

Failure to hit a target must produce a measured issue, not a silent relaxation.

---

## 20. Implementation milestones

The Codex worker should implement in this order. Each milestone ends with tests and a commit.

### M0 — Repository bootstrap and mock vertical slice

Deliver:

- Gradle/Kotlin and Rust workspaces;
- mock Dealer and Poker apps;
- shared card/protocol models;
- loopback transport;
- one fake conversation with a long scrollable card;
- CI for mock Android modules and Rust;
- no proprietary SDK dependency in default CI.

Exit criteria:

- fresh clone can run documented test/build tasks;
- Poker mock displays and scrolls a 20,000-character card;
- protocol chunk/reassembly tests pass.

### M1 — Secure bridge and tmux discovery

Deliver:

- bridge config/pair/doctor;
- TLS identity and HMAC auth;
- WebSocket server;
- tmux server and pane discovery;
- control-mode parser;
- topology events;
- integration test tmux server.

Exit criteria:

- Dealer test client securely lists panes;
- unauthenticated client cannot list panes;
- pane add/close events work.

### M2 — Bridge output and safe input

Deliver:

- `%output` decoder;
- terminal parser;
- initial/recovery capture;
- RAW_LINES and SCREEN_DIFF profiles;
- constrained paste/key operations;
- request dedupe.

Exit criteria:

- fixture output streams to Dealer test client;
- shell metacharacters paste literally;
- duplicate request does not execute twice;
- reconnect repairs output state.

### M3 — Dealer core

Deliver:

- hosts, connections, discovery UI;
- attach/detach;
- Room schema;
- card assembly/history;
- phone conversation preview;
- foreground service;
- mock Poker synchronization.

Exit criteria:

- local and remote mock/real bridge connections;
- three attached panes maintain independent piles;
- app process recreation preserves attachments and pending states.

### M4 — Poker UI and sync over loopback

Deliver:

- deck;
- card reader;
- long-text virtualization;
- live append/replace;
- recent cache;
- sync/reconnect;
- conclusion/full presentation.

Exit criteria:

- all behavior passes with loopback/fake transport;
- no forced scrolling during live update;
- no semantic truncation.

### M5 — Rokid capability spike and real transport

Deliver:

- minimal official sample builds;
- CXR-M/CXR-S transport adapter, or documented CXR-L contingency if that is the available path;
- measured frame size;
- bidirectional hello/ack;
- lifecycle handling;
- raw input diagnostics;
- microphone capability report encoded in runtime diagnostics.

Exit criteria:

- real Dealer sends a card to real Poker;
- Poker sends an input event to Dealer;
- disconnect/reconnect resyncs;
- SDK-specific code remains isolated.

### M6 — End-to-end cards and replies

Deliver:

- real bridge→Dealer→Poker card flow;
- Poker→Dealer→bridge reply flow;
- reply status;
- multi-conversation switching;
- stale pane behavior.

Exit criteria:

- real Termux and Spark panes work;
- exact target pane is visibly confirmed;
- reconnect does not duplicate replies.

### M7 — Morse

Deliver:

- input mapping and diagnostics;
- calibration;
- full Morse state machine;
- review/send;
- button and supported ring paths.

Exit criteria:

- user enters and sends a phrase containing letters, digits, punctuation, and spaces to the selected pane;
- no accidental send from timing ambiguity.

### M8 — ASR

Deliver:

- Rokid audio path;
- provider abstraction;
- partial/final transcript;
- review/send/cancel;
- fallback provider behavior;
- no audio retention.

Exit criteria:

- a spoken reply using the glasses input path reaches the exact pane after explicit confirmation;
- provider/microphone source is visible;
- failure falls back or explains why.

### M9 — Hardening and release candidate

Deliver:

- hardware matrix results;
- queue/backpressure tests;
- security review;
- redacted diagnostic export;
- signed local builds;
- installation scripts/instructions encoded in repository tooling or final README created by the worker;
- all MVP acceptance criteria.

---

## 21. Final acceptance scenarios

### Scenario A — Attach Spark Codex pane

1. Spark bridge is online on its Tailscale address.
2. Dealer authenticates and lists panes.
3. User attaches `%17`, aliases it `Spark · Codex`.
4. Output appears as a live agent/terminal card.
5. Poker shows unread count and opens the conversation.
6. The user scrolls a long answer from beginning to end.
7. No text is summarized or silently omitted.

### Scenario B — Concurrent panes

1. Attach Termux `%3`, Spark `%17`, and Spark `%24`.
2. All emit output concurrently.
3. Dealer assigns events to correct conversations.
4. Poker deck shows independent unread counts.
5. Switching cards never changes the target of an already-open draft.

### Scenario C — Morse reply

1. User opens `Spark · Codex`.
2. Double press enters Morse mode.
3. User composes `YES, PROCEED.`.
4. Poker shows decoded text and destination.
5. User confirms.
6. Dealer persists pending card and sends one idempotent bridge request.
7. Bridge pastes literal text and Enter into `%17`.
8. Poker shows delivered status.

### Scenario D — ASR reply

1. User long-presses the configured control.
2. Poker captures from the verified glasses microphone route.
3. Partial transcript appears.
4. Release ends capture.
5. Final transcript and destination are shown.
6. User confirms.
7. Exactly one reply reaches the selected pane.
8. Audio is no longer retained.

### Scenario E — Reconnect

1. A long card is open and Poker has received revision 8.
2. Glasses link disconnects while Dealer receives revisions 9–15.
3. Poker reconnects and requests sync.
4. Dealer sends missing events or a replacement snapshot.
5. Poker displays revision 15 once, retains valid scroll position when possible, and shows no duplicate card.

### Scenario F — Uncertain delivery

1. Poker submits a reply.
2. Dealer sends it with request ID `R`.
3. Connection drops before Dealer receives result.
4. Dealer marks result unknown and reconciles request ID `R` after reconnect.
5. Dealer never sends the text again under a new ID automatically.
6. User can inspect status before deciding any manual action.

### Scenario G — Security

1. Unpaired client connects to bridge.
2. Pane listing and input are rejected.
3. A paired client sends text containing `` `$(touch /tmp/pwned)` ``.
4. The literal characters appear in the target pane input; no host-side shell interpretation occurs in the bridge.
5. A disallowed key token is rejected.
6. Logs contain request IDs and error codes, not the reply text or secrets.

---

## 22. Worker execution rules

The local Codex worker MUST follow these rules:

1. Start by reading this file completely.
2. Treat this file as the implementation contract, not as optional guidance.
3. Do not block all development on proprietary Rokid SDK access. Build mock transport and all core behavior first.
4. Do not invent Rokid method names. Copy only from the exact official SDK/sample available locally and isolate them in adapters.
5. Do not add third-party conversation connectors.
6. Do not replace full text with summaries.
7. Do not use `pipe-pane` as the primary capture path or overwrite an existing pane pipe.
8. Do not expose arbitrary shell execution.
9. Do not interpolate user text into shell commands.
10. Write tests before declaring a milestone complete.
11. Commit after each milestone with a focused message.
12. Keep CI independent of private SDK artifacts.
13. When hardware behavior differs from this spec, preserve protocol/domain semantics and change only the adapter or input mapping where possible.
14. Any unavoidable product-level deviation requires an explicit `SPEC.md` amendment in the same commit.
15. Leave the repository in a buildable/testable state after every milestone.

### 22.1 Initial worker task

The first worker run should complete M0 and begin M1. It should not attempt the real Rokid integration before the mock vertical slice and shared protocol tests pass.

### 22.2 Required build gates

At minimum, CI/local aggregate checks must run equivalents of:

```text
./gradlew test lint
cargo fmt --check --manifest-path bridge/Cargo.toml
cargo clippy --manifest-path bridge/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path bridge/Cargo.toml
```

If the Poker vendor module cannot build without local SDK artifacts, the default task must build a mock Poker target and clearly report how to invoke the real target locally.

---

## 23. Open hardware-dependent decisions

These are not reasons to stop core implementation. Resolve them in M5 through capability tests:

1. Exact CXR-M versus CXR-L mobile adapter available to the user.
2. Exact custom data-channel method names and callback threading.
3. Maximum reliable Rokid application frame size.
4. Whether ordering/acknowledgement is guaranteed by the vendor link.
5. Glasses microphone PCM access versus native ASR transcript.
6. Function-button down/up versus only click callbacks.
7. Touch-panel gesture vocabulary and direction.
8. Whether a Bluetooth ring can pair directly to Poker and expose down/up timing.
9. Poker process suspend/background limitations.
10. Exact HUD resolution, safe text area, and preferred font sizes.

The implementation MUST surface these as capabilities, not scatter firmware assumptions through business logic.

---

## 24. References

Primary references for implementation verification:

- Rokid Open Platform SDK page: `https://open.rokid.com/sdk?lang=en`
- Rokid YodaOS-Sprite/CXR-S overview: `https://open.rokid.com/sprite?lang=en`
- tmux manual, including control mode, `%output`, `capture-pane`, and `pipe-pane`: `https://man.openbsd.org/tmux.1`
- Android foreground-service types: `https://developer.android.com/develop/background-work/services/fgs/service-types`
- Android `SpeechRecognizer`: `https://developer.android.com/reference/android/speech/SpeechRecognizer`
- Android `RecognizerIntent.EXTRA_AUDIO_SOURCE`: `https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE`

Use current official SDK samples and primary documentation as the source of truth when a reference changes. Preserve the architecture boundaries in this specification even when vendor API details change.
