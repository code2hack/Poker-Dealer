# Prove the Dealer↔Poker data channel and characterize its limits

Type: prototype
Status: open
Blocked by: 11

## Question

Using the exact official Fold6-side CXR-M sample and the official inline CXR-S
integration snippets, can the guide-matched legacy pair exchange bidirectional
private application frames, and what measured framing, ordering,
acknowledgement, callback-threading, lifecycle, suspend/resume, and reconnect
behavior must Poker–Dealer design around?

The answer is a transport decision backed by a minimal throwaway hello/ack prototype and measured limits, not the production transport implementation.

## Comments

The Glass3 Enterprise demo is ruled out on the current firmware because its
required security system service is absent. The active probe uses
`client-m:1.1.0`, the exact CXR-S May-2025 AAR, and a shared custom-command
key. See [the legacy route evidence](../research/cxr-legacy-route.md).
