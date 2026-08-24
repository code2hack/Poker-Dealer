# Poker-Dealer — Successor Project Handoff

**Project:** `code2hack/Poker-Dealer`
**Predecessor:** `code2hack/Poker-Dealer-Legacy`
**Status:** architecture/bootstrap handoff for the new successor project
**Purpose of this file:** preserve the design decisions and investigation direction established before starting the new repository, so a fresh conversation/agent can continue without depending on chat history.

---

## 1. Product identity

Poker-Dealer remains the product name.

The successor project is no longer conceptually “a Rokid remote client for Codex.” Its intended identity is:

> **Poker-Dealer is a glasses-native client platform for persistent AI-agent sessions. Dealer brokers heterogeneous agent runtimes; Poker provides the wearable interaction surface. Codex app-server is the reference and MVP backend.**

The product names remain:

- **Dealer** — the phone-side broker/client and authority for backend integration, projection, synchronization, durable drafts/assets, and wearable routing.
- **Poker** — the Rokid glasses HUD and lightweight semantic interaction surface.

The names fit the generalized architecture well:

```text
AI runtimes
Codex / DSH / Pi / Hermes / ...
              │
              ▼
           DEALER
  brokers sessions/capabilities
              │
              ▼
            POKER
   wearable interaction surface
```

The old repository has been renamed to:

```text
code2hack/Poker-Dealer-Legacy
```

The new successor repository owns active development:

```text
code2hack/Poker-Dealer
```

`Poker-Dealer-Legacy` remains valuable as a **reference implementation and evidence repository**, not current product authority.

---

## 2. Why a new repository exists

The successor architecture changes three foundational axes at once.

### 2.1 Backend

Old:

```text
Dealer → Codex app-server
```

New:

```text
Dealer
  ↓
canonical agent model
  ↓
backend adapter boundary
  ↓
Codex / DSH / Pi / Hermes / ...
```

### 2.2 Dealer ↔ Poker transport

Old:

```text
NSD/mDNS discovery
+ fixed TCP listener
+ PAKE bootstrap
+ pinned identities
+ mTLS
+ ordinary Wi-Fi/hotspot
```

Candidate successor direction:

```text
Dealer / Fold6
     │
   CXR-M
     ⇅
   CXR-S
     │
Poker / Rokid
```

The CXR migration is serious, but **not yet considered proven** until qualified on the exact Fold6 + Rokid firmware.

### 2.3 Poker interaction model

Old Poker:

```text
DOWN / UP / RIGHT / LEFT / FN / TAP / TAPTAP
boundary-driven navigation/input transitions
card/line-oriented movement
```

Successor direction:

```text
RIGHT / LEFT / DOWN / UP
PRIMARY / SECONDARY / COMMAND

explicit NAVIGATION ↔ INPUT
word/token cursor
semantic selection
rendered-line movement
invisible head navigation
command wheel
```

This model is inherited conceptually from `dsh-glasses`.

---

# 3. Sources of design/evidence

The successor repository should contain its own implementation and authority. It should **not** vendor or copy whole predecessor projects.

The major predecessor/reference sources are:

## 3.1 Poker-Dealer-Legacy

Use for proven behavior and architecture evidence involving:

- Codex app-server integration;
- host-qualified thread identity;
- app-server daemon/proxy behavior;
- Spark/u4090/Fold6 Termux host integration;
- Dealer projections;
- structured commands/file changes;
- approvals and structured questions;
- Send / Steer / Interrupt semantics;
- snapshots, deltas, epochs and recovery;
- durable drafts and Photo assets;
- operation identities;
- no-blind-replay semantics;
- accepted / rejected / unknown mutation outcomes;
- multi-thread piles and `BUSY | ATTENTION_REQUIRED | READY`;
- unread state;
- real Fold6/RG hardware evidence;
- previous Roku/Rokid input and transport investigation.

**Do not blindly inherit its current SPEC.** Many old architectural decisions are intentionally being reopened in the successor.

## 3.2 dsh-glasses

Use as the primary source for the new glasses interaction grammar and state-machine philosophy:

- source-neutral controls;
- `BEGIN / UPDATE(HOLD) / END / CANCEL`;
- first-source ownership until interaction completion;
- explicit `NAVIGATION | INPUT` base modes;
- blinking word-sized semantic cursor;
- `RIGHT ≈ Vim w`, `LEFT ≈ Vim b`;
- `DOWN/UP` by actual rendered HUD line;
- selection and clipboard behavior;
- invisible head-navigation mode;
- long-`COMMAND` action wheel;
- modal Photo / Voice / Morse semantics;
- exact target/generation/revision fencing;
- server authority over committed content;
- idempotent operations and no blind replay.

