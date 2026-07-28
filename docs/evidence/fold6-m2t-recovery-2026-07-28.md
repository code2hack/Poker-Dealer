# Fold6 Termux M2T recovery evidence — 2026-07-28

## Scope

This records the issue #14 recovery checks on the real Fold6. Dealer used the production
route-neutral TCP, SSH, daemon lifecycle, proxy WebSocket, app-server, projection, delivery, and
reconnect code. Development control used wireless ADB and a host-side ADB port forward only to
inject and restore faults; neither is a Dealer product route.

Thread IDs, client-generated message IDs, process IDs, socket paths, key material, host pins, and
concrete private endpoints are omitted.

## Tested host

- Samsung SM-F956N (Fold6), Android 16, Samsung build
  `BP4A.251205.006/F956NKSS4DZG1`.
- Termux `0.118.3`, Android ARM64.
- Community distribution `@mmmbuto/codex-cli-termux`.
- Codex CLI, managed Codex, and app-server `0.145.0`.
- Android was awake, powered, at 100% battery, and in active rather than Doze state during the
  destructive process-stop checks. This is lifecycle evidence, not an unplugged battery claim.

The test used a temporary diagnostic `sshd` listener with a dedicated RSA-3072 Dealer key and an
isolated authorized-key file. The normal Termux SSH configuration was not overwritten.

## Faults and outcomes

### Proxy EOF plus unavailable loopback attempt

The opt-in live recovery test closed the genuine app-server WebSocket after the first live agent
delta. Its first reconnect attempt then injected an unavailable loopback route before allowing
the next attempt through the real Fold6 listener.

Observed:

- one initial app-server initialization and one initialization on the recovered connection;
- one bounded backoff update classified as `TCP_CONNECT`;
- three loopback dial attempts total;
- `thread/resume` and authoritative `thread/read` on the new connection;
- exactly one matching client-identified user message;
- terminal outcome `RECOVERED`;
- user-card delivery `DELIVERED`; and
- no second `turn/start`.

### Loopback `sshd` stopped

After the first agent delta, the diagnostic `sshd` master was stopped and its existing proxy
connection was closed. A restoration command restarted only that listener after five seconds.

Observed:

- reconnect failures were classified as `SSH_CONNECT`, distinct from daemon, proxy, and
  app-server failures;
- bounded backoff continued while cached projection state remained available;
- a fresh SSH, proxy, WebSocket, and initialized app-server connection succeeded after restore;
- authoritative readback found exactly one matching user message;
- terminal outcome `RECOVERED`;
- user-card delivery `DELIVERED`; and
- no replay.

### Daemon/app-server stopped

After the first agent delta, a separate SSH control channel ran the community distribution's
machine lifecycle stop command. The active app-server connection ended. Reconnect rechecked
lifecycle state, started the daemon through the Termux adapter, reopened the proxy, initialized,
resumed, and reread the thread.

Observed:

- app-server `0.145.0` restarted;
- terminal outcome `RECOVERED`;
- exactly one matching user message;
- user-card delivery `DELIVERED`; and
- no replay.

### Termux application force-stop

During a live turn, wireless ADB force-stopped `com.termux`, which stopped the Termux host,
`sshd`, daemon/app-server, proxy, and active turn. Termux was reopened and only the isolated
diagnostic listener was restored. Dealer's reconnect path restarted the daemon and inspected the
same host-qualified thread.

Observed authoritative result:

```text
Turn outcome is interrupted; reconnect found 1 matching user message(s).
turn/start was not replayed.
```

The matching user card advanced to `DELIVERED`; Dealer did not fabricate completion. Reopening
Termux produced a fresh shell, demonstrating the documented opportunistic-host limitation. The
previous issue #13 evidence remains the local-TUI coexistence proof; a local TUI can explicitly
reattach to the restarted daemon-backed thread rather than relying on Android to preserve its
process.

## Recovery presentation

Dealer now distinguishes TCP, SSH, daemon, proxy, WebSocket, app-server initialization/request,
turn-start, turn-notification, and reconnect-inspection phases. On Fold6 Termux it presents phase-specific actions for
opening Termux, restoring `sshd`, starting the daemon, repairing an unsupported community
distribution, and waiting for bounded backoff. The service retains cards for the selected
host-qualified thread while unavailable. An `ACCEPTED` or `UNKNOWN` action blocks another turn
until reconciliation, preventing a manual replay after recovery exhaustion.

Completed authoritative recovery ends `RECOVERED`. Authoritative non-completed status maps to
`INTERRUPTED`, `FAILED`, or `UNKNOWN`; cancellation remains `CANCELLED`.

## Automated checks

Regression checks cover:

- six reconnect attempts with exponential backoff capped at eight seconds and bounded by the
  existing reconnect-inspection timeout;
- cancellation during backoff before another connection attempt;
- closure of app-server, SSH, and TCP resources;
- initialization exactly once per new connection;
- authoritative resume/read after reconnect;
- monotonic `LOCAL_PENDING` → `ACCEPTED` → `DELIVERED` or `UNKNOWN` delivery;
- one client-identified user card;
- absence of replay; and
- Termux recovery phase/action presentation.

The complete repository gate passed:

```text
ANDROID_HOME=/home/code2hack/Android/Sdk ./tooling/check.sh
```

The opt-in live Termux suite passed against the real Fold6, including the retained normal
capability checks and the committed proxy-EOF/backoff recovery check.

## Limits

This covers one Fold6, one Termux package, one community distribution/version, and short
controlled interruptions while the phone was awake and powered. It does not claim
workstation-grade availability, other community builds, long-duration Doze behavior, process
survival after force-stop, install/update compatibility, or mixed Codex-version compatibility.
