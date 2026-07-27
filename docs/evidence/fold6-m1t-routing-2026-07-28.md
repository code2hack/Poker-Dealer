# Fold6 embedded-tsnet routing evidence — 2026-07-28

## Scope

This records the real-device checks for GitHub issue #11. Private addresses, tailnet names, keys, host-key material, thread IDs, prompts, and client IDs are omitted.

- Fold6: Samsung SM-F956N, Android 16, Samsung build `BP4A.251205.006/F956NKSS4DZG1`.
- Dealer: debug `0.1.0-m1`. The full final-hardware M1 run used APK SHA-256 `5a8de756770b2e978c1a18359a6e8b90b0026a8277289a226f375112230fe8ee`. Review then added only the machine-approval recovery diagnostic and its unit tests; the fully gated and installed handoff APK is SHA-256 `461b472ff03beefc330187a3c1d663d370b535114fa768ec6b38e264530d1135`, and reused the enrolled identity under Karing.
- Embedded toolchain: Go 1.26.5, Tailscale Go module 1.102.0, gomobile `6129f5bee9d5`, Android ARM64, NDK 23.1.7779620.
- Third-party VPN: Karing 1.2.19 (2209).
- Workstation: u4090, Ubuntu/Linux x86-64, upstream Codex and app-server 0.145.0, host Tailscale 1.98.4.
- Underlay during the recorded runs: Fold6 cellular data. The ordinary Android hotspot interface for Poker was present but was not the default internet underlay.

## Procedure and results

Dealer enrolled the persistent `dealer-fold6` userspace node through interactive browser login. It imported a test-only SSH private key and a `known_hosts` file through Android's document picker, then used only the embedded-tsnet route. Each successful row below completed the full existing M1 sequence: route selection, pinned-host-key SSH, daemon query, app-server proxy WebSocket, initialization, thread resume/read, one streamed turn, fresh connection, reinitialization, and delivery reconciliation without replaying `turn/start`.

| Android VPN condition | Karing per-app condition | Result | Peer path observed from u4090 |
| --- | --- | --- | --- |
| Karing stopped; no Android VPN | not applicable | `Completed`, embedded route selected, app-server 0.145.0 | direct |
| Karing owned `VpnService` | whitelist enabled; Dealer unchecked/bypassed | `Completed`, embedded route selected, app-server 0.145.0 | direct |
| Karing owned `VpnService` | whitelist enabled; Dealer checked/proxied | `Completed`, embedded route selected, app-server 0.145.0 | direct |

Android connectivity diagnostics identified Karing as the owner of the validated `tun0` VPN. In whitelist mode with Dealer checked, Android reported an explicit VPN UID set containing Dealer. In the user's original unchecked configuration Android reported Karing's broad VPN UID range, while Karing's own per-app screen identified Dealer as unchecked/bypassed; the bypass claim is therefore limited to Karing's configured routing behavior, not generalized from Android's UID range. Karing remained connected after the test.

u4090 reported an active peer with a current direct endpoint in every successful condition. A DERP home region was available, but the active path remained direct. Karing's proxy and bypass modes both preserved working UDP/direct connectivity, so neither produced a relay-only condition.

## Diagnostics and failure behavior

- Android 16 initially denied Go's direct netlink interface enumeration. Dealer now supplies the active Android `LinkProperties` snapshot through Tailscale's supported `netmon` interface hook instead of requiring privileged netlink access.
- The final APK observed Karing stop and restart while the embedded node remained running. Native diagnostics recorded the default interface changing `tun0` -> cellular -> `tun0`, and Dealer remained `Connected (direct)` after both injected network-change events.
- The embedded engine initially lacked a writable Tailscale log-state location. Dealer now points it at Dealer-private storage while retaining the no-support-log policy.
- Missing `ACCESS_NETWORK_STATE` was surfaced by Android and added as the sole new platform permission.
- A deliberately incomplete SSH pin set was rejected as `SSH host key is not pinned`; a complete pin set then connected. Android-side authentication with the ephemeral Ed25519 test credential was rejected without password fallback; the successful hardware runs used an ephemeral RSA test credential. Both credential types were valid from the JVM/Linux client, so this evidence does not generalize the Android result beyond the tested build.
- Stopping the embedded node and attempting a turn produced `SSH_EMBEDDED_TSNET: DISABLED` and an actionable all-routes-disabled error. Restarting reused the private node identity and returned to `Connected` without login.
- Dealer now reports an active workstation peer as `Connected (direct)` or `Degraded (DERP <region>)`; the relay case also explains that direct connectivity is unavailable. Other Tailscale health failures remain `Degraded` with their health text. An offline self node reports `Unavailable` with instructions to check the active network and retry. Unit tests cover direct, DERP, peer-relay non-misclassification, degraded, and unavailable status encoding.

## DERP limitation

No real network available during this session blocked the direct path, including Karing's actual proxied mode. Therefore this evidence does **not** claim a completed DERP-relayed M1 turn. Forcing Tailscale's internal debug DERP knob or deliberately damaging the phone/workstation firewall would test an artificial configuration rather than the user's deployed path. A future acceptance run needs an underlay that genuinely blocks direct UDP while leaving coordination and DERP reachable.

## Isolation and security observations

- Dealer declared no Android `VpnService`; Android showed Karing as the only VPN owner.
- The embedded Tailscale node ran userspace-only and created no Android TUN, default route, or system-wide VPN.
- No exit node was selected. The peer's available DERP region was not an exit-node route.
- The Go boundary exposed only authenticated, loopback, single-destination tunnels. The recorded tunnels targeted the configured u4090 SSH service; Compose, SSH, and app-server layers received no Go networking types.
- Android's unrelated app traffic remained governed by the ordinary cellular/Karing configuration. Dealer had no API capable of routing that traffic.
- SSH strict host-key checking remained enabled independently of tailnet identity, as demonstrated by the rejected incomplete pin set.
- Tailnet identity and native log state remained in Dealer-private storage. Test SSH credentials were memory-only in Dealer and were removed from shared phone storage after the run.

## Remaining limits

- A real DERP-relayed full M1 turn is not proven for the reason above.
- This session did not provide a long-duration battery measurement or Android process-kill/suspension run.
- Android 16 warned that the debug APK's `libgojni.so` and `libandroidx.graphics.path.so` were not 16 KiB page aligned. The warning did not block these runs, but production Android 16 packaging remains separate follow-up work.
- This covers one Fold6 software build, one Karing version/profile, one embedded Tailscale version, and Codex/app-server 0.145.0; it is not a broad compatibility claim.
