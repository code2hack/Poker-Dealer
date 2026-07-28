# Spark and u4090 M2 workstation evidence — 2026-07-28

## Scope

This check exercised the same route-neutral Dealer SSH, proxy WebSocket, app-server, projection, turn-delivery, and reconnect stack against both supported workstation hosts. It did not claim M2 complete.

## Hosts

- DGX Spark: Linux ARM64, Codex CLI and managed app-server `0.145.0`.
- u4090: Linux x86-64, Codex CLI and managed app-server `0.145.0`.

Spark began the session on CLI `0.144.6` and was updated to `0.145.0` before the live Dealer-stack test. This evidence therefore does not prove mixed-version compatibility.

## Host-local TUI coexistence

Each workstation started its managed daemon and opened a dedicated host-local TUI with `codex --remote unix://...` against that daemon's control socket. Each TUI created and completed one harmless marker turn and remained attached while the Dealer-stack test used the same host-qualified thread.

The tmux sessions used to keep the local TUIs open were administration surfaces only. No tmux pane capture, inference, or input path was part of the product test.

## SSH trust

Spark received dedicated Dealer public keys. The JVM live test used ED25519. The Fold6 Android SSH provider could not authenticate with that private-key format, so the on-device run used a dedicated RSA-3072 key. Private keys remained outside the repository with mode `0600`. Both live tests and the on-device run used strict JSch host-key checking with host-specific pinned `known_hosts` data. Host-key checking was not disabled.

After the proof, the superseded ED25519 Dealer authorization and local key pair were removed. Spark retains only the Android-compatible dedicated RSA Dealer authorization.

Android negotiated a different Spark host-key algorithm than the JVM test. The LAN and tailnet RSA, ECDSA, and ED25519 fingerprints were compared and matched route-for-route before all reviewed keys were included in Dealer's pin file.

Concrete private-network endpoints, private keys, and host-key material are intentionally omitted.

## Live test

`WorkstationLiveM1Test` replaced the u4090-only test name and selects `spark` or `u4090` from the shared workstation catalog. The identical test passed separately against both hosts.

For each host it verified:

1. LAN TCP connection through the route-neutral dialer;
2. pinned-key SSH authentication;
3. managed daemon status;
4. `codex app-server proxy`, WebSocket upgrade, and initialize/initialized;
5. listing, resuming, and reading the TUI-created thread;
6. projecting existing history;
7. starting and streaming one Dealer turn;
8. reconnecting through a fresh initialized connection;
9. reconciling exactly one client-identified user message as delivered; and
10. retaining only the attempted LAN route in diagnostics.

## Take-control behavior

Dealer now requires an explicit process-local control claim for the exact `(hostId, threadId)` before its service accepts a turn. Changing either identity invalidates the claim for sending. Dealer can yield the indicator back to the local TUI. Unit coverage verifies host, thread, and surface mismatches.

This is the specification's permitted soft control indicator, not a distributed lock. Server rejection and delivery reconciliation remain authoritative.

## Fold6 run

The updated APK was installed on the Fold6. The UI selected DGX Spark, claimed Dealer control for the exact Spark thread, imported the dedicated private key and reviewed host keys, and submitted `Reply exactly DEALER_SPARK_ANDROID_M2_OK.` No malformed turn was sent during setup.

The phone's active system VPN routed the trusted-LAN address through `tun0`. The LAN SSH attempt was closed by the foreign host. Dealer retained that failure, selected the configured embedded-tsnet route to the same Spark host, completed the turn, streamed `DEALER_SPARK_ANDROID_M2_OK`, reconnected, and reconciled the single user card to `DELIVERED`. The terminal one-shot state was `COMPLETED`.

All temporary private-key and host-key copies were removed from Fold6 shared storage after import. Dealer's embedded tailnet was stopped after the proof.

Expanded route diagnostics pushed the fixed-height form below the phone viewport during the run. The setup form was subsequently made vertically scrollable; the temporary display-density change used to finish the already-configured proof was restored.

## Remaining limitations

- Exercise differing supported Codex versions on the two workstations; both were `0.145.0` during these live tests.
- External-Tailscale fallback remains explicitly disabled rather than proven.
- The soft control claim is process-local pending the planned host/thread Room persistence.
