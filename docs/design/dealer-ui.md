# Dealer UI Design Record

**Status:** Accepted pre-SPEC design input; non-normative until reconciled into `SPEC.md` / focused SPEC files  
**Date:** 2026-08-25  
**Scope:** Dealer Android client UI/UX discovered with the user before normative SPEC refinement

This document freezes the product/UI decisions reached during the Dealer design exploration. It intentionally does **not** modify the specification hierarchy. The next SPEC refinement should reconcile these decisions against current Codex app-server behavior and existing Poker-Dealer architecture.

## 1. Product mental model

Dealer should feel like a normal mobile messaging application rather than an engineering diagnostics console.

Primary navigation uses three bottom destinations:

1. **Chats** — the human-attention/inbox surface for a curated subset of Codex threads.
2. **Workspaces** — Codex Project browsing/management plus all threads belonging to the selected Project.
3. **Settings** — Tailscale, Codex host, Poker, ASR, and general configuration.

Opening a thread from Chats or Workspaces pushes the **Chat** screen above the bottom navigation.

## 2. First-run / setup flow

The accepted conceptual flow has four pages:

1. **Welcome / Tailscale**
   - Welcome to Dealer.
   - User signs Dealer into Tailscale.
   - The primary action opens the Tailscale login flow.

2. **Choose Codex host**
   - Shown after Tailscale login succeeds.
   - Dealer lists reachable/known Tailscale nodes with basic node/address/reachability information.
   - Selecting a candidate begins host authentication/trust establishment.
   - Host authentication may request username/password when required and should hide TOFU/key-management mechanics from ordinary user copy.
   - User may go back/change Tailscale account.

3. **Connect Poker**
   - Connect the Rokid client to Dealer.
   - Exact CXR connection/pairing UX is **deferred to CXR qualification**; do not invent it from the legacy transport.
   - User may skip Poker connection and still use Dealer.

4. **Complete**
   - Shows successful setup summary and enters the main app.

Settings should reuse the relevant setup components without forcing the full four-step wizard:

- Change Tailscale account -> Tailscale setup, then required host revalidation.
- Change Codex host -> host setup.
- Reconnect/re-pair Poker -> Poker setup.

## 3. Chats

Chats is a curated inbox, not the complete Codex thread store.

### 3.1 Grouping

- Threads are grouped under **Codex Projects**.
- Project groups are collapsible/expandable.
- A thread may exist in Codex and Workspaces without appearing in Chats.
- `Add to Chats` / `Remove from Chats` are Dealer-local organization operations and must **not** be confused with Codex `thread/unsubscribe`.

### 3.2 Thread row

A row should communicate at least:

- Thread name/title.
- User-facing runtime/work status derived from authoritative Codex app-server state.
- Poker attachment indicator, independently from runtime status.

Use official Codex app-server thread status semantics as the behavioral source of truth. Current conceptual presentation mapping:

- `idle` -> **Ready**
- `active` with no waiting flags -> **Working**
- `active + waitingOnApproval` -> **Approval required**
- `active + waitingOnUserInput` -> **Waiting for you**
- `systemError` -> **Error**
- `notLoaded` -> lifecycle state; normally not promoted as a high-attention label unless operationally relevant
- Dealer may additionally represent **Unknown** when authoritative status cannot currently be established; Unknown is Dealer-local and not a Codex status.

Poker attachment is orthogonal: a Ready, Working, waiting, or error thread may independently be attached or not attached to Poker.

### 3.3 Thread actions

Long-press / context actions may include:

- Attach / detach to Poker.
- Remove from Chats.

Protocol-level subscription management must not be exposed under misleading inbox terminology.

## 4. Workspaces

**Accepted decision: Dealer's user-facing Workspace maps to an official Codex Project.**

The Workspace screen is the management/browsing surface for the selected Codex Project.

### 4.1 Project context

The screen should show the current Project identity and its root/path context. If Codex Projects expose multiple roots, the UI must follow the official Project/root model rather than pretending a Project is always one cwd.

### 4.2 Project contents

The design intent includes a project-root/folder browser with folder creation/rename affordances. Exact filesystem behavior and safety requirements must be grounded in supported Codex app-server filesystem/project APIs during SPEC refinement.

### 4.3 Threads section

Workspaces shows all relevant Codex threads belonging to the selected Project, not only Dealer-curated Chats.

Context actions:

- Add to Chats (Dealer-local)
- Rename (`thread/name/set` candidate)
- Archive (`thread/archive` candidate)
- Fork (`thread/fork` candidate)

### 4.4 New thread

The Workspace screen owns new-thread creation for that Project.

**MVP configuration decision:**

