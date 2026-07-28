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

A later unplugged observation used wireless ADB and left the screen off without forcing Android
idle state. After an initial charger/fuel-gauge transition, the clean interval ran for 27 minutes
54 seconds, from 12:53:49 to 13:21:43 local time. Android remained unpowered and discharging; the
screen was dozing and light idle was `IDLE` at the end. Dealer retained PID 27818, its foreground
service, notification ID 4090, and the embedded node. Spark reported `dealer-fold6` online while
there was no active peer connection. After wake, the same PID remained.

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
shade displayed the Dealer notification.

A later wireless-ADB run expanded that real Samsung notification and tapped its visible `Cancel`
action end-to-end. Dealer changed the embedded node from `Connected` to `Stopped`, removed its
foreground notification, and left no active host run. This used the same `ACTION_CANCEL` service
path as UI cancellation. The tested APK included the subsequent M2 workstation UI changes and had
SHA-256 `11df7f20d42d23ca0a7d28e82f562e624f39457cfc808f1540f32216f7fc471e`;
the notification action implementation itself was unchanged.

## Battery observation and limitation

No clean battery acceptance measurement was possible in this session because the Fold6 remained
physically attached over USB for ADB and reported charging. The short forced-idle check used
Android's battery override and is not a physical discharge measurement.

For transparency, Android BatteryStats' mixed-session estimate for Dealer was 9.71 mAh across
53 minutes 17 seconds of foreground time and 17 minutes 6 seconds of foreground-service time. Its
on-battery screen-off/doze CPU estimate was 0.0457 mAh; nearly all remaining estimate was recorded
while physically powered and while the UI, builds, installs, and tests were active. This sample
cannot be assigned to idle tsnet or an active connection and is rejected as an M1T battery result.

The later measurements used wireless ADB with physical USB, AC, and wireless charging all
disconnected. They recorded the physical charge counter and fixed the screen and probe conditions
within each interval. No battery threshold is inferred from any sample.

The later unplugged idle-node interval above used Android's physical charge counter. It fell from
3,449,925 µAh to 3,287,475 µAh, an observed 162.45 mAh over 27 minutes 54 seconds; the displayed
battery level fell from 81% to 77% and temperature from 36.4°C to 31.8°C. Conditions were cellular
data, Karing connected, wireless ADB connected, screen off/dozing, Dealer foreground service
running, embedded node online, and no active tailnet peer path. This is one observation, not an
isolated estimate of Dealer or `tsnet` consumption, because Android, Karing, cellular, Wi-Fi, and
wireless ADB remained active.

Immediately after wake, Spark made three real relayed probes to the same embedded node. All three
used `DERP(sin)` in 202–361 ms and Tailscale reported that a direct connection was not established.
A later pre-turn probe used `DERP(sfo)` in 523 ms, again without establishing a direct connection.

Dealer then ran a full Spark turn. Trusted LAN failed first, Dealer selected
`SSH_EMBEDDED_TSNET`, and the optional external route remained `DISABLED`. During the SSH,
app-server, and turn traffic, Spark continuously reported the Dealer peer active with no direct
address and relay `sfo`; peer byte counters increased from 5,644/6,908 to 19,948/57,060 bytes.
The host recorded one user message at 13:31:37 local time, the exact response
`DERP12-20260728` at 13:31:42, and task completion one second later. Dealer reported `Completed`
with the user card `COMMITTED | DELIVERED`. This is the real DERP-relayed full-turn proof that was
missing from the earlier routing evidence.

For a relayed-active observation, the Fold6 was again unplugged with cellular data, Karing,
wireless ADB, screen off, and the embedded node running. Spark sent a low-rate peer probe every
few seconds, and every successful probe used `DERP(sfo)` with direct connection unavailable. The
clean measured interval ran for 6 minutes 3 seconds, from 13:35:42 through the last successful
probe at 13:41:41. The physical charge counter fell from 3,137,850 µAh to 3,073,725 µAh, an
observed 64.125 mAh; displayed battery fell from 74% to 72% and temperature from 34.5°C to 34.1°C.
The first timeout four seconds later ended the active interval. Dealer retained PID 27818 and
foreground notification ID 4090. Waking the Fold6 restored the same relayed peer on the first
probe without restarting Dealer or re-enrolling the node. This is a low-rate probe-active
observation, not a sustained bulk-transfer or isolated Dealer-only energy estimate.

For a comparable direct-active observation, Karing was stopped and the embedded node restarted.
Spark then reached Dealer at its direct IPv6 endpoint, normally in 30–80 ms. With the Fold6
unplugged, cellular data and wireless ADB active, screen off, and the same low-rate probe pattern,
the clean measured interval ran for 5 minutes 1 second from 13:45:06 to 13:50:07. Every successful
probe was direct. The physical charge counter fell from 3,039,525 µAh to 3,013,875 µAh, an observed
25.65 mAh; displayed battery remained 71% and temperature fell from 34.0°C to 32.9°C. This is also
a short probe-active whole-device observation, not a sustained transfer or isolated component
estimate. After the observation, Karing was restored and validated as Android's active VPN,
Dealer's embedded node was stopped, and wireless ADB remained enabled for diagnostics.

## Remaining limits

- Longer unattended background/doze and OEM process killing remain unproven beyond the natural
  28-minute observation and the observed wake-to-recover behavior.
- The battery observations are short, include cellular, hotspot/wireless ADB, Android, and other
  running apps, and do not establish a production threshold or isolate Dealer consumption.
- Results cover only the device, versions, VPN profile, tailnet, and Codex build listed above.

These limitations do not invalidate the issue #12 observations; they bound what the recorded
results prove.