Do **not** copy the current dsh WebView implementation just because it exists. The SPEC/state model is the important inheritance; Poker may implement it natively in Compose.

## 3.3 JSOS (`IWhatsskill/JSOS`)

Use as an **external design and CXR reference**, especially for:

- practical CXR-M/CXR-S use between a phone app and glasses app;
- CXR-L use for deployment/install flows;
- wearable HUD ergonomics;
- Full/Mid/Bottom placement;
- fixed/non-reflowing status pulse;
- reading-first UI;
- show/hide own-user-message presentation;
- emulator/debug transport philosophy;
- wake/readiness handshake ideas;
- R08/ring driver architecture and reconnection patterns;
- TTS/barge-in and voice-addressed session ideas.

JSOS is AGPL-3.0. Treat it as a behavioral/design reference unless the project explicitly chooses to accept licensing implications of code reuse.

---

# 4. Core architecture principle: three independent axes

The successor should keep three concerns orthogonal.

```text
┌─────────────────────────────────────────────────────────┐
│                  AGENT BACKENDS                         │
│                                                         │
│ Codex │ DSH │ Pi │ Hermes │ ACP/...                    │
└──────────────────────────┬──────────────────────────────┘
                           │
                     Dealer domain
                           │
┌──────────────────────────┴──────────────────────────────┐
│                GLASSES TRANSPORT                        │
│                                                         │
│                  CXR-M ↔ CXR-S                         │
│              (candidate canonical path)                 │
└──────────────────────────┬──────────────────────────────┘
                           │
                         Poker
                           │
┌──────────────────────────┴──────────────────────────────┐
│                  INTERACTION / HUD                       │
│                                                         │
│ dsh-glasses interaction kernel                          │
│ + JSOS-inspired optical ergonomics                      │
└─────────────────────────────────────────────────────────┘
```

Rules:

1. Backend adapters MUST NOT know about Rokid/CXR.
2. CXR transport MUST NOT know about cards, Morse, Photo, approvals, word cursors, or backend types.
3. Poker interaction reducers MUST NOT know whether transport is CXR, Wi-Fi, simulator, or anything else.
4. Poker SHOULD be almost backend-blind.
5. Dealer absorbs backend churn and exposes a stable Poker-facing projection/protocol.

---

# 5. Backend strategy and priorities

The successor deliberately supports unequal backend priorities.

```text
Tier 0 / Reference / MVP
    Codex app-server

Tier 1 / first post-MVP target
    DeepSeek Harness (DSH)

Tier 2
    Pi

Tier 3
    Hermes

Tier 4
    ACP and other long-tail runtimes
```

Equivalent shorthand:

> **Codex app-server ≫ DSH > Pi > Hermes / everything else.**

This priority is architectural, not merely scheduling.

## 5.1 Critical rule

Do **not** design a lowest-common-denominator universal protocol by asking:

> “What do Codex, DSH, Pi and Hermes all have in common?”

Instead ask:

> “What does Poker-Dealer need to represent Codex completely and correctly, while avoiding unnecessary Codex-specific naming?”

The canonical model MUST NOT weaken Codex MVP semantics to accommodate lower-priority runtimes.

Codex app-server is the **reference backend**.

---

# 6. Backend-neutral canonical model

Codex app-server must stop being the Dealer domain model. It becomes one backend adapter.

Target shape:

```text
Dealer
  │
Canonical Agent Model
  │
Backend Adapter Interface
  │
  ├── CodexBackendAdapter
  ├── DshBackendAdapter
  ├── PiBackendAdapter
  ├── HermesBackendAdapter
  └── AcpBackendAdapter (later)
```

## 6.1 Identity

Replace assumptions equivalent to:

```text
(hostId, threadId)
```

with a backend-qualified identity concept:

```text
AgentSessionLocator
    hostId
    backendId
    sessionId
```

`backendId` identifies an installed/configured runtime instance, not just a backend software family.

This permits:

```text
spark
 ├── codex-openai
 ├── dsh-standard
 ├── pi-personal
 ├── pi-experiment
 └── hermes-main
```

Backend-specific resume information remains opaque inside the adapter.

## 6.2 Suggested canonical concepts

The canonical model should be conceptually equivalent to:

```text
AgentBackend
AgentBackendCapabilities

AgentSession
AgentSessionLocator

AgentRun
AgentRunState

AgentEvent
AgentContent
AgentToolCall
AgentToolResult
AgentFileChange
AgentError
AgentStatus

HumanRequest
HumanRequestResolution

AgentAction
AgentActionOutcome
```

The first implementation should map almost losslessly to Codex:

```text
AgentSession      ≈ Codex thread
AgentRun          ≈ Codex turn
AgentEvent        ≈ Codex item/event
HumanRequest      ≈ Codex server request
```

