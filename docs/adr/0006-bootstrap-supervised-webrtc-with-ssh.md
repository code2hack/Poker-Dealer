---
status: accepted
---

# Bootstrap supervised WebRTC with restricted SSH

Dealer opens a restricted SSH exec channel to each Host, verifies the pinned
SSH host key, and authenticates with its standard product key. The forced
`poker-dealer-bridge rtc-bootstrap` process exchanges WebRTC signaling and
binds the negotiated WebRTC peer identity to that authenticated SSH session.
The SSH channel remains open for the Bridge session's lifetime as its
supervisor, signaling, health, and recovery path.

Bridge Protocol payloads use a reliable ordered WebRTC data channel, not SSH
stdin/stdout. Future media tracks may share the WebRTC peer connection, but
photos, audio, and video remain post-MVP scope. If the supervisory SSH channel
ends, its Bridge process and associated WebRTC session end; reconnection starts
with a new authenticated SSH bootstrap.

This supersedes ADR-0005's SSH-stdio data plane and ADR-0002's pure-JVM
SSH-client placement. It preserves OpenSSH host identity, the single standard
Dealer product key, manual restricted `authorized_keys` provisioning, and the
ban on shell, PTY, forwarding, and arbitrary commands. The costs are that M1
must now ship and test WebRTC implementations on Android and both Host
platforms, and that client module placement cannot be settled until those
implementations are selected.

ADR-0007 further constrains M1 ICE connectivity to the on-device Termux route
and the user-approved Tailscale route for Spark, without STUN/TURN.
