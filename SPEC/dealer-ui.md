# Dealer Android UI/UX Specification

**Status:** Normative MVP specification  
**Product:** Dealer, the Android client for Poker-Dealer  
**Protocol evidence pin:** `openai/codex@6525b95dae2082ac9fee672b14c2cffdef172bb8`  
**Visual evidence:** `docs/design/dealer-ui.md` and `docs/design/dealer-chat-mvp.md`

This specification reconciles the accepted Dealer design input with the current official Codex app-server protocol and Poker-Dealer correctness invariants. It governs product behavior and information architecture. Screenshot-derived geometry remains evidence; where evidence conflicts with this document, this document wins.

## 1. Product mental model

Dealer MUST feel like an ordinary mobile messaging application while preserving exact Codex semantics underneath.

Dealer is not:

- an engineering diagnostics console;
- a generic agent-runtime dashboard;
- a file manager;
- a cwd browser;
- a substitute configuration-precedence system;
- a Poker-dependent application.

Dealer MUST remain fully useful without Poker connected.

## 2. Navigation and screen ownership

The primary bottom navigation contains exactly:

1. **Chats**
2. **Workspaces**
3. **Settings**

Opening a thread from Chats, Workspaces, or another thread link pushes the **Chat** screen above the bottom navigation.

Android Back MUST:

1. dismiss the topmost transient surface;
2. dismiss the IME or composer focus when appropriate;
3. return from Chat to its originating list;
4. leave the application only after navigation surfaces are exhausted.

The current diagnostics Activity is not UI authority and MUST be replaced or isolated when implementation begins.

## 3. Orthogonal state ownership

Dealer MUST model these axes independently.

### 3.1 Codex thread runtime

The only thread-runtime state is official Codex `ThreadStatus`:

- `notLoaded`
- `idle`
- `active { activeFlags }`
- `systemError`

Official active flags are:

- `waitingOnApproval`
- `waitingOnUserInput`

Dealer MUST NOT create a substitute `READY`, `WORKING`, `BUSY`, `RUNNING`, `ATTENTION_REQUIRED`, or `UNKNOWN` thread-state enum.

### 3.2 Exact active turn

Dealer separately tracks:

- exact `turnId`;
- turn status;
- turn kind;
- whether same-turn steering is currently accepted;
- whether interruption is pending.

`ThreadStatus.active` alone is insufficient authority for Steer or Stop.

### 3.3 Connection and reconciliation

Dealer separately tracks:

- connected;
- reconnecting;
- host unavailable;
- app-server replacement;
- reconciling;
- authority fresh/stale.

These are Dealer connection states, not Codex thread states.

### 3.4 Outbound operation

Each Send, Steer, Interrupt, settings update, and structured-request response has its own operation identity and state:

- pending;
- accepted;
- rejected;
- unknown acceptance;
- reconciled.

### 3.5 Local organization and wearable state

Dealer separately tracks:

- Chats membership;
- Poker attachment intent;
- future CXR connection/readiness;
- transient UI surface;
- local ASR session.

None of these changes `ThreadStatus`.

## 4. Thread-status icon contract

Normal thread rows use icons rather than friendly runtime labels.

| Official state | Required visual |
|---|---|
| `notLoaded` | no runtime icon |
| `idle` | no runtime icon |
| `active`, no waiting flags | blue hourglass-shaped `⏳` icon |
| `active + waitingOnApproval` | yellow `?` |
| `active + waitingOnUserInput` | green solid circle `●` |
| `systemError` | red `!` |

Rules:

- The hourglass MUST render blue. A tintable vector shaped like `⏳` is permitted when the platform emoji cannot be colored reliably.
- When both waiting flags are present, render yellow `?` followed by green `●`.
- The blue hourglass is omitted whenever either waiting flag is present.
- Icons MUST have accessibility descriptions using the exact official semantics, for example `active, waiting on approval`.
- Accessibility text MUST NOT create an alternative internal state model.
- When authority is stale, the runtime icon MUST be hidden until freshness is restored. Connection/reconciliation state is shown separately.
- Poker-facing semantic state MUST preserve the same official status and flags rather than receiving a derived work-state enum.

## 5. Setup and onboarding

First run uses four conceptual steps:

