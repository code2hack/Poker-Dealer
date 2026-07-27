# Poker–Dealer M1 Architecture Grill Handoff

**Saved:** 2026-07-27
**State:** M0 complete; M1 architecture grill in progress; no M1 product code
implemented during this grill.

## Resume here

The last approved decision is ADR-0007: M1 configures no STUN/TURN, keeps the
Termux WebRTC route on-device, and uses only the approved Tailscale route for
Spark.

The next question has **not** been approved:

> ICE RFC 8445 forbids loopback candidates. Should the `Termux (local)`
> profile deliberately permit `127.0.0.1`/`::1` ICE candidates only inside
> its SSH-authenticated, same-device session, fail closed if the Fold6 probe
> cannot prove interoperability, and never permit that exception for Spark?

The current recommendation is **yes**. It preserves one WebRTC data plane and
does not make local Termux depend on Wi-Fi or Tailscale, but it is a deliberate
standards exception for one controlled profile. Do not record it as accepted
until the user approves it.

## Settled M1 architecture

- Follow milestones M1 through M9 in order.
- Dealer uses production Bridge client code in M1 acceptance; no disposable
  validation-only client.
- One standard Dealer product SSH key is reused across authorized Hosts. It is
  not the user's general-purpose login key.
- The user manually installs an OpenSSH `restrict` entry forcing the absolute
  `poker-dealer-bridge rtc-bootstrap` command. Poker–Dealer never edits
  `authorized_keys` or `sshd_config`.
- SSH pins Host identity, authenticates Dealer, carries bounded SDP/ICE and
  health/recovery messages, and remains open as the lifetime supervisor.
- Bridge Protocol traffic uses one reliable ordered WebRTC data channel with
  one UTF-8 JSON envelope per data-channel message.
- Closing supervisory SSH ends the Bridge process and its WebRTC session.
- M1 has no persistent Bridge daemon, custom WSS listener, STUN/TURN service,
  Mosh transport, media tracks, or public-network fallback.
- Termux WebRTC traffic remains on-device. Spark WebRTC traffic uses its
  approved Tailscale route; Tailscale's own direct, peer-relay, or DERP
  selection is below the WebRTC seam.
- Tmux discovery, identities, instance invalidation, snapshot-authoritative
  topology, atomic revision batches, and custom-socket configuration are
  settled in `SPEC.md` and the route map.

Current accepted M1 Bridge architecture records are ADR-0006 and ADR-0007.
ADR-0002 through ADR-0005 remain as superseded historical records: ADR-0005
supersedes ADR-0003 and ADR-0004, while ADR-0006 supersedes ADR-0002 and
ADR-0005.

## Provisional implementation research

The implementation stack is researched but **not approved**. See
`research/m1-webrtc-implementation-options.md`.

Current recommendation:

- Android: a source-built, checksummed `arm64-v8a` Google libwebrtc AAR from a
  pinned upstream commit, isolated behind the Bridge client module.
- Rust Host: `str0m` 0.21.x with its RustCrypto backend, driven by the existing
  Tokio process and explicit caller-supplied candidates.

After the loopback decision, grill these one at a time:

1. approve or reject the libwebrtc + `str0m` implementation family;
2. settle the Bridge client module seam and Android AAR provenance/build;
3. settle the Android SSH library and initial host-key enrollment UX;
4. settle bounded SSH signaling framing, offerer role, and recovery ordering;
5. execute the smallest Fold6↔Termux and Fold6↔Spark transport spike before
   full M1 implementation.

## Required spike gates

- Build/load Android libwebrtc on the API-36 ARM64 Fold6, including 16-KiB
  native-library alignment.
- Prove the local Termux candidate pair with Tailscale both off and on.
- Prove Spark selects only the configured Tailscale route.
- Confirm no LAN, hotspot, cellular, or public candidate can become selected.
- Cross-check the generated DTLS fingerprint against the value authenticated
  through SSH; tampering must fail before `server.hello`.
- Verify reliable ordered message transfer, size/backpressure behavior, and
  prompt teardown of WebRTC/UDP/process state when SSH closes.
- Build and run the Host implementation natively on ARM64 Termux and ARM64
  Linux/Spark.

## Scope and workspace safety

The architecture commit should include only the specification, context,
README, route map/handoff/research notes, and ADR-0002 through ADR-0007.
Unrelated untracked workspace material (`.local/`, `MISSION.md`,
`RESOURCES.md`, `assets/`, `lessons/`, `reference/`, and the control-character
filename) is not part of this handoff and must remain unstaged.
