# M1 WebRTC Implementation Options

**Researched:** 2026-07-26 to 2026-07-27
**Status:** Recommendation only; no implementation stack is approved.

## Recommendation

Use different standards-compatible implementations at the platform seam:

- Google libwebrtc's Java/JNI interface on Android.
- `str0m` 0.21.x inside the Rust Host Bridge.

Share Bridge Protocol fixtures and end-to-end interoperability tests rather
than forcing one native library onto both platforms.

## Android

Provisionally pin upstream libwebrtc commit
[`1719a64863d7cfc3e5d4842655e7b46e6631743e`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e)
and build a full `arm64-v8a` AAR.

Relevant upstream capabilities:

- [`DataChannel.Init`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/DataChannel.java)
  defaults to ordered delivery with neither a retransmission-time nor
  retransmission-count limit.
- [`PeerConnection.RTCConfiguration`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/PeerConnection.java)
  accepts an empty ICE-server list and a supplied certificate.
- [`RtcCertificatePem.getFingerprints()`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/RtcCertificatePem.java)
  exposes the fingerprint that SSH-authenticated signaling must bind.
- [`PeerConnectionFactory.Options`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/PeerConnectionFactory.java)
  exposes loopback/VPN adapter masks, while
  [`IceCandidate.adapterType`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/IceCandidate.java)
  supports candidate allowlisting.
- Android VPN discovery and socket binding are implemented by
  [`NetworkMonitorAutoDetect`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/api/org/webrtc/NetworkMonitorAutoDetect.java)
  and
  [`android_network_monitor.cc`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/sdk/android/src/jni/android_network_monitor.cc).

The public Java interface cannot hard-bind the peer connection to a named
interface. Filtering what is signaled can prevent fallback selection but does
not prevent every candidate socket from being created, so the Fold6 stats and
network-change tests remain mandatory.

Google's
[`build_aar.py`](https://webrtc.googlesource.com/src/+/1719a64863d7cfc3e5d4842655e7b46e6631743e/tools_webrtc/android/build_aar.py)
uses the large Linux-only Chromium/WebRTC toolchain. If approved, build the
pinned source in CI, retain the exact toolchain inputs, vendor or otherwise
make the checksummed AAR reproducibly available to a fresh clone, retain the
generated license inventory, and verify Android's
[16-KiB page-size requirements](https://developer.android.com/guide/practices/page-sizes).

Convenient third-party Maven AARs reduce build cost but may lag the fingerprint
interface or obscure exact provenance. `libdatachannel` is lighter, but its
linked Android wrapper still lacks a finished dependency path and adds a
separate JNI surface; it also supplies media transport rather than
libwebrtc's Android capture and codec pipeline.

## Rust Host

Provisionally use:

```toml
str0m = { version = "0.21", default-features = false, features = ["rust-crypto"] }
```

Why:

- [`str0m`](https://crates.io/crates/str0m) is stable, actively released, and
  uses a sans-I/O design that lets the Bridge own its Tokio task, UDP socket,
  timers, and shutdown.
- Caller-supplied [`Candidate::host`](https://docs.rs/str0m/latest/str0m/struct.Candidate.html)
  candidates fit the explicit route allowlist without STUN/TURN.
- [`ChannelConfig`](https://docs.rs/str0m/latest/str0m/channel/struct.ChannelConfig.html)
  and [`Reliability::Reliable`](https://docs.rs/str0m/latest/str0m/channel/enum.Reliability.html)
  support the required ordered reliable data channel.
- DTLS fingerprint verification is enabled by default and available through
  the `Rtc` configuration/interface.
- The official
  [platform matrix](https://docs.rs/str0m/latest/str0m/#platform-support)
  lists ARM64 Linux as tested and ARM64 Android as compiled but not tested.

The major caveat is that `str0m` is most heavily tested as an SFU/server
library; its peer-to-peer path is supported but less exercised. Termux native
builds and Android-libwebrtc interoperability are therefore spike gates.

The best fallback is stable `webrtc-rs` 0.17.2. It integrates directly with
Tokio and exposes IP/interface filters plus an explicit loopback option, but
0.17.x is feature-frozen while its replacement remains prerelease. A
`libdatachannel` Rust wrapper carries substantially more CMake/C++/OpenSSL and
Termux build risk.

## Unresolved loopback exception

[RFC 8445 section 5.1.1.1](https://www.rfc-editor.org/rfc/rfc8445.html#section-5.1.1.1)
requires loopback-interface addresses to be excluded from ICE candidates.
Using `127.0.0.1` or `::1` for same-device Dealer↔Termux WebRTC is therefore a
deliberate controlled exception, not ordinary standards-compliant ICE.

Do not configure or implement that exception until it is explicitly approved.
If approved, it applies only to the local Termux profile, is signaled only
inside the authenticated SSH session, and must fail closed if the production
Fold6 cannot prove interoperability.
