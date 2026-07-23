# Official legacy CXR-S ↔ CXR-M route

Research date: 2026-07-23

Scope: first-party Rokid documentation, Rokid's public Maven/Nexus repository,
and the vendor-distributed sample archive. No device identifier, credential, or
authorization-file content is recorded here.

## Finding

For the selected legacy route, **CXR-M is required on the Fold6** and CXR-S is
the bridge library used by the Poker app on the glasses. The only
documentation-and-sample pair that Rokid currently publishes as a coherent
recipe is:

| Side | Guide-pinned artifact | Evidence |
| --- | --- | --- |
| Fold6 / Dealer | `com.rokid.cxr:client-m:1.1.0` | [Rokid CXR-M SDK import guide](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=bc6df927163347008fd7b78812cf2082) |
| RG-glasses / Poker | `com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45` | [Rokid CXR-S SDK integration guide](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=12e8f5f375e54ae8aa499472cf084755) |

The CXR-S value is a Maven *unique snapshot build*: its POM identifies the
logical version as `1.0-SNAPSHOT`, and the exact documented AAR remains
anonymously downloadable under the snapshot directory:
[POM](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/cxr-service-bridge/1.0-SNAPSHOT/cxr-service-bridge-1.0-20250519.061355-45.pom),
[AAR](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/cxr-service-bridge/1.0-SNAPSHOT/cxr-service-bridge-1.0-20250519.061355-45.aar).
The ordinary Maven version-directory URL for
`1.0-20250519.061355-45` returns 404, so the guide's timestamp string is not a
currently resolvable Gradle coordinate. For a reproducible probe, download the
exact AAR from the unique-snapshot URL, verify SHA-256
`962481281548f0bfbbf7fec24dfb1b9be2ab3773f8adb9f6f643e0bfb5d587cd`,
and use it as a checksum-pinned local file dependency. Do not resolve the
moving `1.0-SNAPSHOT`, which now points to a materially newer build.

Rokid's current metadata also advertises `client-m:1.2.2` and
`cxr-service-bridge:1.0` as releases. However, no first-party compatibility
matrix, paired sample, or migration note found in the live guide says that
those two releases form the supported pair for this firmware. Moreover, the
current CXR-S `1.0` AAR has API changes relative to the documented snapshot
(including connection-callback and ARTC-send signatures). Do **not** silently
substitute either current release in the initial proof:
[CXR-M metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-m/maven-metadata.xml),
[CXR-S metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/cxr-service-bridge/maven-metadata.xml),
[current CXR-S AAR](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/cxr-service-bridge/1.0/cxr-service-bridge-1.0.aar).

## Where the SDKs and samples come from

Both AARs are anonymously readable from:

```text
https://maven.rokid.com/repository/maven-public/
```

The guide-matched `client-m:1.1.0` AAR has SHA-256
`032786b5817e273d2deefdb9e55a961da04e087810a22aa703415b614ab54475`.
For this project, the two AARs and original sample archive are staged under
the protected, Git-ignored `.local/rokid/cxr-maven/` tree.

The Fold6 sample is **`CXRMSamples`**, distributed by Rokid as
[`CXRMSamples_110.zip`](https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR/1.1.0/CXRMSamples_110.zip).
The live quick-start explicitly names that archive and version:
[CXR-M quick start](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=c3315f385e2a42948cb9c5a81d2c33c7).
The archive inspected on 2026-07-23:

- declares application ID `com.rokid.cxrmsamples`;
- declares version `1.1.0`, `minSdk 31`, and `compileSdk`/`targetSdk 36`;
- depends on `com.rokid.cxr:client-m:1.1.0`;
- has SHA-256
  `13d098ac9a73d38a9eda9cc7eb5167e0b961eca113b2a5cce29fdc1cc130ee11`.

The glasses documentation calls its Gradle root project
**`CXRServiceDemo`**, but the live first-party guide does not link a
downloadable glasses sample or public source repository. It supplies the
integration and API snippets inline instead:
[CXR-S integration](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=12e8f5f375e54ae8aa499472cf084755),
[subscriptions](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=087795f9923b4f219584c587a7b5d86a),
and [sending](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=2745b4ceff3645de94339961bff07622).
Therefore a minimal local glasses probe must be assembled from those official
snippets unless Rokid support supplies the missing project.

Artifact and sample downloads are public; **runtime authorization is not**.

## Initialization, identity, and authorization

### Fold6 / CXR-M

Rokid requires the developer to:

1. register or log in at `https://ar.rokid.com`;
2. complete real-name and developer verification;
3. obtain the developer credential;
4. bind the glasses serial in Device Management; and
5. download the resulting `.lc` authorization file.

The guide states that a personal developer can bind at most ten devices of the
same type. These are account- and device-gated operations even though the AAR
and sample ZIP are public:
[authorization and SDK import](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=bc6df927163347008fd7b78812cf2082).

The documented connection sequence is:

1. scan for a glasses device while it is in pairing mode;
2. call `CxrApi.getInstance().initBluetooth(context, device, callback)`;
3. retain the returned socket UUID and MAC address locally; and
4. call
   `connectBluetooth(context, uuid, mac, callback, snLcBytes, clientSecret)`.

The callback can report `SN_CHECK_FAILED`; thus the `.lc` bytes and developer
secret are enforced during the connection, not merely during download:
[Rokid Bluetooth connection guide](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=02f35584888b47948193aa369d6748c2).

No application ID or matched phone/glasses client ID is documented as an auth
input. `com.rokid.cxrmsamples` is only the sample package ID. Do not use values
bundled in the sample archive as production authorization; the official quick
start says to replace its secret and raw authorization resource with the
developer's own values. Keep both outside Git and inject them through protected
local configuration.