1. **Tailscale**
2. **Codex host**
3. **Poker**
4. **Complete**

### 5.1 Tailscale

Dealer starts the supported Tailscale sign-in flow and shows current account/network state.

Changing Tailscale account later MUST trigger validation of the selected Codex host.

### 5.2 Codex host

Dealer lists known/reachable host candidates with enough information to distinguish them. Selecting a host begins trust/authentication and app-server validation.

Ordinary copy SHOULD hide low-level TOFU and key-management terminology while preserving strict host-key verification underneath.

### 5.3 Poker

Poker connection is optional. The user may skip it and complete setup.

Exact CXR authorization, pairing, recovery, and app-deployment UX remains deferred to `SPEC/cxr.md`. Dealer MUST NOT restore the obsolete NSD/PAKE/pinned-mTLS flow.

### 5.4 Settings reuse

Settings reuses the relevant setup component instead of replaying the full wizard:

- change Tailscale account;
- change Codex host;
- connect/reconnect Poker.

## 6. Chats

Chats is a Dealer-curated human-attention subset, not the complete Codex thread store.

### 6.1 Membership identity

Membership is keyed by:

`(hostId, threadId)`

Membership is independent of:

- Project assignment;
- app-server subscription;
- loaded/unloaded state;
- archive state;
- Poker attachment.

### 6.2 Grouping

Chats groups members by official Codex `projectId`.

- Project-assigned threads appear under the current official Project.
- `projectId = null` threads appear under a distinct **Unassigned** group.
- Unassigned is not a Workspace and does not create a pseudo-Project.
- Project groups may be collapsed/expanded without changing membership.

### 6.3 Row content

A row contains:

- current thread name, falling back to preview when unnamed;
- official status icon under Section 4;
- independent Poker attachment indicator;
- optional stale/host-unavailable treatment at the host/group level;
- no friendly runtime label in the normal row.

### 6.4 Membership lifecycle

The following rules are normative:

- A thread created by Dealer is automatically added to Chats.
- A thread forked by Dealer is automatically added to Chats.
- Opening a thread from Workspaces does not add it to Chats.
- `Add to Chats` creates local membership only.
- `Remove from Chats` deletes local membership only.
- Removing from Chats does not unsubscribe, archive, delete, or change Project assignment.
- Archiving a thread hides it from normal Chats while preserving membership.
- Unarchiving restores it to Chats when membership still exists.
- Permanent Codex deletion removes local membership.
- Project reassignment preserves membership and moves the row to the new Project group.
- A discovered external thread is not automatically added.

### 6.5 Context actions

Context actions may include:

- Attach/detach Poker;
- Remove from Chats;
- Copy thread ID;
- Rename;
- Archive.

Any action unsupported by the connected app-server is omitted or disabled with an explanation.

## 7. Workspaces: official Codex Projects only

A Dealer Workspace is exactly one official Codex Project.

Workspaces MUST NOT implement:

- folder-tree navigation;
- arbitrary path browsing;
- file creation/rename/delete/copy;
- a repository explorer;
- a mobile file manager;
- cwd-based fallback Workspaces.

### 7.1 Capability policy

At the protocol evidence pin, Project APIs are experimental.

Dealer SHOULD initialize with the required experimental capability when compatible. If Project APIs are unsupported or rejected:

- Chats and direct Chat remain available;
- Workspaces shows **Codex Projects unavailable on this host**;
- Dealer does not infer Projects from cwd;
- Dealer does not create local pseudo-Projects.

### 7.2 Project list and detail

When available, Workspaces displays official:

- Project ID;
- Project name;
- manual order;
- ordered absolute roots as read-only metadata;
- Project-assigned thread list.

Project roots are metadata, not navigation targets.

### 7.3 Project management

Dealer may expose the Project operations supported by the connected app-server:

- create;
- read;
- update;
- reorder;
- delete;
- thread assignment/reassignment.

Project forms use explicit metadata fields. Root entries are absolute path values validated by app-server; they are not selected through a file-manager UI.

Project deletion copy MUST explain that Codex clears assignments but does not delete threads, directories, or files.

### 7.4 Unassigned threads

Threads with `projectId = null` appear in a distinct **Unassigned threads** section. This section is not a Workspace.

