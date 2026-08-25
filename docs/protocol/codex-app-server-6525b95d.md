# Codex App-Server Protocol Evidence — `6525b95d`

**Status:** Non-normative protocol evidence  
**Upstream repository:** `openai/codex`  
**Pinned commit:** `6525b95dae2082ac9fee672b14c2cffdef172bb8`  
**Captured for:** Poker-Dealer Dealer UI/UX specification refinement  
**Captured date:** 2026-08-26

This document records the upstream protocol evidence used to write `SPEC/dealer-ui.md`, `SPEC/dealer-codex.md`, and `SPEC.md`. It does not override those normative specifications.

## 1. Evidence sources inspected

Primary upstream files at the pinned commit:

- `codex-rs/app-server/README.md`
- `codex-rs/app-server-protocol/src/protocol/common.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/thread.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/thread_data.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/turn.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/item.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/project.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/model.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/permissions.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/config.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/account.rs`
- `codex-rs/app-server-protocol/src/protocol/v2/fs.rs`
- generated TypeScript and JSON schemas under `codex-rs/app-server-protocol/schema/`

Generated schema artifacts are version-specific evidence. Experimental fields may be omitted from default generated surfaces unless experimental generation/opt-in is enabled; Rust source and README mark those fields/methods.

## 2. Transport and initialization

- App-server uses JSON-RPC-like request/response/notification messages.
- Stdio is newline-delimited JSON.
- The direct `ws://` listener is documented as experimental/unsupported.
- The retained Poker-Dealer route uses `codex app-server proxy` to the app-server control Unix socket over an authenticated host stream.
- Each connection must send `initialize`, receive success, then emit `initialized` before normal requests.
- Experimental APIs require the corresponding initialization capability.
- Replacement connections require a fresh initialization handshake.

## 3. Thread model

The official `Thread` includes, among other fields:

- `id`
- `sessionId`
- `forkedFromId`
- `parentThreadId`
- `preview`
- `ephemeral`
- `section`
- `projectId`
- `modelProvider`
- timestamps and recency
- `status`
- `path`
- `cwd`
- source metadata
- optional `name`
- `turns` in read/resume/fork responses as documented

### 3.1 Official ThreadStatus

Generated schema:

```text
notLoaded
idle
active { activeFlags: ThreadActiveFlag[] }
systemError
```

`ThreadActiveFlag` values:

```text
waitingOnApproval
waitingOnUserInput
```

`thread/status/changed` contains:

- `threadId`
- the new official `status`

No official `READY`, `BUSY`, `RUNNING`, `ATTENTION_REQUIRED`, or `UNKNOWN` ThreadStatus exists.

## 4. Thread lifecycle

### `thread/start`

Creates a new thread and auto-subscribes the connection. Current parameters include optional model/config overrides, cwd, permissions/policy/reviewer, and experimental `projectId`.

### `thread/resume`

Loads or rejoins a stored thread. It accepts thread configuration overrides and may return reconstructed history. It is the mutation-enabling lifecycle operation for a stored `notLoaded` thread.

### `thread/read`

Reads a stored thread without resuming it. `includeTurns` controls history inclusion. An unloaded stored thread reports `notLoaded`.

### `thread/list`

Paginates stored threads. Official metadata includes status. Experimental filters/fields may include Project assignment depending on experimental opt-in.

### `thread/loaded/list`

Returns IDs currently loaded in memory.

### `thread/unsubscribe`

Removes only the current connection's subscription. Responses distinguish unsubscribed/not-subscribed/not-loaded. Last-subscriber removal does not force immediate unload; app-server may unload later after inactivity. This method is unrelated to Dealer-local Chats membership.

### Other relevant lifecycle methods

- `thread/name/set`
- `thread/archive`
- `thread/unarchive`
- `thread/delete`
- `thread/fork`
- `thread/metadata/update`
- `thread/settings/update` (experimental)

## 5. Codex Projects

The pinned source defines experimental SQLite-backed Project APIs:

- `project/list`
- `project/read`
- `project/create`
- `project/import`
- `project/update`
- `project/move`
- `project/delete`

A Project contains:

- canonical server-generated `id`;
- `name`;
- ordered absolute `roots`;
- opaque metadata;
- manual position;
- timestamps.

Relevant experimental thread integration includes:

- `Thread.projectId`;
- `thread/start.projectId`;
- Project filtering/assignment surfaces;
- `project/changed`;
- `thread/project/updated`.

Project deletion clears assignments but does not delete threads, directories, or files.

Poker-Dealer implication:

> Workspace identity must be official Project identity. Unsupported experimental APIs require explicit degradation, never cwd-based pseudo-workspaces.

## 6. Turns and mutations

### `turn/start`

Required:

- `threadId`
- ordered `input`

Important optional fields include:

- `clientUserMessageId`
- cwd
- model
- effort
- service tier
- permission profile
- approval policy
- approval reviewer

A successful response returns the new turn. Streaming follows through notifications.

### `turn/steer`

Required:

- `threadId`
- ordered `input`
- exact `expectedTurnId`

It appends input to the currently active regular turn. It does not emit a new `turn/started` and does not accept thread-settings overrides. Missing/mismatched/non-steerable active turns reject the request.

### `turn/interrupt`

Required:

- `threadId`
- exact `turnId`

The response requests cancellation. Authoritative completion arrives later through `turn/completed`, normally with interrupted status.

Poker-Dealer implications:

