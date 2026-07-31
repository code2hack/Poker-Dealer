# Spark M3 disposable thread-lifecycle proof — 2026-08-01

## Scope

This follow-up completes the harmless live operation check for issue #28 after the
`ancestorThreadId` qualification recorded on 2026-07-30. It created one disposable Spark thread,
proved Archive, Restore, and Delete against app-server `0.146.0`, and permanently removed that
exact thread. Thread identifiers, content, endpoints, and credentials are intentionally omitted.

## Host and build

- Host: DGX Spark, Linux ARM64.
- One-shot Codex CLI and app-server: `0.146.0`.
- Integrated Dealer debug APK SHA-256:
  `6c3d6f612720908d20d3ec6717f450ca624e6ae33f0b6e6c64f2b9226fbb596e`.
- The full Spark gate passed before the live proof: unit tests, lint, native AAR packaging
  verification, and debug APK assembly.
- u4090 and CI artifact production were not used.

The long-lived production daemon remained on app-server `0.145.0` because a local Codex client was
attached. Dealer therefore continues to disable Archive/Delete on that connection with the
version-specific explanation. The proof used a separate one-shot `0.146.0` app-server and did not
interrupt the production daemon.

## Live operation proof

The client initialized with `capabilities.experimentalApi: true`, created an empty thread in the
integration worktree, started a harmless turn, waited for the authoritative `turn/started`
notification, interrupted it, and waited for `turn/completed`. Authoritative readback then reported
the persisted thread as idle/READY.

For that exact returned locator, the check then:

1. exhausted active and archived `thread/list` pages with all supported source kinds and the
   qualified `ancestorThreadId` filter;
2. proved the disposable thread had zero descendants;
3. called `thread/archive` and proved the locator moved from the active list to the archived list;
4. called `thread/unarchive`, verified its returned locator, and proved only that locator moved back
   to the active list; and
5. called `thread/delete`, then proved `thread/read` failed and neither active nor archived lists
   contained the locator.

The one-shot origin connection did not receive its own lifecycle notification, so the live oracle
was exhaustive authoritative list/read state. Dealer still handles server lifecycle notifications
as authoritative and refreshes discovery after each accepted lifecycle operation; compatibility
fixtures and state tests cover notification-driven detach, restore, and exact host-qualified purge.

## Result and limits

The compatible Spark app-server completed Archive, Restore, and permanent Delete for a harmless
READY thread, and cleanup left no disposable locator behind. This proves one zero-descendant case;
active, ephemeral, unknown-scope, cascade, race, and unrelated-state protections remain covered by
the focused domain, protocol, storage, and Dealer tests rather than destructive live data.