Dealer may allow assigning an unassigned thread to a Project through the official Project/thread assignment API.

### 7.5 Project thread actions

Project thread actions include, when supported:

- Add to Chats;
- Rename;
- Archive;
- Fork;
- reassign Project;
- copy thread ID.

## 8. New thread creation

New thread creation belongs to the selected Project.

### 8.1 Required context

Dealer MUST use the exact Project ID when `thread/start.projectId` is supported.

Working-directory selection is limited to official Project root metadata:

- one root: select it automatically as `cwd`;
- multiple roots: show a simple root selector containing only those roots;
- zero roots: omit the `cwd` override and inherit app-server effective defaults.

Dealer MUST NOT offer arbitrary path entry or folder navigation from the new-thread flow.

### 8.2 Configuration

MVP new threads:

- inherit the host's effective Codex configuration;
- expose no named Codex profile selector;
- expose no Dealer Thread Preset;
- expose no ordinary Personality control;
- expose no ordinary Developer Instructions editor.

### 8.3 Optional name

If the user supplies a name:

1. Dealer starts the thread;
2. after successful creation, Dealer calls `thread/name/set`;
3. name failure does not roll back or delete the created thread;
4. Dealer reports the rename failure and retains the new thread.

### 8.4 Local organization

A successfully created thread is automatically added to Chats.

If creation acceptance is unknown, Dealer MUST reconcile before creating another thread from the same operation or clearing operation state.

## 9. Settings

Primary Settings sections are:

- **Tailscale**
- **Codex host**
- **Poker**
- **ASR**
- **General**

MVP explicitly excludes:

- Thread Presets;
- named Dealer/Codex profile management;
- ordinary Personality controls;
- ordinary Developer Instructions editing;
- a filesystem navigator.

Host and Poker connection details MUST distinguish current, stale, unavailable, and unqualified states.

## 10. Chat visual baseline

The supplied ChatGPT Remote Android screenshots are the visual and interaction baseline only for states they visibly demonstrate.

Dealer SHOULD closely reproduce:

- floating header;
- timeline spacing and hierarchy;
- assistant text treatment;
- user bubbles;
- code blocks and copy action;
- collapsed and focused composer;
- add/plugins menu;
- permissions menu;
- intelligence/model/speed menu;
- voice composer;
- remote-status panel;
- overflow menu;
- Android IME/system chrome integration.

Dealer MUST NOT copy OpenAI/ChatGPT names, logos, or trademark branding.

The approximate geometry and visual tokens remain in `docs/design/dealer-chat-mvp.md`.

Missing states are specified by current app-server protocol plus Poker-Dealer invariants, not visual extrapolation.

## 11. Timeline item model

Dealer MUST preserve structured app-server items.

### 11.1 User and assistant content

- User text appears in user bubbles.
- Assistant text appears in the assistant timeline treatment.
- Code blocks preserve language/monospace presentation and provide copy.
- Images remain ordered relative to text in the submitted draft and timeline.

### 11.2 Command execution

A command card displays available structured fields:

- command;
- cwd;
- parsed command actions;
- status;
- streamed/aggregated output;
- exit code;
- duration;
- process identity when relevant.

Output deltas update the same card. Completion updates status in place.

### 11.3 File changes

A file-change card displays:

- affected paths;
- change type;
- patch/diff content when available;
- apply status;
- live patch updates;
- final completion/failure.

Dealer MUST NOT flatten file changes into assistant prose.

### 11.4 Unknown items

An unknown non-blocking item is retained as an **Unsupported Codex item** placeholder with stable identity and diagnostic metadata. It is not silently dropped.

Unknown blocking server requests follow the fail-closed request rules in Section 15.

## 12. Composer and durable draft

The draft is Dealer-owned and durable.

A draft contains an ordered sequence of:

- text segments;
- staged image assets.

Rules:

- draft editing is allowed while disconnected, reconciling, or waiting on a blocking request;
- unsafe submission controls are disabled when authority is insufficient;
- each outbound submission freezes the exact draft revision and target;
- later edits create a newer revision;
- only exact known acceptance clears the submitted revision;
- rejection unlocks/restores the submitted revision;
- unknown acceptance locks the operation and preserves draft/assets until reconciliation;
- no blind replay;
- staged asset failure must not silently remove surrounding text or reorder content.