Rokid also states that CXR-M fills the same companion role as the Rokid AI app
and the two cannot be used simultaneously on one phone. When switching from
the Rokid AI app, the glasses must be put back into discoverable/pairing mode:
[CXR-M introduction](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=91ac0a79cf7242f3b86a67c805c6fcfb),
[Bluetooth procedure](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=02f35584888b47948193aa369d6748c2).

### RG-glasses / CXR-S

The glasses app constructs `CXRServiceBridge`; its constructor initializes the
native bridge. It may then:

- attach a `StatusListener`;
- subscribe to a message-key string with either `MsgCallback` or
  `MsgReplyCallback`; and
- send a message-key string plus `Caps` and optional bytes.

There is no documented glasses-side secret, application registration, client
ID, or matching package ID. The two apps must instead agree on message-key
names and `Caps` schemas. On CXR-M, `setCustomCmdListener` receives
glasses-originated keys and `sendCustomCmd(key, caps)` sends toward a CXR-S
subscription:
[CXR-M custom commands](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=f0f3a70c56ef4f65b69baf71663ca1b7),
[CXR-S subscriptions](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=087795f9923b4f219584c587a7b5d86a),
[CXR-S sends](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=2745b4ceff3645de94339961bff07622).

## Compatibility with this hardware

The static compatibility surface is favorable:

- the glasses run Android 12 / API 32, while the documented CXR-S AAR declares
  `minSdk 28`, targets API 32, and contains `arm64-v8a` and `armeabi-v7a`
  native libraries;
- the Fold6 exceeds CXR-M's `minSdk 28` requirement (the official sample itself
  chooses `minSdk 31`);
- direct device inspection for this project reports the firmware-resident
  package `com.rokid.cxrservice`: CXR runtime version `1.135`, Android package
  versionName `12` / versionCode `32`; and
- the official CXR-S setup procedure asks developers to enable ADB through the
  Rokid AI app and install their app, but does not instruct them to install or
  replace a system service:
  [CXR-S development environment](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html?documentId=98a831b635c1446ebbecfe42ee28c007).

Accordingly, **no system APK or firmware change is part of the official legacy
route**, and none should be made for the first probe. The missing Enterprise
`com.rokid.security.system.server` service is irrelevant to CXR-S/CXR-M.
Preserve the installed `com.rokid.cxrservice`; install only the two application
APKs.

Read-only device inspection also shows that the exported Android service's
`onBind` returns `null`. The AAR's native bridge instead reaches the running
system service through Flora on the abstract Unix socket `@cxr-service`.
Application code must construct one long-lived `CXRServiceBridge`; it must not
try to bind the system component directly. The public bridge has no close or
unsubscribe API, and duplicate topic subscriptions return `-2`, so the first
probe should create one process-long instance and register each key once.

This is not yet proof of runtime compatibility. No public Rokid source found
maps firmware `1.22.009-20260710-150201` or CXR service `1.135` to a particular
CXR-M/CXR-S artifact pair. Authentication and one bidirectional
`hello`/`ack` exchange on the real devices remain mandatory acceptance tests.

## Local compile-only probes

A minimal, explicitly throwaway CXR-S glasses app was assembled from the
official status, subscription, and send snippets under the protected
`.local/rokid/cxr-probe/glasses/` tree. It uses the exact downloaded AAR as a
local file dependency, one process-long bridge, and the shared key
`poker_dealer_probe`.

The Android build completed all 34 tasks successfully. The resulting debug APK:

- is package `com.pokerdealer.cxrprobe`, version `0.1-prototype`;
- has `minSdk 28`, targets/compiles against API 34;
- contains both `arm64-v8a` and `armeabi-v7a`, including the archived
  `libcxr-sock-rfcomm-jni.so`;
- passes `apksigner` verification; and
- has SHA-256
  `f0e1bee3e1379723cb0dbef4c0f3723d3781e1e967925d1c185122e4e734763f`.

This proves source/toolchain packaging only. The APK was not installed, and it
does not prove system-service or phone interoperability.

A matching throwaway Fold6 app was then compiled against the normal
`client-m:1.1.0` Maven coordinate. Its custom-command listener and sender use
the same `poker_dealer_probe` key, but it deliberately contains no Bluetooth
credential, device identifier, or `.lc` bytes. Its 34-task build also
succeeded; the v2-signed debug APK is package
`com.pokerdealer.cxrmprobe`, contains both documented ARM ABIs, and has
SHA-256
`a19e9394e7ff6880357fa9a9539fe5339f13144ef7a7c6d1c36ca4d1770d7536`.
It was not installed.

The separately extracted official phone sample working copy was sanitized by
removing its bundled demo authorization files and replacing its embedded
secret/resource selection with an explicit unconfigured placeholder. The
original downloaded ZIP remains unchanged in the protected staging tree, so
the removal is recoverable. Neither the unconfigured sample nor the
compile-only phone probe can authenticate.

## Uncertainties requiring either a probe or Rokid support

- Whether CXR service `1.135` is guaranteed compatible with the documented
  May-2025 CXR-S unique snapshot and `client-m:1.1.0`.
- Which CXR-S build officially pairs with `client-m:1.2.2`.
- Whether Rokid will provide the missing `CXRServiceDemo` source/archive.
- Release and redistribution terms for the proprietary AARs and sample; public
  readability alone is not a redistribution grant.

Until Rokid answers the version-matrix questions, the reproducible first probe
is the documented `client-m:1.1.0` plus exact CXR-S unique snapshot above.