But canonical names should describe product semantics rather than Codex wire names.

---

# 7. Capability-driven behavior

Other backends MUST NOT pretend to support Codex features they do not have.

Example capability vocabulary:

```text
history.read
history.live

prompt.start
prompt.steer
prompt.queue

run.interrupt

session.fork
session.archive

humanRequest.choice
humanRequest.text
humanRequest.confirm
humanRequest.approval
humanRequest.secret
humanRequest.privilege

content.images
```

Rules:

- Unsupported operations are disabled.
- Semantically different operations MUST NOT be used as emulation.
- Example: `Interrupt + new prompt` MUST NOT be presented as a fake `Steer`.
- Example: creating a fresh session and copying text MUST NOT be silently presented as a true backend `Fork`.

---

# 8. Preserve rich backend-specific semantics

Do not flatten everything to:

```text
role + text
```

The canonical event model must retain first-class concepts important for Codex, including:

- command/tool execution;
- command output;
- file changes/diffs;
- structured approvals;
- structured user questions;
- active-run identity;
- streaming state;
- steering;
- interruption;
- reconciliation state.

At the same time, do not contaminate the canonical core with every backend-specific feature.

Use a concept equivalent to:

```text
Canonical Core
+
Backend Extensions
```

For example:

```text
AgentEvent
├── UserContent
├── AgentContent
├── ToolCall
├── ToolResult
├── FileChange
├── HumanRequest
├── Error
├── Status
└── NativeExtension
```

`NativeExtension` may preserve an important backend-native event without forcing all other backends to invent it.

---

# 9. HumanRequest unification

Dealer should expose a backend-neutral human-interaction abstraction.

Potential forms:

```text
Choice
FreeText
Confirm
Permission
Approval
Secret
Privilege
Unknown
```

Examples:

```text
Codex command approval
    → Permission / Approval

Codex structured question
    → Choice / FreeText

DSH question/requested
    → Choice / FreeText

DSH approval/requested
    → Approval

Pi extension UI select
    → Choice

Pi extension UI confirm
    → Confirm

Pi extension UI input/editor
    → FreeText

Hermes approval.request
    → Approval

Hermes clarify.request
    → FreeText / Choice

Hermes sudo.request
    → Privilege

Hermes secret.request
    → Secret
```

Poker safety rule remains:

> If the complete request cannot be safely and completely rendered/resolved on the glasses, escalate it to Dealer.

---

# 10. Poker-facing protocol stability

A central goal is:

> **Poker and Dealer can remain stable even when backend agents and their APIs change.**

Therefore two protocol lifecycles should be deliberately different.

## 10.1 Poker ↔ Dealer protocol

Should be highly stable.

Concepts may include:

```text
PokerSnapshot
PokerDelta
PokerAction
PokerHumanRequest
PokerDraftMutation
PokerCapabilities
```

Poker should see semantic concepts such as:

```text
session
history
live output

READY
BUSY
ATTENTION_REQUIRED

draft
request
approval

Send
Steer
Interrupt

Photo
Voice
Morse
```

Poker should NOT see backend-native method names such as:

```text
thread/start
turn/steer
session/event
extension_ui_request
approval.request
```

## 10.2 Dealer ↔ backend adapter API

May evolve as backend ecosystems change.

Dealer acts as an **anti-corruption layer** between rapidly evolving AI-agent runtimes and the stable wearable protocol.

---

# 11. Poker work-state model survives backend generalization

Keep the Poker presentation abstraction:

```text
BUSY | ATTENTION_REQUIRED | READY
```

Derive it canonically.

Example:

```text
backend active
+ no blocking human request
        ↓
BUSY

unresolved blocking human request
        ↓
ATTENTION_REQUIRED

idle and promptable
        ↓
READY
```

Piles may contain sessions from different runtimes while sharing one Poker interaction model.

---

# 12. Pi-specific limitation to remember

Pi is highly promising through `pi --mode rpc`:

- persistent sessions;
- history/entries;
- streaming message/tool events;
- steer;
- follow-up/queue;
- abort;
- fork/clone;
- extension UI request/response.

But Pi is process/session oriented rather than naturally daemon/multi-client oriented.

Initial policy should be:

```text
1 active Dealer-owned Pi session
=
1 pi --mode rpc subprocess
```

Do not build a custom persistent Pi daemon for MVP/post-MVP unless actual demand justifies it.

A persisted Pi session may be resumed, but exact simultaneous live multi-client continuity with an independently running Pi TUI should be treated as a capability difference, not faked.

---

# 13. Hermes strategy

Hermes is a later backend target.

Prefer its rich native control surface (for example its gateway/session APIs) over flattening it through an OpenAI-compatible chat endpoint.

Potential Hermes-native features such as:

