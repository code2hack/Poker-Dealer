# Dealer UI Design Record

**Status:** Accepted design source; normative behavior reconciled into `SPEC/dealer-ui.md`  
**Date:** 2026-08-26  
**Scope:** Dealer Android client product/UI decisions

This document preserves the accepted design intent. It is non-normative. `SPEC/dealer-ui.md` governs behavior, protocol mapping, recovery, and MVP boundaries.

## 1. Product mental model

Dealer feels like a normal mobile messaging application rather than an engineering diagnostics console.

Primary navigation:

1. **Chats** — Dealer-curated human-attention subset.
2. **Workspaces** — official Codex Projects and their threads.
3. **Settings** — Tailscale, Codex host, Poker, ASR, and general configuration.

Opening a thread pushes Chat above the bottom navigation.

## 2. Setup

Conceptual first run:

1. Tailscale
2. Codex host
3. Poker
4. Complete

Poker is optional. Exact Poker/CXR authorization and recovery remain deferred. Settings reuses individual setup components.

## 3. Chats

- Chats is not the complete thread store.
- Membership is Dealer-local.
- Add/Remove from Chats is not `thread/unsubscribe`.
- Threads group under official Codex Projects.
- Unassigned threads use a distinct Unassigned group.
- Poker attachment remains independent from runtime state.

### Runtime visuals

Normal rows use official Codex state only:

| Official state | Visual |
|---|---|
| `notLoaded` | none |
| `idle` | none |
| `active`, no waiting flags | blue hourglass |
| `active + waitingOnApproval` | yellow `?` |
| `active + waitingOnUserInput` | green `●` |
| `systemError` | red `!` |

No Ready/Working/Attention text labels are part of the row design.

## 4. Workspaces

Workspace means official Codex Project exactly.

Workspaces contains:

- Project list and selection;
- official Project metadata and roots;
- Project management where supported;
- all Project-assigned threads;
- an Unassigned threads section that is not a Workspace;
- thread actions and new-thread creation.

Workspaces intentionally contains **no folder tree or file-manager navigator**. Roots are read-only/selectable metadata only.

When Project APIs are unsupported, show Projects unavailable; do not substitute cwd-based Workspaces.

## 5. New thread

- New thread belongs to the selected official Project.
- One root is automatic.
- Multiple roots use a simple root selector.
- Zero roots inherit app-server cwd defaults.
- No arbitrary path browser.
- No named profile chooser.
- No Dealer Thread Presets.
- No ordinary Personality or Developer Instructions control.
- Dealer-created and Dealer-forked threads are added to Chats.

## 6. Settings

Sections:

- Tailscale
- Codex host
- Poker
- ASR
- General

ASR is Dealer-local ONNX recognition for both Dealer and Poker audio sources.

## 7. Chat MVP

The supplied ChatGPT Remote Android screenshots remain the visual/interaction baseline for:

- floating header;
- timeline;
- user bubbles;
- code blocks;
- composer;
- add/plugins menu;
- permissions menu;
- intelligence/model/speed menu;
- voice composer;
- remote-status panel;
- overflow menu.

Do not copy OpenAI/ChatGPT branding.

Intentional departures from the screenshots:

- Plan mode omitted from MVP.
- Voice produces locally recognized editable text; it is not audio upload.
- Add menu supports image attachment only, not arbitrary documents/audio.
- Missing request/error/reconnect states follow current Codex protocol, not screenshot extrapolation.

## 8. Protocol-driven behavior

Normative protocol-driven areas include:

- official `ThreadStatus`;
- `notLoaded` resume lifecycle;
- Send/Steer/Stop;
- structured approvals and input;
- reconnecting/host unavailable;
- `systemError`;
- image staging;
- model/reasoning/service-tier selection;
- permission profile/policy/reviewer separation;
- command/file/diff cards;
- usage freshness.

See `SPEC/dealer-ui.md`.

## 9. Document mapping

| Design topic | Normative destination |
|---|---|
| Product/navigation | `SPEC/dealer-ui.md` Sections 1–2 |
| State axes/icons | Sections 3–4 |
| Setup | Section 5 |
| Chats | Section 6 |
| Workspaces | Section 7 |
| New thread | Section 8 |
| Settings | Section 9 |
| Chat/timeline/composer | Sections 10–14 |
| Requests | Section 15 |
| Model/Speed/permissions | Sections 16–17 |
| Upload/voice | Sections 18–20 |
| Recovery/status | Sections 21–22 |
| Poker | Sections 23–24 |
| ASR architecture | `SPEC/asr.md` |
