---
status: superseded by ADR-0005
---

# Pin Bridge identity to its public key

Dealer identifies a Poker–Dealer Bridge by the SHA-256 fingerprint of its
long-lived ECDSA P-256 SubjectPublicKeyInfo rather than by the full certificate.
This lets the Bridge reissue certificate metadata or validity while retaining
identity, while private-key replacement remains an explicit identity change
that requires re-pairing. Full-certificate identity was rejected because it
would couple trust to renewable certificate details; pairing IDs and secrets
remain separately rotatable authorization credentials.

The long-lived key is held by a private self-signed root identity certificate,
which signs a separate endpoint leaf certificate. Dealer imports that root as
the only trust anchor for the Bridge and still performs ordinary chain and
hostname validation against the leaf's configured SANs. A directly
self-signed leaf or custom pin-only trust manager was rejected because either
couples trust to renewable leaf metadata or replaces standard TLS validation
with bespoke security code.
