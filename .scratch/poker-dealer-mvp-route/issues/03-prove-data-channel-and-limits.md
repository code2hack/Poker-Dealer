# Prove the Dealer↔Poker data channel and characterize its limits

Type: prototype
Status: open
Blocked by: 02

## Question

Using the exact official Fold6-side and RG-glasses-side samples, which transport adapter pair can exchange bidirectional private application frames, and what measured framing, ordering, acknowledgement, callback-threading, lifecycle, suspend/resume, and reconnect behavior must Poker–Dealer design around?

The answer is a transport decision backed by a minimal throwaway hello/ack prototype and measured limits, not the production transport implementation.

## Comments
