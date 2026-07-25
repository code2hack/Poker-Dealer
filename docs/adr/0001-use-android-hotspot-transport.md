---
status: accepted
---

# Use Android hotspot transport without CXR

Poker–Dealer uses an authenticated Android TLS/TCP connection initiated by
Dealer on the Fold6 hotspot host toward a Poker listener on the tethered
RG-glasses. Real-hardware evidence showed that this direction works
bidirectionally while the Samsung hotspot blocks the reverse connection
direction; it also removed the credentials, compatibility, and lifecycle
dependency of CXR-M/CXR-S/CXR-L. The throwaway evidence is preserved on
`prototype/android-hotspot-transport` at commit `9d36ed1`.

## Consequences

- CXR and the Rokid companion data channel are not production fallbacks.
- Dealer's TCP-client role does not change its application authority.
- Production must add TLS pairing, endpoint identity, durable replay,
  idempotency, and process-death resynchronization beyond the raw prototype.
- ADB remains installation and diagnostic control only, never a data path.
