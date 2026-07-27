# Poker–Dealer Real-Hardware MVP Decision Route

## Destination

Resolve every remaining product, architecture, security, and hardware-dependent decision needed to move Poker–Dealer from M0 to the real-hardware MVP defined by `SPEC.md`, ending with an implementation-ready decision map rather than product implementation.

## Notes

- `SPEC.md` remains the normative product and domain contract. Existing decisions stay fixed unless hardware evidence makes a requirement infeasible; any product-level deviation then requires an explicit specification amendment.
- Wayfinding is planning by default. Research, diagnostic tasks, and throwaway prototypes may execute only to resolve a decision. State-changing hardware actions require explicit approval; proprietary artifacts, credentials, and device identifiers must not be committed.
- The Samsung Fold6 (`SM-F956N`, Android 16/API 36) is both the production Dealer phone and the local Termux/tmux bridge host for MVP acceptance.
- The Poker device is Rokid `RG-glasses` running Android 12/API 32, firmware `1.22.009-20260710-150201`, and CXR service `1.135`. Its HUD is 480×640; a microphone is declared; button/touch events are visible through Linux input devices. The installed CXR service remains untouched but is not a Poker–Dealer dependency.
- Normal production communication uses authenticated Android TLS/TCP over the Fold6 hotspot. Dealer on the hotspot host initiates the connection to the Poker listener on the glasses. USB/Wi-Fi ADB is a development and diagnostics path only.
- Consult the `domain-modeling` skill whenever terminology changes. Use the ticket-specific `research`, `prototype`, or `grilling` skill when resolving a ticket.

## Decisions so far

