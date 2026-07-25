# Prove the Dealer↔Poker data channel and characterize its limits

Type: prototype
Status: resolved
Route status: superseded by the Android hotspot prototype

## Question

Using the exact official Fold6-side CXR-M sample and the official inline CXR-S
integration snippets, can the guide-matched legacy pair exchange bidirectional
private application frames, and what measured framing, ordering,
acknowledgement, callback-threading, lifecycle, suspend/resume, and reconnect
behavior must Poker–Dealer design around?

The answer is a transport decision backed by a minimal throwaway hello/ack prototype and measured limits, not the production transport implementation.

## Comments

The Glass3 Enterprise demo was ruled out on the current firmware because its
required security system service is absent. The superseded planned probe used
`client-m:1.1.0`, the exact CXR-S May-2025 AAR, and a shared custom-command
key. See [the legacy route evidence](../research/cxr-legacy-route.md).

## Answer

The CXR probe will not run. The user selected the Android-only path after
real-hardware prototype commit `9d36ed1` proved bidirectional hotspot TCP,
load, and reconnect. It also recorded bounded runs of more than 60 seconds of
glasses sleep and 303 seconds of Fold6 locked-screen active traffic; these are
not long-idle guarantees. `SPEC.md` revision 2 makes Fold6/Dealer client →
RG-glasses/Poker listener the normative topology and requires production
authentication plus replay/idempotency.
