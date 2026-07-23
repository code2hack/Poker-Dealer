# Choose the official Rokid SDK route for RG-glasses and Fold6

Type: research
Status: resolved

## Question

From current first-party Rokid documentation and distributions, where and under what access terms can the exact CXR-S and CXR-L SDKs/samples for RG-glasses firmware `1.22.009` be obtained; what roles do CXR-S, CXR-L, and CXR-M play; and is CXR-M necessary for a direct private Dealer↔Poker custom data channel on the Fold6?

The answer must name the official download or access path, identify the artifacts/sample projects to request, distinguish public facts from access-gated facts, and recommend the supported mobile/glasses adapter pair without inventing API names.

## Comments

## Answer

[Official legacy CXR-S ↔ CXR-M route](../research/cxr-legacy-route.md) —
Use the guide-matched legacy pair for the first real-hardware probe:
`client-m:1.1.0` on the Fold6 and the checksum-pinned CXR-S unique-snapshot
AAR `1.0-20250519.061355-45` on the glasses. **CXR-M is required** for this
route; CXR-L is not part of the direct Android phone↔glasses channel. Keep the
firmware-resident `com.rokid.cxrservice` and do not install or replace a system
APK.

This supersedes the original
[public-document recommendation](../research/rokid-sdk-route.md) to use the
Glass3 Enterprise pair. Two deterministic runs of the official glasses demo
showed `bindSecurityService = false`; bytecode proved that SDK requires
`com.rokid.security.system.server.SecurityCoreService`, which is absent on this
firmware, while the legacy CXR system service is installed and running. The
user selected the no-firmware-change legacy option on 2026-07-23.

Runtime compatibility is still probe-gated because Rokid publishes no matrix
for firmware `1.22.009`, CXR runtime `1.135`, and the SDK versions. The Fold6
connection also requires the user's verified Rokid developer credential and
per-device `.lc` authorization file.
