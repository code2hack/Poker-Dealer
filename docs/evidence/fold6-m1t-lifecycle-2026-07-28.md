# Fold6 embedded-tailnet lifecycle evidence — 2026-07-28

## Scope

This records the real-device checks performed for GitHub issue #12 after the routing proof in
`fold6-m1t-routing-2026-07-28.md`. Private addresses, credentials, thread IDs, prompt text, and
client-generated message IDs are omitted.

- Fold6: Samsung SM-F956N, Android 16, Samsung build
  `BP4A.251205.006/F956NKSS4DZG1`.
- Dealer: debug `0.1.0-m1`, final tested APK SHA-256
  `de7632d103873179ac1c52be8296c61f5377faeb09b5de10faf9a4ddcd590d63`.
- Embedded toolchain: Go 1.26.5, Tailscale Go module 1.102.0, gomobile
  `6129f5bee9d5`, Android ARM64, NDK 23.1.7779620.
- Third-party VPN: Karing 1.2.19 (2209).
- Workstation: u4090, Ubuntu/Linux x86-64, Codex/app-server 0.145.0.
- Underlay: Fold6 cellular data, with Karing still owning Android's VPN slot.

## Activity, service, restart, and power-management checks

With the embedded node connected, Android's `always_finish_activities` developer setting was
temporarily enabled. Leaving and reopening Dealer destroyed and recreated the Activity while
retaining process PID 4357, the foreground service, its binding, the connected state, and node name
`dealer-fold6`.

ADB then simulated unplugged power and forced deep idle with the screen off. The observed interval
was 1 minute 52 seconds, from 03:43:55 to 03:45:47 local time. USB ADB disappeared during idle and
returned after wake, while PID 4357 and foreground notification ID 4090 remained. From u4090, the
embedded node stayed online and became directly reachable again in 5 ms. Android idle and battery
overrides were reset immediately after the check.

Dealer was then force-stopped and relaunched. Starting the embedded node reused its app-private
identity and reported `Connected`, node `dealer-fold6`, without showing a login URL. The new process
PID was 12446 and the node was online within 5 seconds. Reinstalling an updated APK later in the
same session again reused the identity and connected without enrollment.

These are induced short-duration checks, not evidence that Android will preserve Dealer through
arbitrary OEM process killing or long unattended doze.

## Turn, interruption, fallback, and cancellation checks

After process restart, a full embedded-route M1 turn completed in 14 seconds. It performed the
normal second connection and authoritative delivery reconciliation. The host thread log contained
one semantic user-message event for the unique test text.

A configured but unreachable trusted-LAN endpoint was then placed ahead of the embedded route. The
run retained `SSH_LAN: connection is closed by foreign host`, selected
`SSH_EMBEDDED_TSNET`, retained `SSH_EXTERNAL_TAILSCALE: DISABLED`, and completed in 31 seconds.
The host thread again contained exactly one semantic user-message event.

During a separate active M1 turn, stopping the embedded node closed the tunnel and app-server
stream. Dealer reached `Error` one second later, displayed
`app-server connection closed before turn ... completed`, and retained all three route diagnostics
as disabled for recovery. The unique test text appeared in exactly one semantic user-message event;
`turn/start` was not replayed.

During another active M1 turn, the phone UI's Cancel button moved Dealer from `Running` to
`Cancelled` in one second. The submitted card remained the one accepted card and the host log
contained one semantic user-message event. Existing JVM tests additionally verify that cancellation
closes app-server, SSH, TCP, and WebSocket resources; the production embedded stream closes its
native tunnel when that shared TCP resource closes.

Android 13+ notification permission was missing before this run, which hid the foreground
notification action on the Fold6. Dealer now declares and requests `POST_NOTIFICATIONS` when the
Activity is first created. With permission granted before starting the node, Android recorded a
posted foreground notification with one `Cancel` service `PendingIntent`. The Samsung notification
shade displayed the Dealer notification. Automated tapping of the expanded action was not completed;
the action target remains the same `ACTION_CANCEL` path that cancels the run and stops the embedded
node.

## Battery observation and limitation

No clean battery acceptance measurement was possible in this session because the Fold6 remained
physically attached over USB for ADB and reported charging. The short forced-idle check used
Android's battery override and is not a physical discharge measurement.

For transparency, Android BatteryStats' mixed-session estimate for Dealer was 9.71 mAh across
53 minutes 17 seconds of foreground time and 17 minutes 6 seconds of foreground-service time. Its
on-battery screen-off/doze CPU estimate was 0.0457 mAh; nearly all remaining estimate was recorded
while physically powered and while the UI, builds, installs, and tests were active. This sample
cannot be assigned to idle tsnet or an active connection and is rejected as an M1T battery result.

A repeatable idle-node and active-connection battery run still requires:

1. physical USB disconnection after establishing wireless diagnostics or a timestamped manual
   procedure;
2. fixed screen, radio, Karing, thermal, and battery-start conditions;
3. separate idle and active intervals long enough for useful charge-counter or BatteryStats
   resolution;
4. direct and real DERP-relayed active intervals measured separately.

No battery threshold is inferred from the rejected sample.

## Remaining limits

- A real DERP-relayed full M1 turn remains unproven; every working peer path was direct.
- Notification cancellation is posted and wired but was not tapped end-to-end in the Samsung shade.
- Long unattended background/doze, OEM process killing, and user-visible recovery remain unproven.
- Idle, direct-active, and DERP-active physical battery observations remain incomplete.
- Results cover only the device, versions, VPN profile, tailnet, and Codex build listed above.

Issue #12 must remain open until those remaining hardware observations are recorded.
