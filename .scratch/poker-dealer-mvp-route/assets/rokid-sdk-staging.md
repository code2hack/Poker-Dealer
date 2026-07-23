# Rokid Glass3 Enterprise SDK staging record

Verified: 2026-07-23

## Protected local staging

- Vendor sample: `<repo>/.local/rokid/glass3sdkdemo`
- Artifact copies: `<repo>/.local/rokid/maven`
- Isolated Gradle cache: `<repo>/.local/rokid/gradle-cache`
- `.local/rokid/` is git-ignored. Staging directories are mode `0700`; manually
  downloaded artifacts are mode `0600`.
- No credentials, device identifiers, signing material, or proprietary project
  configuration were added to the repository.

The sample is a clean checkout of
`https://gitee.com/as_pixar/glass3sdkdemo.git`, branch `main`, at commit
`20af0e445894e944a64437a5d3ce0d08b09f5a66` (2026-07-07). It contains two
independent Android builds:

| Side | Sample directory | Android application ID | SDK coordinate |
| --- | --- | --- | --- |
| Fold6 / phone | `glass3sdkphonedemo` | `com.rokid.phone` | `com.rokid.security:phone.sdk:2.2.0-E` |
| RG-glasses | `glassdemo` | `com.rokid.glesse` | `com.rokid.security:glass3.open.sdk:2.2.0-E` |

These are sample application IDs, not evidence of registered production IDs.
Neither build declares a signing configuration.

## Artifact inventory

The selected AARs and POMs are anonymously downloadable from
`https://maven.rokid.com/repository/maven-public/`. The following AARs were
staged:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `phone.sdk:2.2.0-E` | 1,394 | `ca915284398e61775fe2164253727bc46b3d5f3edd2ecf5096519b46225bbc31` |
| `glass3.open.sdk:2.2.0-E` | 339,753 | `55f9a3fbcba8bdaffc75e4831193a0ada895534739708c3f46371fbc3156b31d` |
| `phone.sdk.server:2.2.0-E` | 995,366 | `7f4c8f19cf0db40c5c9c18141548635015d41a10daa5e56b32bd30126e326da8` |
| `phone.sdk.api:2.2.0-E` | 96,597 | `eeb8b145eaa0ee4de10d9030afbc6bfc7388393223f4d59a5d4ab2a3478c3699` |
| `sdk.base.common:2.2.0-E` | 373,952 | `d036fa7e7e69d19964c293a0c79a5d3be7696d52c0c111935931528cdbd7bc6c` |
| `glass3.sdk.base.data:2.2.0-E` | 101,067 | `61f219a20636035eca2cfa0e18e92c0d3239dac712a0378cdd5244e93ffa1514` |

`phone.sdk` is a thin wrapper whose POM delegates to `phone.sdk.server` and
`phone.sdk.api`. The manually copied set is an integrity snapshot of the
selected coordinates and their immediate shared components, not a complete
offline Maven repository. An online Gradle build must resolve the remaining
Rokid and third-party transitive graph.

## Identity, credentials, and device association

- Repository and artifact download: no authentication is configured or needed.
- Core app-to-app route: the phone registers `GlassSample`; the glasses bind the
  preinstalled `com.rokid.security.system.server` service and register the same
  client ID. This matched client ID is routing configuration, not a secret.
- Online services: the phone sample passes `UserAuthInfo("", "")`. Its source
  explicitly says online STT/TTS needs an access key and secret key obtained
  from Rokid business support. Translation is disabled in the sample.
- A hard-coded demo `SDK_SERVER_ID` exists but has no call site. It is treated
  as dead/legacy sample data, not a credential and not a value to reuse.
- Device association: the demonstrated path is Classic Bluetooth pairing,
  followed by Wi-Fi P2P. The sample persists Bluetooth/P2P connection data and
  can read the glasses serial after connection, but it does not use that serial
  as an authentication or whitelist credential.
- No login, developer-portal token, manifest API key, certificate pin,
  device-serial whitelist, or account-binding flow appears in the sample.
- The glasses source can report that a system service or “developer-version
  authorization” is missing. The public sample does not document provisioning
  or establish whether the current RG-glasses firmware is already authorized.
  Runtime initialization with blank `UserAuthInfo` therefore remains a
  hardware-probe question.

## Licensing and redistribution constraint

The two selected Maven POMs identify their artifacts as Apache License 2.0, but
the AARs contain no bundled license/notice file. The vendor sample repository
also contains no `LICENSE`, `COPYING`, or `NOTICE`. Do not commit or redistribute
the staged sample or binaries based only on this metadata; obtain explicit
vendor terms before shipping them, and review every transitive dependency's
license separately. Local protected use is the only assumption made here.

## Build verification

The Android SDK license was accepted and Android command-line tools 22.0,
Platform API 34, and Build Tools 34.0.0 were installed under
`<home>/android-sdk`. SDK `aapt`, `aapt2`, `aidl`, `zipalign`, and `adb` were
adapted to native Termux AArch64 executables; their original x86-64 executables
remain beside them with `.x86_64` suffixes.

Both official Gradle 8.6 projects then completed debug builds:

| Side | Result | Protected output | Bytes | SHA-256 |
| --- | --- | --- | ---: | --- |
| RG-glasses | 38 tasks, 3m52s | `<repo>/.local/rokid/glass3sdkdemo/glassdemo/app/build/outputs/apk/debug/app-debug.apk` | 47,867,026 | `cca6b4273eefa6edd40a5922d023c264bbac388b283b98338d3efea51d393529` |
| Fold6 / phone | 38 tasks, 6m35s | `<repo>/.local/rokid/glass3sdkdemo/glass3sdkphonedemo/app/build/outputs/apk/debug/app-debug.apk` | 307,698,039 | `313e331dcbbf4ef3b0bce96d3d434034ceaf67c1ec56b1c4b690850605efffe5` |

Local verification confirmed package IDs `com.rokid.glesse` and
`com.rokid.phone`. Both APKs pass `apksigner verify` with APK Signature Scheme
v2 and the same local Android debug certificate. The binaries remain
permission-restricted and git-ignored.

No APK has been installed. A USB session briefly identified the RG-glasses
after the builds but disconnected before package inspection; the previously
recorded hotspot endpoint accepts TCP connections but currently remains
`offline` to ADB. No device state was changed.
