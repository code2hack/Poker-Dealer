# Choose the official Rokid SDK route for RG-glasses and Fold6

Type: research
Status: resolved
Route status: superseded on 2026-07-25 by the Android hotspot decision

## Question

From current first-party Rokid documentation and distributions, where and under what access terms can the exact CXR-S and CXR-L SDKs/samples for RG-glasses firmware `1.22.009` be obtained; what roles do CXR-S, CXR-L, and CXR-M play; and is CXR-M necessary for a direct private Dealer↔Poker custom data channel on the Fold6?

The answer must name the official download or access path, identify the artifacts/sample projects to request, distinguish public facts from access-gated facts, and recommend the supported mobile/glasses adapter pair without inventing API names.

## Comments

## Answer

Historical answer only. The normative route is now the Android-only
Fold6/Dealer client → RG-glasses/Poker listener topology in `SPEC.md` revision
2, backed by prototype commit `9d36ed1`. No CXR artifact is a production
dependency.

[Official legacy CXR-S ↔ CXR-M route](../research/cxr-legacy-route.md) —
At that time, the guide-matched legacy pair selected for a planned
real-hardware probe was
`client-m:1.1.0` on the Fold6 and the checksum-pinned CXR-S unique-snapshot
AAR `1.0-20250519.061355-45` on the glasses. **CXR-M is required** for this
historical route; CXR-L was not part of its direct Android phone↔glasses
channel. The firmware-resident `com.rokid.cxrservice` was left unchanged.

This supersedes the original
[public-document recommendation](../research/rokid-sdk-route.md) to use the
Glass3 Enterprise pair. Two deterministic runs of the official glasses demo
showed `bindSecurityService = false`; bytecode proved that SDK requires
`com.rokid.security.system.server.SecurityCoreService`, which is absent on this
firmware, while the legacy CXR system service is installed and running. The
user selected the no-firmware-change legacy option on 2026-07-23.

Runtime compatibility was never proven because Rokid publishes no matrix
for firmware `1.22.009`, CXR runtime `1.135`, and the SDK versions. The Fold6
connection would also have required a verified Rokid developer credential and
per-device `.lc` authorization file; no such authorization is now required or
requested for Poker–Dealer.