- Dealer exposes **no named Codex profile chooser**.
- Dealer exposes **no Thread Preset concept**.
- New threads inherit the host's default Codex configuration as resolved by Codex/app-server.
- Dealer does not implement its own competing config/profile precedence system.
- The normal creation dialog should therefore remain minimal (for example optional thread name/title plus create action); Project/workspace context already determines the target Project.

## 5. Settings

Primary sections:

- **Tailscale account** — basic account/network information, logout/change account.
- **Host** — basic active Codex host information, change host.
- **Poker** — connection information, reconnect/re-pair entry.
- **ASR** — speech-input settings; detailed design deferred.
- **General** — ordinary app preferences.

Explicit MVP exclusions:

- No Thread Presets section.
- No named Codex config-profile management UI.
- No ordinary Personality control.
- No ordinary Developer Instructions editor.

Personality/developer-instruction behavior should be inherited from Codex configuration/instructions unless a future requirement justifies advanced controls.

## 6. Chat MVP

The MVP Chat screen deliberately uses the supplied **ChatGPT Remote mobile Android UI as the visual/interaction baseline** for the states visible in the reference screenshots.

The goal is to reproduce the layout and interaction grammar closely while retaining Poker-Dealer's own architecture and Codex app-server authority model. Do not copy ChatGPT/OpenAI logos, product naming, or trademarked branding.

Observed baseline states:

1. Viewing / collapsed composer
2. Focused composer + system IME
3. Add/plugins popover
4. Permissions popover
5. Intelligence/model/speed popover
6. Voice-capture composer
7. Remote-session status panel
8. Thread overflow actions

Dealer-local additions to the main Chat surface are deferred from MVP. The overflow menu is the designated place for any future Dealer-specific thread actions, but the MVP should begin from the supplied Remote-menu baseline unless the normative SPEC explicitly requires otherwise.

See `docs/design/dealer-chat-mvp.md` for geometry/state details.

## 7. Protocol-driven states, not screenshot invention

**Accepted decision:** states not fully evidenced by the ChatGPT Remote screenshots must be designed from current Codex app-server protocol/docs and Poker-Dealer invariants, not guessed from screenshots.

This specifically includes:

- Approval cards
- User-input request cards
- Reconnecting / host unavailable
- `notLoaded` -> resume/loading
- `systemError`
- Upload progress/failure
- Nested Model selector
- Nested Speed/service-tier selector
- Active-turn Stop behavior
- Send versus Steer semantics
- Settings-update rejection/forbidden values
- Context/usage unavailable or stale
- File-change/diff cards
- Command/terminal cards

Where the protocol is insufficient, the SPEC must mark the UI behavior as an explicit product decision or deferred item.

## 8. Codex runtime/config principles established during design

- Official app-server `ThreadStatus` / active flags should replace the Legacy `READY / RUNNING / ATTENTION_REQUIRED` trio as the underlying authoritative runtime model. Legacy-style words may still be presentation labels.
- `waitingOnApproval` is an active flag under `active`, not a peer of `idle`.
- `thread/resume` loads/rejoins a stored thread; `thread/read` does not resume it.
- `thread/unsubscribe` stops a client subscription but does not mean "Remove from Chats" and does not force immediate unload.
- Permission profile answers **what Codex may access/do**; approval policy answers **when authorization is requested**. They must remain separate semantics even if a compact UI groups them visually.
- MVP uses only the default Codex configuration surface; Dealer does not expose named profile selection or Dealer Thread Presets.

## 9. Design principles to carry into SPEC

1. **IM mental model:** ordinary messaging UX on top; precise Codex semantics underneath.
2. **Authoritative protocol state:** never invent runtime state that conflicts with app-server.
3. **Chats vs Workspaces:** Chats is the human-attention subset; Workspaces is the complete Project management surface.
4. **Project identity:** Workspace means Codex Project.
5. **Orthogonal Poker state:** Poker attachment does not redefine Codex runtime state.
6. **Minimal configuration:** default Codex config in MVP; no profile/preset layer in Dealer.
7. **Hardware independence:** exact CXR connection UX remains deferred until qualified with real-device evidence.
8. **Design-before-implementation:** current diagnostics activity is not UI authority.

## 10. Items deliberately left for SPEC refinement

- Exact Project root/folder browser semantics and allowed filesystem mutations.
- Exact CXR connect/pair/recovery flow.
- ASR behavior and persistence.
- Exact Chat approval/input/error/reconnect card designs derived from app-server protocol.
- Exact Model / reasoning effort / service-tier menus supported by the connected host.
- Exact overflow-menu MVP contents if Poker-Dealer needs an action beyond the Remote baseline.
- Durable semantics for Dealer-local Chats membership.