```text
approval.request
clarify.request
sudo.request
secret.request
```

should map through canonical `HumanRequest` forms or remain backend extensions where appropriate.

Hermes should not shape MVP abstractions ahead of Codex, DSH or Pi.

---

# 14. ACP strategy

ACP is valuable as a **long-tail compatibility adapter**, not as the internal core protocol.

Target future arrangement:

```text
Codex  → native app-server adapter
DSH    → native DSH adapter
Pi     → native RPC adapter
Hermes → native rich gateway/session adapter

other/long-tail agents
       → ACP adapter
```

Native adapters should be preferred for reference/priority backends where they preserve richer semantics.

---

# 15. CXR-M + CXR-S transport direction

JSOS demonstrates that an ordinary phone app and ordinary glasses app can use Rokid's official CXR split stack as an application data transport.

Observed architecture of interest:

```text
Phone app
  │
CXR-M
  │
Rokid CXR system stack
  │
CXR-S / CXRServiceBridge
  │
Glasses app
```

The exact JSOS pattern of interest uses:

- phone-side CXR-M;
- glasses-side CXR-S bridge;
- application-level JSON above CXR;
- a separate emulator/debug WebSocket path.

CXR-L is a separate concern, used by JSOS primarily in deployment/install flows through Hi Rokid. It is **not** the main live JSOS Core ↔ HUD data channel.

---

# 16. What CXR may replace

Current Poker-Dealer-Legacy layers and successor candidates:

| Legacy layer | CXR successor direction |
| --- | --- |
| Android NSD/mDNS discovery | likely delete |
| Poker TCP listener `:39817` | likely delete |
| IP/endpoint tracking | delete |
| Dealer hotspot/Wi-Fi connection establishment | likely delete |
| custom socket heartbeat/reconnect | partially replace |
| PAKE ceremony | may delete, pending trust proof |
| Keystore identities + mTLS | may simplify/replace, pending security proof |
| connection epoch | keep |
| complete snapshots | keep |
| revisioned deltas | keep |
| control generation | keep |
| draft/target revisions | keep |
| operation IDs/idempotency | keep |
| accepted/rejected/unknown | keep |
| no blind replay | absolutely keep |

Critical principle:

> **CXR can replace communication/bootstrap machinery; it does not replace distributed-state correctness.**

---

# 17. CXR qualification plan

Do not switch architecture based only on JSOS.

Run a real hardware qualification against the exact Fold6 and exact Rokid firmware.

## Phase CXR-1 — inspect the SDK and JSOS integration

Determine:

- exact CXR-M artifact/version;
- exact CXR-S bridge artifact/version;
- permissions/components;
- JNI/native dependencies;
- channel/subscription APIs;
- connection lifecycle;
- payload framing;
- binary support;
- reconnect behavior;
- developer credential requirements;
- Hi Rokid coexistence requirements.

## Phase CXR-2 — isolated probe

Create a tiny pair:

```text
DealerCxrProbe / Fold6
        ⇅
PokerCxrProbe / Rokid
```

No Codex, no cards, no drafts, no Photo/Morse/Voice.

Prove:

- phone → glasses;
- glasses → phone;
- request/reply;
- binary payload;
- connection state;
- reconnect.

## Phase CXR-3 — real-hardware stress/trust gate

Measure/prove:

- normal third-party APK usability;
- no root/platform signature requirement;
- pairing/connect determinism;
- ordering;
- duplicate/loss behavior;
- payload limits;
- throughput;
- interactive latency;
- Bluetooth off/on recovery;
- Fold6 process death/recreation;
- Poker process death/recreation;
- both device reboots;
- background/screen-off behavior;
- Hi Rokid coexistence;
- offline behavior;
- application/channel isolation;
- credential requirements;
- version/firmware dependency;
- battery/power behavior;
- failure semantics sufficient for no-replay.

## Phase CXR-4 — transport abstraction

Only after the probe passes:

```text
Poker semantic protocol
        │
PokerTransport
        │
        ├── temporary LegacyWifiPokerTransport
        └── CxrPokerTransport
```

Keep semantic/synchronization envelopes above CXR.

Do not automatically copy JSOS's JSON chunking approach. Measure CXR's binary/payload properties first.

## Phase CXR-5 — dual-stack A/B

Compare CXR and the legacy Wi-Fi path for:

- connect latency;
- reconnect latency;
- throughput;
- streaming;
- battery;
- background behavior;
- failure semantics.

## Phase CXR-6 — cutover

If CXR passes strongly:

- make CXR-M/S canonical;
- delete obsolete NSD/mDNS;
- delete TCP `39817`;
- delete endpoint/IP tracking;
- simplify/delete PAKE and mTLS only to the degree proven safe;
- do not keep two permanent production transports without a concrete reason.

---

