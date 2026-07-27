---
status: superseded by ADR-0005
---

# Pair one Dealer per bridge

Each Poker–Dealer Bridge accepts exactly one active Dealer pairing in v1,
matching the product's single authoritative Fold6 companion and making
authorization and revocation unambiguous. Re-pairing rotates the pairing ID and
256-bit secret while preserving the Bridge's TLS identity; multiple concurrent
Dealer identities were rejected as out-of-scope complexity that would require
independent permissions, revocation, and mutation-ownership semantics.

Pairing is initiated only by a local `poker-dealer-bridge pair` command, which
emits a single Dealer import bundle to the invoking terminal. It will not
reveal an existing secret; `pair --rotate` invalidates the old pairing and
issues a replacement. A remotely reachable enrollment endpoint was rejected
because its convenience did not justify adding an unauthenticated attack
surface.

Connection authentication runs in the first bounded WebSocket protocol frames
after TLS and upgrade, before any product frame is accepted or emitted.
Putting pairing material in the upgrade URL or headers was rejected to keep it
out of HTTP diagnostics and to make the challenge-response state machine
explicitly testable.

The state machine is `server.hello`, `client.hello`, `auth.challenge`,
`auth.response`, then `auth.result`. Its HMAC transcript binds the Bridge
identity, selected protocol version, pairing and connection IDs, fresh nonces
from both peers, and a Bridge-timed 30-second challenge window. Using the
Bridge's monotonic time rather than requiring synchronized Dealer wall time
preserves replay resistance without making phone clock skew an authentication
failure.
