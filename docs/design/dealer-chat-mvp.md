# Dealer Chat MVP — ChatGPT Remote Mobile Baseline

**Status:** Accepted visual/interaction evidence; behavior is normative in `SPEC/dealer-ui.md`  
**Visual source:** Eight user-supplied ChatGPT Remote Android screenshots captured 2026-08-25  
**Reference canvas:** 390 × 956, preserving the supplied 626 × 1536 aspect ratio

## 1. Source hierarchy

1. `SPEC.md` and `SPEC/dealer-ui.md` are normative.
2. Supplied screenshots are the visual source of truth only for states they visibly show.
3. Pinned Codex app-server protocol evidence is the behavioral source for protocol-driven states.
4. Poker-Dealer correctness invariants govern identity, recovery, uncertain mutation handling, and Poker attachment.
5. Missing states must not be extrapolated as if the screenshots specified them.

No public pixel-level ChatGPT Remote Figma/component specification was identified. Screenshot geometry is a reference, not an upstream API contract.

## 2. MVP visual boundary

Dealer should closely reproduce:

- floating thread/context header;
- conversation timeline;
- assistant text layout;
- user bubble treatment;
- code block/copy treatment;
- collapsed and focused composer;
- add/plugin menu;
- permission menu;
- intelligence/model/speed menu;
- voice composer;
- remote-session status panel;
- thread overflow menu;
- Android keyboard/system chrome integration.

Do not recreate the Android status bar, keyboard, or gesture bar as Dealer UI.

Do not copy ChatGPT/OpenAI logos, names, or trademark branding.

## 3. Geometry reference

Measurements are normalized from the supplied screenshots and are approximate targets, not fixed device constants.

- Frame: `390 × 956`
- Background: black
- Horizontal content inset: approximately `13–17`
- Floating header: top ~`51`, height ~`51`, side inset ~`14`
- Back control: ~`51 × 51`
- Header context pill: flexible width, ~`51` high
- Header action pill: ~`98 × 51`
- Collapsed composer: side inset ~`13`, height ~`50`, pill radius ~`25–30`
- Focused composer: ~`98` high in captured state, two-row control layout
- Add/plugins popover: ~`320` wide
- Permissions popover: ~`316` wide
- Intelligence popover: ~`210` wide
- Remote-status panel: ~`318` wide
- Thread-actions popover: ~`227` wide

Approximate dark tokens sampled from screenshots:

- background `#000000`
- surface `#212121`
- code/subtle surface `#151515`
- raised surface `#2B2B2B`
- border `#3B3B3B`
- primary text `#F5F5F5`
- secondary text `#AAAAAA`
- user bubble `#133362`
- warning `#EFA071`
- destructive `#F27778`

Android system sans / Roboto-like typography is the implementation reference; monospace content uses platform monospace/Roboto Mono equivalent.

## 4. Screenshot-supported states and accepted departures

### S0 — Viewing

- Keyboard hidden.
- One-row pill composer.
- Add button, prompt placeholder, microphone.
- Floating header and timeline visible.

### S1 — Focused composer

- Android IME visible.
- Composer expands.
- Lower row exposes add, permission, intelligence/model/speed, microphone, and submission affordance.

### S2 — Add/plugins

The reference screenshot visibly contains Upload photo, Plan mode, and plugins.

Dealer MVP intentionally changes this:

- Upload photo remains.
- Supported plugins/apps may appear.
- Plan mode is omitted.
- Arbitrary file/document upload is omitted.
- Audio upload is omitted.

### S3 — Permissions

The visual sheet is retained, but normative content is grouped as:

- Access Profile;
- Approval Policy;
- Approval Reviewer.

These are separate protocol concepts, not one enum.

### S4 — Intelligence/model/speed

The visual hierarchy is retained.

Actual options are host-driven:

- supported reasoning effort;
- nested Model;
- nested Speed/service tier.

No screenshot-derived hard-coded option list is normative.

### S5 — Voice capture

The waveform composer is retained as a visual baseline.

Dealer behavior is:

```text
local recording
→ Dealer ONNX ASR
→ editable transcript
→ text Send or Steer
```

No raw audio is uploaded to Codex.

### S6 — Remote-session status

The panel may show:

- thread ID;
- Project;
- cwd;
- context/token usage;
- account rate-limit windows;
- freshness.

Unavailable/stale is not shown as current or zero.

### S7 — Thread overflow

Visual baseline:

- pin/add-to-Chats equivalent where appropriate;
- copy thread ID;
- rename;
- archive.

Dealer-specific actions belong here when required, especially Poker attachment.

## 5. Official runtime icon evidence override

Normal row status follows `SPEC/dealer-ui.md`:

- idle: no icon;
- notLoaded: no icon;
- active/no waiting: blue hourglass;
- waitingOnApproval: yellow `?`;
- waitingOnUserInput: green `●`;
- systemError: red `!`.

The UI does not use Ready/Working/Attention status labels.

## 6. Interaction model

The derived multi-axis diagram is:

`docs/design/dealer-chat-interaction.mmd`

The machine-readable derived summary is:

`docs/design/dealer-chat-state-machine.json`

They are subordinate to `SPEC/dealer-ui.md`.

## 7. Missing states

Screenshots do not specify:

- approval cards;
- user-input cards;
- permission-escalation cards;
- MCP elicitation;
- reconnecting/host unavailable;
- `notLoaded` resume/loading;
- `systemError`;
- image staging/failure;
- nested Model;
- nested Speed;
- active-turn interrupting;
- Send versus Steer;
- settings rejection;
- stale/unavailable usage;
- command/terminal cards;
- file-change/diff cards.

These states follow the pinned current app-server protocol plus Poker-Dealer invariants.

## 8. Responsive implementation direction

- Preserve the visual hierarchy across supported Android screen sizes and orientations.
- Use real Android system IME/chrome.
- Do not encode one specific phone model into layout semantics.
- Keep host-qualified thread/turn identity underneath the IM-style surface.
- Preserve drafts through reconnect and unknown acceptance.
- Keep Poker/Chats management out of the main message surface unless placed in an accepted overflow/context action.
- This 2026-08-25 visual baseline is version-pinned; future ChatGPT UI changes do not silently redefine Dealer.