# 18. CXR security fork

Three possible outcomes must be considered.

## Best case

CXR sufficiently proves:

- intended physical phone/glasses pairing;
- correct device identity;
- acceptable application/channel isolation;
- secure communication.

Then custom PAKE/mTLS may become unnecessary.

## Middle case

CXR proves the paired devices but not exact Dealer/Poker application identity/isolation.

Still use CXR as transport, but add a thin application-layer authenticated encryption envelope:

```text
CXR
 ↓
Poker secure envelope
  key-id
  nonce
  epoch
  AEAD ciphertext
 ↓
Poker semantic protocol
```

This still removes networking complexity such as NSD, TCP listener, endpoint tracking and TLS certificates.

## Bad case

If CXR is too fragile, cloud-dependent, incompatible, mutually exclusive with normal Rokid behavior, unable to sustain required payloads, or unreliable in background/recovery scenarios, stop and retain the legacy transport architecture.

---

# 19. dsh-glasses interaction kernel for Poker

The new Poker HUD interaction model should use the dsh-glasses conceptual control layer:

```text
RIGHT
LEFT
DOWN
UP
PRIMARY
SECONDARY
COMMAND
```

Intended built-in Rokid mapping:

```text
RIGHT      = single-finger swipe forward
LEFT       = single-finger swipe backward
DOWN       = dual-finger swipe forward
UP         = dual-finger swipe backward
PRIMARY    = single-finger touch
SECONDARY  = dual-finger touch
COMMAND    = function button
```

Raw events must not leak above the input-adapter layer.

Every interaction follows:

```text
BEGIN
optional UPDATE/HOLD
END or CANCEL
```

First source owns canonical input until completion. Competing input is ignored, not queued.

Cancellation, focus loss, backgrounding or disconnection MUST NOT synthesize semantic actions.

---

# 20. Base UI state

Target conceptual state:

```text
AppVisibility
HudVisibility

BaseMode
    NAVIGATION
    INPUT

SelectionState
    inactive
    active(anchorTokenId, focusTokenId)

TransientMode
    none
    head-navigation
    command-wheel
    photo
    voice
    morse
```

Input/Navigation transition is explicit:

```text
NAVIGATION ← single COMMAND → INPUT
```

It is **not boundary-driven**.

Navigation and Input retain separate cursor state.

---

# 21. Semantic word/token cursor

Poker should use a blinking **word-sized semantic highlight**, not a character `|` cursor.

Rules:

- punctuation is an indivisible word-like unit according to accepted segmentation;
- emoji is a word-like unit;
- each image token is indivisible;
- `RIGHT` approximates Vim `w`;
- `LEFT` approximates Vim `b`;
- `DOWN/UP` move by actual rendered HUD line.

Rendered-line movement requires post-layout geometry:

```text
semantic block
   ↓
stable tokens
   ↓
Compose layout
   ↓
token rectangles
   ↓
visual line grouping
   ↓
cursor movement
```

For vertical movement:

- retain preferred horizontal coordinate;
- move to nearest token on adjacent rendered line;
- horizontal movement resets preferred horizontal coordinate.

Streaming/reflow reanchors by stable block/token identity, not line number.

This is a major replacement for Poker-Dealer-Legacy's line-index navigation model.

---

# 22. Navigation mode

Navigation is reading-first.

Target behavior:

- composer/input surface hidden;
- conversation receives maximum available optical viewport;
- `RIGHT/LEFT` move token cursor;
- `DOWN/UP` move by rendered line;
- `PRIMARY` begins/completes selection/copy behavior;
- double `PRIMARY` copies current token when appropriate;
- `SECONDARY` is mostly non-mutating in Navigation;
- double `SECONDARY` may Hide HUD;
- single `COMMAND` enters Input;
- double `COMMAND` may background app;
- long `COMMAND` activates invisible head-navigation until release.

Selection operates on semantic projected content, not visually truncated screen scraping.

---

# 23. Invisible head-navigation

Long `COMMAND` from Navigation captures a head-pose anchor.

Target semantics:

```text
head up
    continuous scroll up

head down
    continuous scroll down

head left
    previous pile/tab once per excursion

head right
    next pile/tab once per excursion

return to dead zone
    stop and re-arm

release/cancel
    clear anchor and exit
```

Use:

- dominant-axis arbitration;
- dead zone;
- hysteresis;
- stale-pose cancellation;
- capped velocity;
- lateral excursion latch.

These are semantic viewport/pile actions, not synthetic cursor controls.

Poker's existing posture/wheel machinery from Legacy is useful reference evidence.

---

# 24. Input mode

Input mode shows the exact editable target and committed draft.

Target directional behavior remains semantic:

- `RIGHT/LEFT` move by word/token;
- `DOWN/UP` move by rendered line;
- selection can extend/contract with directional input.