## 13. Send, Steer, and Stop state machine

### 13.1 Send

Send is available only when:

- authoritative `ThreadStatus` is `idle`;
- the thread is loaded and accepts direct input;
- no conflicting mutation is pending;
- the draft is valid and nonempty.

Send maps to `turn/start` with exact thread identity and a `clientUserMessageId`.

### 13.2 Steer

Steer is available only when:

- authoritative `ThreadStatus` is `active`;
- Dealer knows the exact active regular `turnId`;
- same-turn steering is supported;
- no blocking request remains unresolved;
- no conflicting mutation is pending;
- the draft is valid and nonempty.

Steer maps to `turn/steer` with exact `expectedTurnId`.

Dealer MUST NOT emulate Steer as Interrupt + Send.

### 13.3 Stop

Stop is available when Dealer knows the exact active turn that may be interrupted.

Stop maps to `turn/interrupt(threadId, turnId)`.

After request acceptance, the UI enters **interrupting** and remains there until:

- authoritative `turn/completed` confirms completion/interruption; or
- reconciliation establishes the final state.

The interrupt response alone does not return the composer to idle/Send semantics.

### 13.4 Unknown acceptance

For Send, Steer, or Stop:

- do not clear the draft merely because the connection closed;
- do not retry automatically;
- show pending/reconciling state;
- reconcile from thread history, current active turn, notifications, and `clientUserMessageId`.

## 14. Blocking requests

When `activeFlags` includes `waitingOnApproval` or `waitingOnUserInput`:

- draft editing remains available;
- Steer is disabled until all blocking requests are resolved;
- Send remains unavailable because the thread is active;
- Stop remains available when exact active-turn authority exists;
- the request must be answered through its structured card;
- typing a chat message MUST NOT be treated as a request response.

If both active flags are present, both request categories remain visible and both status icons render.

## 15. Approval and user-input cards

### 15.1 Common rules

Every request card is fenced by:

- host-qualified thread identity;
- exact turn/item/request identity;
- app-server generation;
- normalized request scope/fingerprint where applicable.

A response is sent at most once unless authoritative reconciliation proves non-acceptance.

Unknown acceptance locks the card and reconciles.

### 15.2 Command approval

The card displays all available structured information, including:

- command;
- cwd;
- parsed actions;
- reason;
- network context;
- environment;
- proposed policy amendments.

Supported decisions are represented losslessly:

- accept once;
- accept for session;
- decline;
- cancel;
- explicit exec-policy amendment when offered;
- explicit network-policy amendment when offered.

Policy amendments require a separate, comprehensible confirmation.

### 15.3 File-change approval

The card displays reason, target item, and available diff/context.

Supported decisions:

- accept once;
- accept for session;
- decline;
- cancel.

### 15.4 Permission escalation

This card is distinct from the ordinary permissions-settings sheet.

It displays:

- requested filesystem/network permissions;
- cwd;
- environment;
- reason;
- proposed grant scope.

Dealer responds only with a losslessly representable grant. Unsupported permission shapes fail closed.

### 15.5 Tool user input

Dealer renders all questions from one request atomically.

- Preserve question order and IDs.
- Show headers, prompt text, options, Other availability, and secret status.
- Secret input is masked and excluded from ordinary logging/diagnostics.
- Submit one response map keyed by question ID.
- `isBlocking`, not deprecated timeout metadata, controls blocking behavior.

### 15.6 MCP elicitation

MVP supports MCP elicitation only when the request form can be rendered and answered losslessly by Dealer.

Unsupported field types or malformed forms:

- remain unresolved/fail closed;
- show a clear unsupported-request surface;
- are never answered with fabricated defaults.

### 15.7 Unknown dynamic client requests

Unknown or unsupported dynamic client-tool requests are not silently executed and do not receive fabricated success responses.

## 16. Model, reasoning, and Speed

The Chat intelligence surface contains three distinct selectors:

1. **Reasoning effort**
2. **Model**
3. **Speed / service tier**

### 16.1 Source of choices

Dealer populates controls from the connected host:

- `model/list`;
- selected model's supported reasoning efforts;
- selected model's service tiers and default tier;
- current effective thread settings.

Dealer MUST NOT hard-code a universal model, effort, or Speed list.

### 16.2 Persistence

All three selections persist as next-turn thread settings in MVP.

When supported, Dealer uses `thread/settings/update`:

- `model`;
- `effort`;
- `serviceTier`.

Dealer does not use one-turn `serviceTierForTurn` for the normal Speed selector.

### 16.3 Acceptance

- Show a pending state while updating.
- Commit visible selection only after authoritative acceptance.
- On rejection, retain/restore the prior authoritative setting and show the error.
- On unknown acceptance, disable conflicting changes and reconcile.
- Unsupported settings capability makes the selector read-only/unavailable; Dealer MUST NOT maintain a local fake setting.
- Steer does not carry thread-settings overrides.

### 16.4 Plan mode

Plan mode is omitted from Dealer MVP.

Dealer MUST NOT emulate Plan mode by prepending prompt text.

A future protocol-backed collaboration mode requires a separate specification decision.

## 17. Permissions sheet

Dealer uses one compact visual sheet with three semantically separate groups.

### 17.1 Access Profile

- Source: `permissionProfile/list`.
- Show profile ID/description in user-facing form.
- `allowed = false` entries are disabled and identified as unavailable by host policy.
- The active profile remains authoritative.

### 17.2 Approval Policy

Expose only values supported by the connected app-server:

- untrusted;
- on-request;
- granular policy;
- never.

Granular policy uses a subpage rather than pretending to be one simple preset.

### 17.3 Approval Reviewer

Expose:

- user;
- automatic review (`auto_review`).

`guardian_subagent` is compatibility input, not an ordinary selectable label. Dealer may display an incoming compatibility value as Automatic review.

### 17.4 Applying changes

Changes use `thread/settings/update` and remain pending until accepted.

Profile, policy, and reviewer remain distinct fields even when a shortcut changes more than one atomically.

A server rejection or requirements-policy conflict must be shown; Dealer does not optimistically retain the rejected value.

## 18. Add/plugins menu

MVP menu content is capability-driven.

Required:

- **Upload photo**

Optional when supported by the connected host:

- installed plugins/apps;
- their protocol-grounded invocation entries.

Excluded:

- Plan mode;
- arbitrary file/document upload;
- audio upload.

Dealer does not show a plugin/app action it cannot encode through current supported user-input forms.

## 19. Image attachment lifecycle

MVP supports image attachments only.

### 19.1 Selection and staging

- User selects an image through Android system facilities.
- Dealer validates readable content and stages the exact draft asset.
- Dealer uses an inline image data URL for Codex input in MVP.
- Dealer MUST NOT send an Android-local path as a Codex-host `localImage` path.
- The selected model's input modalities must support image input.

### 19.2 Progress and failure

Each asset has:

- selecting;
- staging;
- ready;
- failed;
- canceled;
- frozen with outbound operation;
- accepted/reconciled.

Failure is visible and does not silently reorder or discard other draft content.

### 19.3 Submission

Images preserve their exact ordering relative to text.

Accepted submission releases the exact staged revision according to retention policy. Unknown acceptance retains it until reconciliation.

Remote HTTP image URLs are not used as an upload shortcut.

## 20. Voice composer and local ASR

The voice composer is a text-entry aid, not an audio-upload feature.

Flow:

```text
record audio
→ Dealer-local ONNX ASR
→ editable transcript
→ ordinary text draft
→ Send or Steer
```

Rules:

- Dealer records local microphone audio.
- Dealer performs recognition locally using a supported deployed ASR model.
- Partial recognition may be shown, but only reviewable transcript text enters the durable draft.
- Recording or transcription never auto-sends.
- Raw audio is not submitted to Codex as `audio` or `localAudio`.
- Poker-recorded audio uses the same Dealer-owned pipeline after future CXR transfer.
- Exact ASR architecture is `SPEC/asr.md`.

## 21. Remote-status panel

The panel may display:

- exact thread ID with copy;
- Project identity;
- current cwd as thread metadata, not Workspace identity;
- current model/reasoning/service tier;
- context/token usage;
- account rate-limit windows;
- Poker attachment;
- connection/freshness.

