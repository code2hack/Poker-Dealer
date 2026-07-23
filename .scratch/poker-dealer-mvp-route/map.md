# Poker–Dealer Real-Hardware MVP Decision Route

## Destination

Resolve every remaining product, architecture, security, and hardware-dependent decision needed to move Poker–Dealer from M0 to the real-hardware MVP defined by `SPEC.md`, ending with an implementation-ready decision map rather than product implementation.

## Notes

- `SPEC.md` remains the normative product and domain contract. Existing decisions stay fixed unless hardware evidence makes a requirement infeasible; any product-level deviation then requires an explicit specification amendment.
- Wayfinding is planning by default. Research, diagnostic tasks, and throwaway prototypes may execute only to resolve a decision. State-changing hardware actions require explicit approval; proprietary artifacts, credentials, and device identifiers must not be committed.
- The Samsung Fold6 (`SM-F956N`, Android 16/API 36) is both the production Dealer phone and the local Termux/tmux bridge host for MVP acceptance.
- The Poker device is Rokid `RG-glasses` running Android 12/API 32, firmware `1.22.009-20260710-150201`, and CXR service `1.135`. Its HUD is 480×640; a microphone is declared; Rokid button/touch events are visible through Linux input devices.
- Normal production communication must use the supported Rokid transport. USB ADB is a development and diagnostics path only.
- Consult the `domain-modeling` skill whenever terminology changes. Use the ticket-specific `research`, `prototype`, or `grilling` skill when resolving a ticket.

## Decisions so far

- [Choose the official Rokid SDK route for RG-glasses and Fold6](issues/01-choose-official-rokid-sdk-route.md) — Select the current Glass3 Enterprise phone/glasses SDK pair and vendor sample; legacy CXR-M is not an extra dependency.
- [Obtain and stage the official Rokid SDK artifacts](issues/02-obtain-and-stage-rokid-sdk-artifacts.md) — Public artifacts and the linked sample are staged locally; core routing has no supplied API key, while online speech and possible device-side developer authorization remain vendor/hardware concerns.

## Not yet specified

- End-to-end acceptance sequencing and remaining milestone order after the transport, audio, input, HUD, and two-host topology decisions are resolved.
- Any product-level specification amendment that becomes unavoidable if the documented Glass3 Enterprise pair cannot satisfy the required private transport, audio, lifecycle, or input semantics on this firmware.

## Out of scope

- Implementing M1–M9 product deliverables; this map ends when those deliverables have no unresolved decisions blocking them.
- Adding non-tmux conversation connectors or a cloud backend.
- Reverse-engineering proprietary Bluetooth protocols or undocumented Rokid APIs.
- Replacing full text with generated summaries.
