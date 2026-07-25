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

- Implement `SPEC.md` M5: authenticated TLS/TCP, endpoint pairing/configuration, persistent outbox/replay, dedupe, lifecycle recovery, and the extended hotspot power-policy matrix.
- Keep the throwaway prototype on `prototype/android-hotspot-transport`; lift only validated decisions and production interfaces into main.

## Not yet specified

- Endpoint discovery/pairing UX, DHCP address-change recovery, and long unattended hotspot idle behavior.
- Public-Android microphone/ASR and input-event capabilities on the current glasses firmware.

## Out of scope

- Implementing M1–M9 product deliverables; this map ends when those deliverables have no unresolved decisions blocking them.
- Adding non-tmux conversation connectors or a cloud backend.
- Reverse-engineering proprietary Bluetooth protocols or undocumented Rokid APIs.
- Replacing full text with generated summaries.
