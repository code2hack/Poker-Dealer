---
status: accepted
last_amended: 2026-08-01
---

# Use Android hotspot transport without CXR

Poker–Dealer uses an authenticated Android TLS/TCP connection initiated by
Dealer on the Fold6 hotspot host toward a Poker listener on the tethered
RG-glasses. Real-hardware evidence showed that this direction works
bidirectionally while the Samsung hotspot blocks the reverse connection
direction; it also removed the credentials, compatibility, and lifecycle
dependency of CXR-M/CXR-S/CXR-L. The throwaway evidence is preserved on
`prototype/android-hotspot-transport` at commit `9d36ed1`.

Poker accepts enrollment only during an explicit five-minute pairing window
and trusts one Dealer installation at a time. Poker displays its current
hotspot endpoint and a single-use code; Dealer enters them and initiates the
connection. Successful pairing exchanges and pins both installations' public
keys, discards the code, and uses mutually authenticated TLS for later
connections. Trusting a different Dealer requires explicit replacement.
Dealer may update Poker's mutable hotspot endpoint without pairing again, but
the connection must authenticate as the already pinned Poker identity; an
identity mismatch fails closed.

After each connection, Poker restart, or detected sequence gap, Dealer sends
a fresh authoritative synchronization snapshot from its retained projection,
then sends idempotent revisioned live updates. Poker–Dealer does not maintain
a separate durable transport replay log. Each snapshot contains the complete
bounded retained card content for every attached thread; Poker does not use a
lazy card-fetch protocol. Poker stages each identified snapshot, verifies all
declared chunks, and replaces its visible synchronized state only when the
snapshot is complete. A partial snapshot is discarded after connection loss.
Poker keeps synchronized conversation content only in process memory; after
Poker restarts it shows no cached content until a fresh snapshot completes.
Only the pairing identity persists on Poker.

During live synchronization, growth of an open text card uses revisioned,
UTF-8-offset-checked append chunks. Completion sends one authoritative final
card revision. A revision or offset mismatch discards the partial update and
requests a fresh synchronization snapshot rather than guessing or replaying.

## Consequences

- CXR and the Rokid companion data channel are not production fallbacks.
- Dealer's TCP-client role does not change its application authority.
- Private keys must remain in Android Keystore; pairing codes are temporary
  bootstrap material rather than reusable credentials.
- Production must add authoritative snapshot recovery, idempotent revisioned
  updates, and process-death resynchronization beyond the raw prototype.
- ADB remains installation and diagnostic control only, never a data path.