Target clipboard behavior conceptually follows dsh-glasses:

- `PRIMARY` starts/finishes selection;
- double `PRIMARY` copies current token or selected range;
- `SECONDARY` pastes/replaces;
- long `SECONDARY` cuts;
- double `SECONDARY` hides only where selection/click-classification rules permit;
- `COMMAND` returns to Navigation;
- long `COMMAND` opens command wheel.

Plain text may mirror to Android clipboard.

Rich image references should remain internal authenticated references rather than raw Android clipboard image authority.

---

# 25. Request/approval adaptation

Poker has richer structured request/approval concepts than the current dsh glasses design.

Do not preserve the old concept of request panel as a third base mode.

Prefer:

```text
BaseMode
    NAVIGATION
    INPUT

InputTarget
    composer
    structured request
    approval
    editable "Other"
    free-text request answer
```

Possible product rule to refine in SPEC:

- entering Input while Navigation cursor is on/inside an actionable request selects that exact request as the Input target;
- otherwise Input targets the ordinary session composer;
- returning to Navigation restores the original semantic Navigation cursor/anchor.

Structured choices remain structured and must not be flattened into prompt text.

---

# 26. Command wheel

Reuse the strong posture-selection concepts already proven in Legacy, but align semantics with the dsh interaction grammar.

Target visual concept:

```text
             Photo

    Morse                Voice

       Send / Steer / Interrupt
```

Bottom sector is dynamically derived from authoritative session state and capabilities.

Examples:

```text
running + nonempty draft
    → Steer

running + empty draft
    → Interrupt

not running + nonempty draft
    → Send

unsupported/stale/conflicting
    → disabled
```

Wheel selection snapshots/revalidates:

- session/attachment identity;
- target;
- draft revision;
- agent state;
- displayed semantic action;
- control generation;
- wheel/session ID.

Release must never silently substitute a newly different action.

The user-visible label should preferably be the actual semantic action (`Send`, `Steer`, `Interrupt`) rather than a generic `Primary`.

---

# 27. Photo / Voice / Morse direction

Do not treat these as simple renames of Legacy controls.

The dsh-glasses modal semantics are generally the desired interaction reference.

## Photo

Likely direction:

- `RIGHT/LEFT` zoom;
- `PRIMARY` capture;
- long `SECONDARY` delete newest staged photo;
- short `COMMAND` atomically commit surviving Photo-session assets and exit.

This differs materially from Legacy's “capture immediately commits token” behavior and may require protocol/state changes.

Exact-byte preservation and ordered mixed text/image semantics from Legacy/dsh should remain.

## Voice

Poker should use **Voice** as the user-facing mode concept.

Backend recognition may remain Dealer-local for the Poker-Dealer product even if dsh-glasses recognition runs on its server host.

Preserve:

- explicit slice/fence semantics;
- reviewed draft commitment;
- no direct auto-send;
- bounded audio;
- exact session/target/revision fencing;
- committed/provisional separation.

Future JSOS-inspired ideas:

- TTS;
- Voice barge-in that immediately stops/ducks TTS;
- exact-alias voice-addressed pile selection.

These are post-MVP.

## Morse

Prefer the richer dsh control scheme:

```text
short PRIMARY   dot
long PRIMARY    dash

RIGHT           commit selected completion
LEFT            commit literal decoded word

DOWN            next completion
UP              previous completion

short SECONDARY provisional backspace
long SECONDARY  clear/delete exact session word

COMMAND         exit
```

Do not use an LLM for completion.

---

# 28. JSOS-inspired optical ergonomics

The interaction grammar comes primarily from dsh-glasses. JSOS contributes a missing **optical ergonomics layer**.

## 28.1 Full / Mid / Bottom placement

Introduce a presentation property equivalent to:

```text
HudPlacement
    FULL
    MID
    BOTTOM
```

Changing placement changes geometry but MUST NOT change semantic cursor/selection/session state.

After re-layout, re-anchor to the same semantic token where possible.

Dealer may own/synchronize this preference.

## 28.2 Fixed non-reflowing status PulseSlot

Create a tiny optical status area that does not participate in content layout.

Potential states:

```text
READY
RUN
ASK
SYNC
OFF
VOICE
MORSE
PHOTO
!
```

Exact vocabulary is open.

Invariant:

> Updating the PulseSlot MUST NOT alter content geometry, line wrapping, cursor, selection or scroll anchor.

## 28.3 Show/hide own prompts

A purely presentation-level option equivalent to:

```text
showUserMessages = true / false
```

When disabled, Poker hides the user's own message cards locally while preserving complete authoritative history in Dealer/backend.

If the focused semantic block is hidden, re-anchor deterministically to a nearby surviving block.

