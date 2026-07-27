---
status: accepted
---

# Constrain M1 WebRTC to on-device and Tailscale routes

M1 configures no WebRTC STUN or TURN servers. The Bridge data plane may use
only the already approved private route for its selected Host:

- Termux uses an on-device route that does not depend on the LAN, hotspot, or
  public network.
- DGX Spark uses its user-approved Tailscale address and route.

Poker–Dealer does not silently widen candidate gathering or connectivity to an
unapproved LAN, cellular, or public route. Tailscale may internally select a
direct connection, peer relay, or DERP relay; that remains below the WebRTC
boundary and does not constitute a Poker–Dealer TURN service.

M1 must prove both routes on the production Fold6 before its WebRTC
implementation is accepted. The probe must show which Android adapters and ICE
candidates are gathered, which candidate pair is selected, and that Termux and
Spark data-channel traffic succeeds without configured STUN/TURN. Failure is a
visible M1 implementation blocker and reopens the implementation choice; it
must not activate an unapproved fallback.

This keeps M1 independent of public ICE infrastructure, limits address
exposure, and matches the two fixed Host topologies. The cost is that general
Internet WebRTC connectivity is unsupported, and Android loopback/VPN adapter
behavior remains a real-hardware acceptance concern rather than an assumed
library capability.
