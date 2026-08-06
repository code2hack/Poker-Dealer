# Rokid official ASR placement on RG-glasses

**Research date:** 2026-08-03
**Source policy:** first-party Rokid developer, product, support, and officially
linked sample sources only. No reseller, press, forum, or community-project
claims are used.

## Short answer

For **general speech-to-text/dictation**, Rokid's documented Glass3 paths do
not put the ASR model on either the glasses or the paired phone:

- the normal online path captures audio on the glasses, sends it to the phone,
  and has the phone forward it to a remote speech service;
- the newer independent path captures on the glasses and connects from the
  glasses directly to a remote public or private speech service.

The phone is therefore a relay, credential/bootstrap, and connectivity layer
in the normal path, not the documented inference host. The newer path removes
the phone from the ASR data path, but it still does not provide on-device
dictation.

Rokid separately provides **offline command/wake-phrase matching** on the
glasses. That is a small, configured command vocabulary, not free-form ASR.

## Product and SDK scope

The official Glass3 Demo guide tells developers to expect Android Studio to
show the device as `Rokid RG-glasses`, so these Glass3 documents match the
Poker hardware identifier. The same guide requires distinct glasses and phone
Demo apps ([Demo Running Guide](https://x-docs.rokid.com/docs/en/downloads/demo-guide.html)).

The documentation is primarily the **Rokid Sprite Enterprise / Glass3 SDK**
surface. Rokid explicitly distinguishes enterprise firmware from consumer
firmware: enterprise uses Rokid AI Enterprise/the enterprise Demo, while a
consumer system uses the Rokid AI app with developer mode
([Glass3 FAQ](https://x-docs.rokid.com/docs/en/faq/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98.html)).
The repository does not currently record which of those firmware families is
installed on the target RG-glasses, so SDK availability still needs a live
capability check.

Version scope is also in transition. The main changelog lists phone and glasses
SDK `2.1.9-E`, dated **2026-05-25**
([changelog](https://x-docs.rokid.com/docs/en/terminal-sdk/resources/%E7%89%88%E6%9C%AC%E5%8F%98%E6%9B%B4%E6%97%A5%E5%BF%97.html)),
while the independent speech guide names Glass3 SDK `2.2.0-E` as its current
example. The conclusions below therefore identify the exact path rather than
assuming all releases have the same architecture.

## Evidence by capability

### 1. Normal online ASR: glasses capture, phone relays, service recognizes

Rokid's SDK tutorial documents this complete flow:

1. the glasses capture microphone audio;
2. Bluetooth/P2P carries it to the paired phone;
3. the phone forwards it to the speech service;
4. recognition results return to the glasses.

It also requires both phone-side and glasses-side SDK initialization and API
Key credentials for online ASR
([SDK initialization and ASR/TTS tutorial](https://x-docs.rokid.com/docs/en/terminal-sdk/getting-started/%E8%A7%86%E9%A2%91%E6%95%99%E7%A8%8B.html)).
The phone sample confirms that the phone initializes hosted ASR/TTS with
business credentials and a public-cloud environment
([Phone SDK bootstrap](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/35-voice-ai/01-%E6%89%8B%E6%9C%BA%E7%AB%AF-SDK-%E5%88%9D%E5%A7%8B%E5%8C%96%EF%BC%88ASR-TTS%EF%BC%89.html)).

The glasses app starts the session through `getGlassAsrService()` and receives
partial, final, and optional intent callbacks, but Rokid labels this sample
**Cloud ASR** rather than local inference
([Glasses TTS and ASR sample](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/35-voice-ai/02-%E7%9C%BC%E9%95%9C%E7%AB%AF-TTS-%E4%B8%8E-ASR.html)).

### 2. Independent online ASR: glasses connect directly to a service

The newer `online-speech` integration can run in a glasses-side Android app.
It uses the glasses `open-sdk` audio source, then opens a WebSocket to a
configured public-cloud or private-deployment domain and ASR endpoint
([ASR/TTS Speech Service SDK guide](https://x-docs.rokid.com/docs/en/private-speech/SDK_INTEGRATION.html)).
The companion Demo guide places this code in the glasses project and describes
`connect()` followed by `startAsrWithMic()` as recording and automatically
streaming audio
([Independent ASR/TTS Sample Guide](https://x-docs.rokid.com/docs/en/private-speech/DEMO_ANDROID.html)).

This path removes the phone relay, but recognition remains a network speech
service. A private deployment changes the server endpoint; it does not place
the model on the glasses or phone.

### 3. Offline commands and wake phrases: glasses-local, constrained vocabulary

Rokid exposes `getGlassOfflineCmdService()` in the glasses SDK. An app installs
explicit `VoiceAction` phrases and receives a callback when one matches. The
official sample classifies this as offline wake/command handling with no
network, contrasting it with cloud ASR for free dictation
([Glasses TTS and ASR sample](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/35-voice-ai/02-%E7%9C%BC%E9%95%9C%E7%AB%AF-TTS-%E4%B8%8E-ASR.html)).

This establishes a glasses-side offline command capability, but it is not a
general transcript engine. Rokid's FAQ is explicit that offline open ASR is
not currently supported and directs developers to online or private speech
services instead
([Glass3 FAQ](https://x-docs.rokid.com/docs/en/faq/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98.html)).

### 4. Consumer built-ins do not establish a reusable dictation model

Rokid's consumer support material says the companion app is required for
pairing and AI-service configuration, and its voice-assistant troubleshooting
requires both a Bluetooth phone connection and a stable phone network
([Rokid consumer FAQ](https://global.rokid.com/pages/faqs)). That is consistent
with a phone/service-assisted product flow but does not disclose the exact
inference host.

The same FAQ mentions an offline Chinese/English fallback for the specific
teleprompter smart-scrolling feature. It does not document that model's process
location or expose it as third-party free-form ASR. It must not be generalized
into an on-device dictation guarantee.

## What remains unknown

- Whether the target consumer/enterprise firmware grants Poker access to
  `glass3.open.sdk` or the independent `online-speech` artifacts.
- Whether the target Android image registers any standard Android
  `RecognitionService`, and whether that service is genuinely on-device.
- Which process performs consumer assistant and teleprompter recognition; the
  public consumer docs do not provide that boundary.
- Latency, punctuation behavior, supported languages, network-loss behavior,
  and simultaneous use with Poker's microphone/camera on the real device.

## Implication for Poker M5

The previous idea of calling Android `SpeechRecognizer` **entirely on Poker**
cannot be justified from Rokid's official ASR documentation. The documented
general-ASR implementation is online.

The normal paired-phone route also depends on Rokid's phone/glasses SDK channel,
which conflicts with Poker-Dealer's accepted ordinary-hotspot transport and
no-proprietary-companion-channel boundary. The independent glasses-side
`online-speech` client would instead add a Rokid SDK, credentials, and a network
speech service.

## Subsequent project decision

On 2026-08-04 the project selected an independent Dealer-local ASR path. Poker
streams ephemeral microphone audio over the existing authenticated connection;
Dealer runs `sherpa-onnx` with a pinned streaming ONNX conversion derived from
the exact upstream `nvidia/parakeet-unified-en-0.6b` model and projects
provisional text back. Rokid speech SDKs and services are not part of that path.

That product decision is not evidence of performance. The real Fold6 and
RG-glasses still require M5 qualification for microphone ownership, sustained
streaming, recognition behavior, latency, thermal load, battery use, and
cancellation.
