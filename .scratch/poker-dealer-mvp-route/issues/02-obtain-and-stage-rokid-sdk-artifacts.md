# Obtain and stage the official Rokid SDK artifacts

Type: task
Status: resolved
Blocked by: 01
Route status: superseded on 2026-07-23

## Question

Stage the vendor-linked `glass3sdkdemo` source plus the documented `com.rokid.security:phone.sdk:2.2.0-E` and `com.rokid.security:glass3.open.sdk:2.2.0-E` artifacts in protected local paths. Determine whether the sample requires application identifiers, online credentials, device binding, or additional vendor authorization on this Fold6/RG-glasses pair, and record artifact versions, redacted locations, and licensing constraints for downstream capability decisions.

## Comments

## Answer

[The Glass3 Enterprise sample and SDK artifacts are staged in protected,
git-ignored local paths](../assets/rokid-sdk-staging.md). Public source and
Maven downloads require no credentials. The sample uses matched `GlassSample`
client IDs for routing and supplies no credential for the core transport;
Rokid AK/SK is explicitly required only for online speech services. No
serial-based binding was found. Device-side developer authorization is
undocumented and must be tested by the downstream hardware probe. Maven POMs
label the selected AARs Apache-2.0, but the sample and AARs contain no bundled
license grant or notice, so redistribution remains vendor-dependent.

The staging result remains valid historical evidence, but this is no longer
the active device route. The real glasses lack the Enterprise security system
service required by that SDK. Active legacy staging continues in
[issue 11](11-stage-and-authorize-legacy-cxr-probe.md).