## 28.4 Optical safe zone

Model:

```text
hardware canvas
     ↓
device/firmware safe zone
     ↓
user placement profile
     ↓
semantic HUD viewport
```

Separate hardware facts from user ergonomic preferences.

Possible concepts:

```text
RokidDisplayProfile
    hardwareSize
    safeInsets
    opticalCenter
    supportedPlacements

PokerHudProfile
    placement
    fontScale
    showUserMessages
```

---

# 29. Poker simulator/debug architecture

Adopt JSOS's emulator/debug philosophy.

Build a simulator that uses the **real production Poker protocol/reducers**, not a second fake UI.

Concept:

```text
Dealer
  │
same Poker wire protocol
  ↓
Debug Poker transport
  ↓
480×640 simulated Poker renderer
```

Virtual controls should include:

```text
RIGHT LEFT DOWN UP
PRIMARY SECONDARY COMMAND

head pitch/roll
hide/wake
connection loss
snapshot replacement
```

Use it to test:

- token movement;
- rendered-line movement;
- selection;
- head scrolling;
- pile switching;
- command wheel;
- Full/Mid/Bottom;
- font scaling;
- streaming reflow;
- request cards;
- wake/resync.

Real RG hardware remains mandatory for qualification of physical input and optical behavior.

---

# 30. Wake/readiness handshake

Borrow the principle, not JSOS's simplistic buffering implementation.

Desired shape:

```text
Dealer sees event that qualifies for foregrounding
      ↓
request Poker wake/foreground
      ↓
Poker Activity/UI is truly render-ready
      ↓
Poker reports HUD_READY(epoch)
      ↓
Dealer reconciles/installs latest authoritative state
```

Do not blindly replay buffered UI messages.

Use the latest authoritative snapshot/delta state.

This should help avoid cases where Android lifecycle says the app is foreground but the user-visible optical HUD is not yet ready.

---

# 31. R08/ring direction

JSOS provides useful reference evidence for direct R08 BLE/GATT driver architecture and bounded reconnection.

Do **not** automatically adopt its Accessibility/global-injection architecture.

Future Poker input-driver model should remain:

```text
InputSourceDriver
├── RokidBuiltInDriver
├── AndroidHidDriver
└── R08DirectDriver        future
       ↓
canonical controls
RIGHT LEFT DOWN UP PRIMARY SECONDARY COMMAND
```

A ring driver should fail independently.

No global/system-wide remapping should be required for core product operation unless explicitly reconsidered.

---

# 32. Preserve Poker's strong distributed-state invariants

Regardless of CXR/backend/UI changes, retain these architectural principles:

- server/backend authority for durable agent history;
- Dealer authority for canonical projection and committed wearable draft state;
- Poker authority only for ephemeral local interaction/viewport/modal state;
- complete snapshots;
- revisioned deltas;
- stable session identity;
- connection epochs;
- control generations;
- exact target and draft revisions;
- operation IDs;
- idempotent duplicate handling;
- accepted / rejected / unknown mutation outcomes;
- authoritative reconciliation;
- no blind replay after unknown acceptance;
- committed content survives forced modal exit;
- provisional/uncommitted modal state is discarded on irreconcilable loss.

CXR or a backend adapter MUST NOT weaken these rules.

---

# 33. MVP scope discipline

The successor is a generalized architecture, but the MVP remains **Codex**.

Do not simultaneously implement Codex + DSH + Pi + Hermes.

Implementation order:

```text
NOW
 │
 ▼
canonical model designed Codex-complete
 │
 ▼
CodexBackendAdapter
 │
 ▼
Poker-Dealer Codex MVP
 │
 ▼
real hardware acceptance
 ───────────────────────
 │
 ▼
DshBackendAdapter
 │
 ▼
PiBackendAdapter
 │
 ▼
Hermes / ACP / others
```

Abstraction now; backend breadth later.

---

# 34. Recommended successor milestones

These are planning recommendations, not yet normative SPEC.

## M0 — project constitution and architecture

Create/define:

```text
AgentSessionLocator
AgentBackendCapabilities

AgentSession
AgentRun
AgentEvent
HumanRequest

BackendAdapter

PokerSnapshot
PokerDelta
PokerAction

PokerTransport
```

Write architecture decisions covering:

- Codex reference-backend priority;
- backend-neutral Dealer boundary;
- Poker backend blindness;
- CXR qualification direction;
- dsh interaction model;
- authority/snapshot/no-replay invariants;
- predecessor/reference role of Legacy/dsh/JSOS.

## M1 — Codex abstraction proof

Prove:

```text
Codex app-server
     ↓
CodexBackendAdapter
     ↓
Canonical Agent Model
```

without semantic loss for:

