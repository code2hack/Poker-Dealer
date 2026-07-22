# Rokid SDK route for the Fold6 and RG-glasses

Research date: 2026-07-22. Sources are limited to Rokid-hosted documentation and distribution endpoints, plus a Rokid representative's public CXR-L announcement.

## Decision

Use Rokid's current **Glass3 Enterprise two-ended SDK**, not a mixture of the legacy CXR SDK families:

- Poker on RG-glasses: `com.rokid.security:glass3.open.sdk:2.2.0-E`
- Dealer on Fold6: `com.rokid.security:phone.sdk:2.2.0-E`
- Repository: `https://maven.rokid.com/repository/maven-public/`
- Vendor-linked sample: `https://gitee.com/as_pixar/glass3sdkdemo.git`, containing `glassdemo` (glasses) and `glass3sdkphonedemo` (phone)

The official current guide names those exact dependencies and repository. Its sample guide says the two apps are built separately, the phone scans/connects to `Rokid RG-glasses`, and the two sides can exchange messages and files after classic-Bluetooth connection. The SDK overview assigns connection/P2P/message responsibilities to the phone SDK and registration/message/hardware responsibilities to the glasses SDK. [Glass3 quick start](https://x-docs.rokid.com/docs/terminal-sdk/getting-started/%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B.html), [SDK overview](https://x-docs.rokid.com/docs/terminal-sdk/getting-started/%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.html), [demo guide](https://x-docs.rokid.com/docs/downloads/demo-guide.html)

Therefore **a separate CXR-M dependency is not necessary for the selected direct private Dealer↔Poker channel**. The current `phone.sdk` is already the mobile half of the supported pair. Do not add `client-m` or `client-l` alongside it unless a hardware spike proves the current pair incompatible and the route is deliberately changed.

## Verified public facts

### Current supported route and download steps

1. Clone the vendor-linked sample:

   ```sh
   git clone https://gitee.com/as_pixar/glass3sdkdemo.git
   ```

2. Add Rokid's public Maven repository to Gradle:

   ```groovy
   maven { url 'https://maven.rokid.com/repository/maven-public/' }
   ```

3. Add `com.rokid.security:phone.sdk:2.2.0-E` to the Fold6 app and `com.rokid.security:glass3.open.sdk:2.2.0-E` to the glasses app, following the exclusions and native-library conflict rule in the official quick start. Build `glass3sdkphonedemo` and `glassdemo` separately. [Glass3 quick start](https://x-docs.rokid.com/docs/terminal-sdk/getting-started/%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B.html)

The Maven metadata confirms both coordinates and `2.2.0-E` are publicly resolvable. It also shows newer `P` releases and snapshots, but the public Enterprise guide still pins `2.2.0-E`; do not upgrade by guessing what `E` and `P` mean. [Glasses metadata](https://maven.rokid.com/repository/maven-public/com/rokid/security/glass3.open.sdk/maven-metadata.xml), [phone metadata](https://maven.rokid.com/repository/maven-public/com/rokid/security/phone.sdk/maven-metadata.xml)

### Legacy CXR-S

CXR-S is the glasses-resident SDK. Rokid's legacy guide says it runs on YodaOS-Sprite, lets an on-glasses app use the data channel, and exchanges structured or binary messages bidirectionally with mobile CXR-M. The guide distributes it through the same Maven repository and pins `com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45` with `minSdk >= 28`; Rokid's current metadata now marks `1.0` as the release. There is no separate browser download step: Gradle retrieves the AAR. [Legacy CXR-M 1.1.0/CXR-S guide](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html), [CXR-S metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/cxr-service-bridge/maven-metadata.xml)

The guide's example Gradle root is named `CXRServiceDemo`, but no separately downloadable official CXR-S sample archive is linked publicly. The current public replacement sample is `glassdemo` in `glass3sdkdemo`.

### Legacy CXR-M

CXR-M is the legacy mobile companion SDK and the documented phone-side counterpart when an on-glasses app deliberately uses CXR-S. Rokid's legacy 1.1.0 guide distributes `com.rokid.cxr:client-m:1.1.0` through Maven, names the sample archive `CXRMSamples_110.zip`, and requires a developer account, real-name/developer verification, credentials, device-SN binding, and a downloaded `.lc` authorization file. Current Maven metadata exposes release `1.2.2`, but the public legacy guide and its sample remain at 1.1.0, so using 1.2.2 would require vendor confirmation. [Legacy CXR-M/CXR-S guide](https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ec4070ccd4941799958ef842431733f.html), [CXR-M metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-m/maven-metadata.xml)

### CXR-L

Rokid's public Maven repository exposes the Android AAR `com.rokid.cxr:client-l`; metadata currently marks `1.1.0` as the release. Thus the Android artifact can be obtained by adding the same Maven repository and a Gradle dependency on `com.rokid.cxr:client-l:1.1.0`. [CXR-L metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-l/maven-metadata.xml), [CXR-L 1.1.0 POM](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-l/1.1.0/client-l-1.1.0.pom)

A Rokid representative describes CXR-L as extending the Rokid AI App and exposing glasses image, audio, display, and command-channel capabilities, and directs developers to join the developer Discord and register for SDK support. This makes it an AI-app-mediated option, not the documented two-app Enterprise pair. [Rokid representative's CXR-L announcement](https://www.linkedin.com/posts/liangguan_join-the-rokid-discord-server-activity-7458221545224658944-6MGO)

## Inferences

- For Poker-Dealer's private app-to-app channel, the current `phone.sdk` + `glass3.open.sdk` pair is the narrowest supported route because Rokid explicitly documents bidirectional phone/glasses messaging for that pair and supplies both sample apps.
- Legacy CXR-M **would** be necessary if the project intentionally selected legacy CXR-S, because Rokid documents CXR-S's data channel as communicating with CXR-M. It is **not** an additional dependency on the selected current Enterprise route.
- CXR-L is unnecessary for the MVP and introduces dependence on the Rokid AI/Hi Rokid host application's authorization/session path. Nothing in the public current Enterprise guide requires it for direct phone↔glasses messages.

## Access-gated or still unknown

- No public Rokid compatibility matrix maps RG-glasses firmware `1.22.009-20260710-150201` or CXR service `1.135` to an exact Glass3, CXR-S, CXR-M, or CXR-L version. Compatibility must be proven by running the vendor sample on these two devices before product implementation.
- The public Rokid pages do not define what the Maven `E` and `P` variants mean or authorize choosing a newer variant for this device. Use the documented `2.2.0-E` pair until Rokid says otherwise.
- The public legacy portal does not expose a downloadable CXR-S sample archive beyond the `CXRServiceDemo` project name, nor does the current public Rokid documentation expose authoritative CXR-L Android/iOS sample names. Request those, a firmware/version matrix, and release/redistribution terms through Rokid's developer-support/Discord registration path if the fallback SDK families must be evaluated.
- Public Maven readability is not a redistribution license. No public license grant was found adjacent to these proprietary artifacts; resolve terms before vendoring AARs, redistributing them, or committing them to the repository.

## Immediate follow-up

Stage only the vendor-linked `glass3sdkdemo` source and Maven coordinates in a local ignored area, then run the phone/glasses message exchange on Fold6 + RG-glasses. That spike should determine whether the current pair initializes without online API credentials and whether firmware `1.22.009`/CXR service `1.135` supports the documented private message path.