- [Validate the Android-only hotspot route](https://github.com/code2hack/Poker-Dealer/blob/prototype/android-hotspot-transport/prototypes/android-hotspot/RESULTS.md) — Real-hardware evidence fixes the topology as Fold6/Dealer TCP client → RG-glasses/Poker TCP listener. Bidirectional traffic, load, and reconnect passed; bounded runs covered more than 60 seconds of glasses sleep and 303 seconds of Fold6 locked-screen active traffic. Process restart proved production replay/idempotency is required, and long unattended idle remains open.
- [Choose the official Rokid SDK route for RG-glasses and Fold6](issues/01-choose-official-rokid-sdk-route.md) — Superseded historical research. CXR-M/CXR-S/CXR-L are not production transport dependencies.
- [Obtain and stage the official Rokid SDK artifacts](issues/02-obtain-and-stage-rokid-sdk-artifacts.md) — The Enterprise artifacts remain staged as historical evidence, but the route is rejected on this firmware because its required security system service is absent.

## Active next step

- Resume implementation at `SPEC.md` M1 and follow the normative milestone order
  through M9. The hotspot prototype resolved the transport direction but does
  not permit skipping the bridge, Dealer core, or loopback Poker foundations
  required before production M5 transport hardening.
- Implement M1 next: restricted SSH bootstrap and supervision, the reliable
  ordered WebRTC Bridge data channel, tmux discovery, topology events, and the
  control-mode parser.
- Keep the throwaway prototype on `prototype/android-hotspot-transport`; lift only validated decisions and production interfaces into main.

## M1 implementation decisions

- M1's secure pane-discovery acceptance client will use the production Kotlin
  SSH and WebRTC implementations intended for reuse by Dealer. Acceptance
  tests exercise those implementations against the Rust bridge; Android UI,
  Room persistence, and the foreground connection service remain M3 work. A
  disposable client that validates only the Rust side is insufficient.
- `shared:bridge-client` owns the reusable Bridge session state and
  platform-facing interfaces. Bridge wire DTOs, validation, and codecs remain
  in `shared:protocol`. The final Android/platform module boundary remains
  open until the M1 WebRTC implementation is selected; ADR-0006 supersedes the
  earlier pure-JVM placement decision.
- Bridge transport is one compound supervised session. Dealer pins the
  OpenSSH Host key, authenticates with its single standard product SSH key
  reused across Hosts, and opens an exec channel whose authorized-key entry
  uses `restrict` plus a forced absolute
  `poker-dealer-bridge rtc-bootstrap` command. That channel exchanges WebRTC
  signaling and remains open as the session supervisor.
- Dealer does not import or use the user's general-purpose shell-login key.
- In M1, Dealer displays or exports that public key and fingerprint. The user
  manually installs the exact restricted entry in each Host's
  `authorized_keys`; Poker–Dealer may show a template but never edits
  `authorized_keys` or `sshd_config`.
- The Dealer key cannot open a shell or PTY, forward ports, use an agent/X11,
  run user RC files, tunnel, or select another command. Bridge protocol frames
  use one reliable ordered WebRTC data channel with one UTF-8 JSON envelope per
  data-channel message. The SSH channel carries only bounded signaling,
  supervision, health, and recovery messages; human diagnostics use stderr.
- `poker-dealer-bridge` may open only the WebRTC sockets owned by its
  SSH-supervised session. It owns no separately persistent product daemon,
  custom WSS listener, TLS CA, HMAC pairing secret, or WebSocket
  authentication state machine. ADR-0006 supersedes the earlier SSH-stdio and
  custom-listener decisions.
- M1 configures no STUN/TURN. Termux WebRTC stays on-device and Spark WebRTC
  stays on its approved Tailscale route. Tailscale may internally use a
  direct, peer-relay, or DERP path, but Poker–Dealer never silently widens to
  an unrelated LAN, cellular, or public route. A Fold6 route probe is required
  before the selected WebRTC implementation is accepted.
- Mosh is not a Bridge transport fallback because its interactive
  latest-screen synchronization is not a lossless application channel.
- Human-editable `bridge.toml` contains only tmux configuration. Any durable
  request-dedupe state uses an owner-only private user-data directory. Tests
  may relocate both through one explicit root override, never an implicit
  working-directory fallback.
- Successful Host authorization permits discovery of same-user default and
  named sockets in tmux's standard per-user directory. Only custom `tmux -S`
  paths require local `bridge.toml` entries; Dealer cannot submit executables
  or socket paths, and the Bridge never scans the general filesystem.
- `tmuxServerId` is the durable socket-locator identity, while
  `tmuxServerInstanceId` identifies one tmux process lifetime using the
  canonical locator plus reported UID, PID, and server start time. Pane
  locators carry both; an instance change makes every old pane attachment stale
  before replacement panes are published.
- Tmux topology is snapshot-authoritative. Control-mode lifecycle
  notifications trigger a short debounced full re-read of the affected server;
  the Bridge diffs snapshots into ordered changes and also reconciles
  periodically and after reconnect/recovery.
- Each reconciliation emits one atomic `host.delta` batch with matching
  `baseRevision` and new `revision`, including all upserts, removals, and
  instance invalidations. Dealer never partially applies a batch and requests
  a snapshot on a gap or base mismatch.

## Not yet specified

- Whether the `Termux (local)` profile may use a tightly scoped loopback ICE
  candidate exception despite RFC 8445. The current recommendation is yes,
  but it is not accepted until explicitly approved.
- The exact Android and Rust WebRTC implementations, Bridge-client module
  seam, Android AAR provenance, Android SSH library, and SSH host-key
  enrollment UX. Current implementation research is preserved in
  `research/m1-webrtc-implementation-options.md`.
- Endpoint discovery/pairing UX, DHCP address-change recovery, and long unattended hotspot idle behavior.
- Public-Android microphone/ASR and input-event capabilities on the current glasses firmware.
- Post-MVP live photo/audio/video ingestion for Host-side AI agents. Its
  viability research is recorded in
  `research/media-agent-transport-viability.md`. M1 implements only the
  WebRTC data plane required for Bridge Protocol; no media tracks, capture
  pipeline, codecs, or agent-media integration are added to M1–M9.

## Out of scope

- Implementing M1–M9 product deliverables; this map ends when those deliverables have no unresolved decisions blocking them.
- Adding non-tmux conversation connectors or a cloud backend.
- Reverse-engineering proprietary Bluetooth protocols or undocumented Rokid APIs.
- Replacing full text with generated summaries.