- Send, Steer, and Stop are separate semantic operations.
- Interrupt response acceptance is not turn completion.
- Exact active-turn fencing is required.
- Unknown acceptance must reconcile, not replay.

## 7. User input forms

Current `UserInput` variants include:

- text;
- inline image URL/data URL;
- host-local image path;
- inline audio URL/data URL;
- host-local audio path;
- skill;
- mention.

README evidence states remote HTTP(S) image URLs are rejected; inline data URLs or host-local paths are used. Android-local paths are not Codex-host paths.

Poker-Dealer MVP decision:

- inline image attachment is supported;
- arbitrary document upload is omitted;
- raw audio upload is omitted;
- Dealer-local ASR converts speech to editable text.

## 8. Structured items and notifications

Relevant `ThreadItem` variants include:

- user message;
- agent message;
- reasoning;
- plan;
- command execution;
- file change;
- MCP tool call;
- dynamic tool call;
- collaboration/subagent items;
- image view;
- review-mode items;
- context compaction.

Relevant notifications include:

- `turn/started`
- `turn/completed`
- `item/started`
- `item/completed`
- agent/reasoning deltas
- `item/commandExecution/outputDelta`
- `item/commandExecution/terminalInteraction`
- `item/fileChange/patchUpdated`
- `serverRequest/resolved`
- `thread/tokenUsage/updated`
- `thread/settings/updated`
- `thread/status/changed`

Poker-Dealer implication:

> command and file-change items remain structured timeline cards and update in place.

## 9. Server-initiated requests

The pinned protocol defines:

- `item/commandExecution/requestApproval`
- `item/fileChange/requestApproval`
- `item/tool/requestUserInput`
- `mcpServer/elicitation/request`
- `item/permissions/requestApproval`
- `item/tool/call`
- account token refresh and other infrastructure requests

### Command approval

Params may include:

- kind;
- thread/turn/item identity;
- callback/approval identity;
- timestamp;
- environment;
- reason;
- network context;
- command/cwd;
- parsed command actions;
- proposed policy amendments.

Decisions include:

- accept;
- accept for session;
- exec-policy amendment;
- network-policy amendment;
- decline;
- cancel.

### File approval

Decisions include:

- accept;
- accept for session;
- decline;
- cancel.

### Tool user input

The request contains:

- ordered questions;
- IDs;
- headers/prompts;
- options;
- Other/secret metadata;
- `isBlocking`.

Response maps question IDs to answers.

### Permission escalation

The request contains requested filesystem/network permission shape, cwd, environment, reason, and identity. It is distinct from ordinary thread permission settings.

Poker-Dealer implication:

> supported requests require lossless structured cards; malformed/unsupported blocking requests fail closed.

## 10. Models, reasoning, and service tier

`model/list` reports per model:

- wire model ID/value;
- display name and description;
- supported reasoning efforts;
- default effort;
- input modalities;
- service tiers;
- default service tier;
- hidden/default metadata.

Current thread settings include:

- model/provider;
- reasoning effort;
- service tier;
- permission profile;
- approval policy;
- approval reviewer;
- sandbox compatibility projection;
- other settings.

`thread/settings/update` is experimental and updates persistent next-turn settings. It emits `thread/settings/updated` when effective settings change.

Poker-Dealer MVP decision:

- model, effort, and Speed/service tier persist through thread settings;
- menus are host-driven;
- visible state commits only after server acceptance;
- unsupported capability is explicit, not locally faked.

## 11. Permissions semantics

### Permission profile

`permissionProfile/list` returns:

- profile `id`;
- optional description;
- `allowed`.

### Approval policy

`AskForApproval` includes:

- `untrusted`;
- `on-request`;
- granular policy;
- `never`.

### Approval reviewer

`ApprovalsReviewer` includes:

- `user`;
- `auto_review`;
- compatibility value `guardian_subagent`.

These are separate fields and must not be collapsed into one false enum.

## 12. Effective configuration

`config/read` accepts optional cwd and can return:

- effective config;
- origins;
- optionally layers.

Poker-Dealer MVP inherits host effective configuration and does not add named Dealer profiles or a competing precedence system.

Personality and Developer Instructions remain inherited and are not ordinary Dealer controls.

## 13. Usage and context

Relevant surfaces include:

- `thread/tokenUsage/updated`;
- nullable model context window;
- `account/rateLimits/read`;
- sparse `account/rateLimits/updated`;
- `account/usage/read`;
- optional per-thread usage estimates.

Poker-Dealer implication:

- unavailable is not zero;
- cached data requires freshness;
- window labels are data-driven, not hard-coded.

## 14. Reconnect and reconciliation evidence

Authoritative refresh may use:

- fresh initialization;
- Project list/read when supported;
- thread list/read/resume;
- loaded-thread list;
- current official status;
- turn/item history;
- `clientUserMessageId`;
- request-resolution notifications;
- settings/status notifications.

No notification stream alone is sufficient after a gap. Dealer rereads/rejoins authoritative state and reconciles retained operations without blind replay.

## 15. Upgrade audit rule

When Poker-Dealer changes the supported Codex app-server version:

1. pin the new upstream commit/version;
2. regenerate or inspect matching schemas;
3. diff every protocol surface referenced here;
4. classify stable, experimental, deprecated, and removed fields;
5. update focused specs before implementation behavior changes;
6. preserve unknown-field tolerance and fail-closed blocking-request handling;
7. add compatibility tests/fixtures for changed contracts.
