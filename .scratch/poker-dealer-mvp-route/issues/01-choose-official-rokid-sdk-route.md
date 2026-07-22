# Choose the official Rokid SDK route for RG-glasses and Fold6

Type: research
Status: resolved

## Question

From current first-party Rokid documentation and distributions, where and under what access terms can the exact CXR-S and CXR-L SDKs/samples for RG-glasses firmware `1.22.009` be obtained; what roles do CXR-S, CXR-L, and CXR-M play; and is CXR-M necessary for a direct private Dealer↔Poker custom data channel on the Fold6?

The answer must name the official download or access path, identify the artifacts/sample projects to request, distinguish public facts from access-gated facts, and recommend the supported mobile/glasses adapter pair without inventing API names.

## Comments

## Answer

[Rokid SDK route for the Fold6 and RG-glasses](../research/rokid-sdk-route.md) — Use the current public Glass3 Enterprise pair (`phone.sdk:2.2.0-E` on Fold6 and `glass3.open.sdk:2.2.0-E` on RG-glasses) and its two-ended `glass3sdkdemo`; do not add legacy CXR-M or CXR-L. CXR-M is required only if deliberately falling back to legacy CXR-S. Exact firmware `1.22.009` compatibility, CXR-L samples, and redistribution terms remain vendor-confirmation/probe items.