- history;
- streaming;
- commands/tools;
- file changes;
- structured questions;
- approvals;
- Send;
- Steer;
- Interrupt;
- reconnect/reconciliation;
- uncertain actions.

If Codex becomes awkward or weaker, fix the canonical model.

## M2 — CXR qualification

Run the isolated Fold6/RG CXR-M/S probe and complete the hardware/trust matrix.

Do not tie CXR qualification to full Poker feature migration.

## M3 — Poker interaction/HUD kernel

Implement:

- seven canonical controls;
- explicit Navigation/Input;
- semantic token cursor;
- rendered-line movement;
- selection/clipboard;
- invisible head navigation;
- command wheel;
- JSOS Full/Mid/Bottom;
- PulseSlot;
- safe-zone profile;
- prompt visibility option;
- simulator/debug transport.

## M4 — Codex Poker MVP parity

Bring forward only the valuable product semantics from Legacy:

- multi-session piles;
- `BUSY | ATTENTION_REQUIRED | READY`;
- unread;
- requests/approvals;
- drafts;
- Photo;
- Morse;
- Voice;
- wake/recovery;
- host continuity required by the Codex MVP.

Once successor functionality is proven equal or better for the real intended workflow, `Poker-Dealer-Legacy` may be considered superseded for active development.

## Post-MVP

```text
DSH adapter
Pi adapter
Hermes adapter
ACP/other adapters
```

---

# 35. Anti-goals / things not to absorb

Do not import these simply because predecessor/reference projects contain them:

- terminal emulation in Dealer/Poker;
- tmux screen scraping;
- generic terminal-key injection as product input;
- a generic slash-command UI;
- JSOS Admin Codex terminal HUD;
- cloud/Android speech fallback as implicit core behavior;
- JSOS `SEND AUTO` voice submission;
- session creation/management directly on glasses unless separately designed;
- lowest-common-denominator backend protocol;
- fake Steer/Fork semantics;
- permanent dual transport complexity without need;
- backend-specific branches spread through Poker UI;
- raw CXR types leaking into Dealer domain or Poker reducers;
- whole-project code copying from JSOS.

---

# 36. Repository-boundary recommendation

The new repository should own only the successor architecture.

Do not copy entire repositories inside it.

Conceptually:

```text
Poker-Dealer-Legacy
   → evidence and reusable design knowledge

dsh-glasses
   → interaction/state-model reference

JSOS
   → external CXR/ergonomics reference

new Poker-Dealer
   → resulting architecture and implementation
```

Source transfer from Legacy, when appropriate, should be deliberate and reviewed rather than a wholesale historical continuation.

---

# 37. Proposed initial repository shape

The repository may start small.

Possible direction:

```text
Poker-Dealer/
├── AGENTS.md
├── SPEC.md
├── HANDOFF.md
│
├── docs/
│   └── adr/
│
├── shared/
│   ├── agent-domain/
│   ├── backend-api/
│   └── poker-protocol/
│
├── backends/
│   └── codex/
│
├── transports/
│   └── cxr/
│
├── apps/
│   ├── dealer-android/
│   └── poker-android/
│
└── tools/
    └── poker-simulator/
```

Do not create empty architecture for its own sake. Add directories/modules only when their contracts are understood and needed.

---

# 38. Immediate next conversation / agent instructions

A fresh conversation should **not start coding the full product immediately**.

Recommended next task:

> Turn this handoff into the first normative `SPEC.md` and project constitution, with Codex as the Tier-0 reference backend and MVP, while keeping CXR qualification and dsh-style Poker UI as independent architectural tracks.

The first hard architecture questions to resolve are:

1. Exact canonical Agent Session / Run / Event / HumanRequest model that is **Codex-complete**.
2. Exact boundary between canonical Dealer domain and backend-native extension payloads.
3. Stable Poker-facing snapshot/delta/action protocol.
4. Exact security properties CXR-M/S provides on the real devices and which legacy PAKE/mTLS responsibilities remain necessary.
5. Exact Poker semantic token/layout model for native Compose rendered-line navigation.
6. Exact request/approval `InputTarget` behavior under the new explicit Navigation/Input state machine.
7. Which Legacy source can be reused directly versus reimplemented under the new architecture.

Priority rule for all decisions:

> **Do not sacrifice the Codex MVP for hypothetical genericity. Generalization must preserve or improve the full Codex experience.**

---

# 39. One-sentence architectural north star

> **Dealer is the stable, backend-neutral agent-session broker; Poker is the backend-blind, glasses-native semantic control surface; Codex is the reference MVP backend; CXR-M/S is the candidate native transport; dsh-glasses supplies the interaction grammar; JSOS supplies selected optical/CXR design inspiration; and distributed-state correctness from Poker-Dealer-Legacy remains non-negotiable.**