Rules:

- Values carry source/freshness metadata.
- Unavailable is not rendered as zero.
- Stale cached values are visibly marked stale.
- Rate-limit window labels come from authoritative data and are not hard-coded to seven days.
- If runtime state is shown textually in this diagnostic panel, use exact protocol terms rather than friendly aliases.

## 22. `notLoaded`, reconnecting, and `systemError`

### 22.1 `notLoaded`

- Thread rows show no runtime icon.
- Dealer may use `thread/read` to show stored read-only history.
- Opening Chat triggers `thread/resume`.
- Composer mutation controls remain disabled until loaded authority is restored.
- Resume failure leaves the thread `notLoaded`; Dealer does not fabricate `idle`.

### 22.2 Reconnecting / host unavailable

- Show a separate host/connection banner or surface.
- Keep the draft editable and durable.
- Mark cached timeline/status as stale.
- Hide runtime row icons while freshness is not established.
- Disable unsafe mutations and settings changes.
- Reinitialize, reread/rejoin, and reconcile.
- Do not create an `UNKNOWN` ThreadStatus.

### 22.3 `systemError`

- Render red `!` in the row.
- Chat shows a protocol-grounded error surface using available error information.
- Draft remains durable.
- Retry/resume actions are offered only when they map to a valid lifecycle operation.
- Dealer does not silently convert `systemError` to idle.

## 23. Poker attachment

Poker attachment is independent from:

- Project assignment;
- Chats membership;
- ThreadStatus;
- active turn;
- host availability.

Attachment indicators must be visually distinct from runtime icons.

Attach/detach actions belong in thread context/overflow surfaces, not as a redefinition of runtime.

Exact CXR connection, pairing, readiness, and recovery UX remains deferred.

## 24. ASR, CXR, and data boundaries

- Dealer-local ASR may operate with Poker absent.
- Poker audio transfer requires a qualified CXR path.
- CXR concrete SDK types remain below `PokerTransport`.
- ASR code does not depend on CXR concrete types.
- Codex core does not depend on ASR or CXR concrete types.
- Audio never flows directly from Poker to Codex in MVP.

## 25. MVP exclusions

MVP excludes:

- filesystem/file-manager navigation in Workspaces;
- arbitrary file/document upload;
- raw audio upload to Codex;
- Plan mode;
- named Codex profiles in Dealer;
- Dealer Thread Presets;
- ordinary Personality control;
- ordinary Developer Instructions editor;
- detailed CXR pairing UX before qualification;
- automatic ASR model marketplace/download design;
- a generalized backend adapter;
- OpenAI/ChatGPT branding.

## 26. Accessibility and responsive Android behavior

- All icon-only controls require content descriptions.
- Status icon descriptions use official Codex semantics.
- Color is not the sole accessibility channel; `?`, `●`, `!`, and hourglass shape remain distinguishable.
- Touch targets meet Android accessibility guidance.
- Layout adapts to supported Android screen sizes and orientations without encoding one specific phone model into the product contract.
- Android system status/navigation bars and IME remain system-owned.

## 27. Acceptance criteria

Dealer UI/UX refinement is accepted when tests/design review prove:

- only official `ThreadStatus` and active flags exist on the runtime axis;
- exact status icon mapping is implemented without friendly row labels;
- connection/reconciliation remains a separate axis;
- Send, Steer, and Stop follow exact protocol semantics and no-blind-replay rules;
- interrupting waits for authoritative completion;
- blocking requests allow draft editing but disable Steer;
- request cards preserve all supported structured response choices;
- Workspaces uses official Projects only and contains no file navigator;
- unsupported Project APIs degrade explicitly without cwd pseudo-workspaces;
- Chats membership follows the accepted lifecycle rules;
- model/reasoning/Speed persist only after server acceptance;
- permissions sheet preserves profile/policy/reviewer distinctions;
- Plan mode is absent;
- image attachment is the only Chat upload type;
- voice produces locally recognized editable text and never uploads raw audio;
- stale/unavailable usage is not presented as fresh or zero;
- Poker attachment is visually and semantically orthogonal;
- Dealer remains fully useful with Poker disconnected.
