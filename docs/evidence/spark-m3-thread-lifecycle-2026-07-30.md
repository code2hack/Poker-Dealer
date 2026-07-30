# Spark M3 thread-lifecycle qualification — 2026-07-30

## Scope

This check qualified the unstable `thread/list.ancestorThreadId` filter used only for Dealer Archive/Delete cascade preflight. It did not archive, restore, delete, rename, or start any thread.

Concrete endpoints, thread identifiers, thread content, and credentials are intentionally omitted.

## Host and versions

- Host: DGX Spark, Linux ARM64.
- Installed Codex CLI: `0.146.0`.
- Qualified one-shot app-server: `0.146.0`.
- Existing long-lived daemon app-server: `0.145.0`.
- Existing daemon managed Codex version: `0.146.0`.

The existing daemon was not restarted because it could own active client sessions. Dealer therefore qualifies the filter only for the exact Spark app-server version `0.146.0`; Archive/Delete remain disabled with an explanation while Spark reports daemon app-server `0.145.0`. No other host or version is qualified by this evidence.

## Live qualification

A read-only one-shot `0.146.0` app-server connection initialized with `capabilities.experimentalApi: true`, then:

1. found an existing persisted parent/child relationship without printing identifiers or content;
2. queried `thread/list` with the known parent as `ancestorThreadId`;
3. exhausted active and archived cursor pages with a page size of one;
4. included every source kind supported by the `0.146.0` schema; and
5. verified that the known child was returned and every returned row exposed its persisted immediate-parent field.

The run found 37 descendants across 37 active pages and one archived page. A negative control initialized without `experimentalApi` and received JSON-RPC error `-32600`, confirming that `ancestorThreadId` is rejected unless the capability is enabled.

## Product gate

Dealer enables the experimental capability and Archive/Delete only when both the host and daemon-reported app-server version match the recorded qualification. Preflight exhausts active and archived pages and reads authoritative metadata again at confirmation; unknown scope, unknown state, active work, and ephemeral threads remain blocked.

Stable `thread/archive`, `thread/unarchive`, and `thread/delete` methods do not inherit this experimental-method allowance. Server notifications remain authoritative for the actual archived, restored, or deleted set.
