# Wearable media for Termux and DGX Spark agents

Research date: 2026-07-26

Scope: primary sources only—Rokid and Android documentation/vendor sample
source, current OpenAI Codex documentation, NVIDIA documentation/model cards,
Ultralytics source documentation, and IETF standards. This is research, not a
change to `SPEC.md` or an ADR.

> Architecture update: after this research, M1 selected a restricted
> SSH-supervised WebRTC data plane in ADR-0006. Live media remains post-MVP;
> statements below that reserve all WebRTC work for later preserve the research
> conclusion at the time but no longer describe the active Bridge data plane.

## Conclusion

**The product idea is viable as a media-ingestion system, but it is not yet a
proven capability of this exact RG-glasses unit.** The sound boundary is:

```text
RG-glasses capture
        │
        ▼
Dealer session + media relay
        │
        ├── stills / selected frames ──► vision workers (YOLO, LocateAnything, VLM)
        ├── encoded live video ────────► video-ingestion worker
        └── audio ────────────────────► ASR / audio worker
                                           │
                                           ▼
                            files + structured observations + transcripts
                                           │
                                           ▼
                               Codex / tmux-hosted agent
```

The agent should consume a selected image file, transcript, detection result,
or a tool call into the media service—not an unbounded camera bitstream in its
language-model context. NVIDIA's own VSS blueprint uses the same separation:
live/file ingestion and accelerated vision services sit behind an agent that
routes requests to tools; VSS supports images, video files and RTSP streams,
with separate ASR and CV pipelines. [NVIDIA VSS overview](https://docs.nvidia.com/vss/3.0.0/overview/latest/index.html),
[VSS features](https://docs.nvidia.com/vss/2.4.1/content/features.html),
[VSS agent workflow](https://docs.nvidia.com/vss/latest/agent-workflow-lvs.html)

## What is established

### Rokid capture surface

Rokid's current Glass3 documentation exposes still capture, MP4 recording,
H.264 feeding, microphone capture, and live phone-side video/audio preview. Its
documented live preview returns raw NV21 video frames and audio buffers, with a
documented 5–30 FPS and 500 kbit/s–10 Mbit/s range. These are real vendor
capabilities, not a transport thought experiment. [Rokid Glass SDK media
sample](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/30-media/01-%E7%9C%BC%E9%95%9C%E7%AB%AF-SDK-%E6%8B%8D%E7%85%A7%E5%BD%95%E5%83%8F%E5%BD%95%E9%9F%B3%E4%B8%8E-AI.html),
[Rokid live-preview sample](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/20-message-transfer/06-%E5%AE%9E%E6%97%B6%E8%A7%86%E9%A2%91%E9%A2%84%E8%A7%88.html),
[Rokid Glass API](https://x-docs.rokid.com/docs/en/terminal-sdk/api-reference/Glass3%20%20SDK%28%E7%9C%BC%E9%95%9C%E7%AB%AF%29%20API%E6%96%87%E6%A1%A3.html)

Rokid also publishes an app-level camera sample whose `QuickCameraManager`
uses Android `camera2` (`CameraManager`, `CameraDevice`, `ImageReader`, and
`MediaRecorder`), while the same vendor sample's microphone path calls the
proprietary `GlassSdk` media service. Consequently, public Android camera
access has strong vendor-sample evidence for Glass3, but the sample does **not**
prove public `AudioRecord` microphone access. [Rokid app-level capture
guide](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/30-media/02-%E7%9C%BC%E9%95%9C%E7%AB%AF%E5%BA%94%E7%94%A8%E6%8B%8D%E7%85%A7%E5%BD%95%E5%83%8F.html),
[vendor-sample `QuickCameraManager`](https://gitee.com/as_pixar/glass3sdkdemo/blob/20af0e445894e944a64437a5d3ce0d08b09f5a66/glassdemo/app/src/main/java/com/rokid/glass/camera/QuickCameraManager.kt),
[vendor-sample `SdkMediaActivity`](https://gitee.com/as_pixar/glass3sdkdemo/blob/20af0e445894e944a64437a5d3ce0d08b09f5a66/glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt)

Android requires user-granted `CAMERA`/`RECORD_AUDIO` permissions. Android 12
also provides device-wide camera/microphone toggles and indicators; disabled
toggles yield blank camera data or silent audio. Camera/microphone foreground
services have foreground-service-type and background-start constraints.
[Android sensitive-data access](https://developer.android.com/training/permissions/explaining-access),
[Android foreground camera/microphone rules](https://developer.android.com/about/versions/11/privacy/foreground-services),
[Android 12 foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### Codex is an orchestrator/consumer, not a live-media endpoint

Current Codex CLI documentation explicitly supports one or more image inputs
with `-i`/`--image` (including PNG and JPEG), and `/mention` points Codex at
local files or folders. The documented Codex prompt-input surface lists images,
not native audio tracks or video tracks. Therefore photos and extracted video
frames are first-class Codex inputs; continuous audio/video should first be
converted by tools into files, transcripts, sampled frames, and structured
events. [OpenAI image inputs](https://learn.chatgpt.com/docs/image-inputs),
[Codex CLI command reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)

This does not prevent Codex from operating a media pipeline: Codex can inspect
workspace files and run allowed local programs, but a local path is useful only
after the bytes exist in a filesystem readable by that Codex process.
[Codex CLI command reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)

### Local vision workers fit the design

Ultralytics YOLO prediction accepts image paths, PIL images, NumPy arrays,
video files, webcams, and RTSP/RTMP/TCP/IP streams. `stream=True` yields results
incrementally, and its low-latency default can retain the newest frame instead
of indefinitely buffering old frames. Thus a decoded glasses feed can be
passed directly as frames or exposed as a conventional stream endpoint.
[Ultralytics prediction sources](https://github.com/ultralytics/ultralytics/blob/main/docs/en/modes/predict.md),
[Ultralytics inference loader](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/data/build.py),
[Ultralytics stream defaults](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/default.yaml)

NVIDIA LocateAnything-3B is an image-and-text grounding model. Its supported
tasks include open-set/dense detection, phrase grounding, GUI grounding, OCR
localization, and pointing; the reference worker takes a PIL RGB image plus a
natural-language query. It is not documented as an audio model or a continuous
video-stream API, so live video must be sampled/decoded into images before
calls. [NVIDIA LocateAnything model card](https://huggingface.co/nvidia/LocateAnything-3B),
[NVLabs reference implementation](https://github.com/NVlabs/Eagle/tree/main/Embodied)

The model card specifies Linux, BF16 Transformers inference, and NVIDIA
Ampere/Blackwell/Hopper/Lovelace support. It reports an A100 4K-image batch
probe at 11.71 GB peak reserved memory with its sparse attention backend, while
DGX Spark provides a Blackwell GPU and 128 GB unified memory. This makes a Spark
deployment technically plausible, but NVIDIA publishes LocateAnything
benchmarks on H100/A100—not GB10—so Spark throughput and ARM64 dependency
compatibility still require a local probe. LocateAnything weights are
non-commercial research-use under the NVIDIA license, which is a product-use
constraint independent of technical viability. [LocateAnything runtime and
license](https://huggingface.co/nvidia/LocateAnything-3B),
[DGX Spark hardware](https://docs.nvidia.com/dgx/dgx-spark/hardware.html)

## Transport finding

SSH is suitable for control, metadata, photos, recordings, and bounded
chunked/resumable file transfer. SSH multiplexes channels over one secure
connection, while TCP provides a reliable ordered byte stream; the inference
is that loss delays later bytes in that same stream, which is undesirable for
deadline-sensitive live media. This is a latency tradeoff, not a content-type
or binary-safety limitation. [SSH connection protocol](https://www.rfc-editor.org/rfc/rfc4254),
[TCP specification](https://www.rfc-editor.org/rfc/rfc9293)

WebRTC is a good candidate for truly live audio/video because its standards
cover audio, video, and auxiliary data over direct paths; media uses SRTP and
data channels can carry messages separately. WebRTC does not choose the
application’s signaling protocol, authorization policy, session ownership, or
storage scheme, so restricted SSH can remain the authenticated bootstrap and
lifetime supervisor while the WebRTC session carries application control and
an authenticated media service terminates its media tracks. [WebRTC
overview](https://www.rfc-editor.org/rfc/rfc8825),
[WebRTC data channels](https://www.rfc-editor.org/rfc/rfc8831),
[WebRTC transports](https://www.rfc-editor.org/rfc/rfc8835)

Recommended transport split:

| Payload | Initial route | Reason |
| --- | --- | --- |
| Photo / selected JPEG frame | Reliable bounded upload over the authenticated Host link | Exact, retryable artifact that Codex and every image worker can open |
| Short recording | Reliable resumable upload | Offline analysis values completeness over latency |
| Live audio/video | WebRTC media tracks into a Host media worker | Codec negotiation, timing, congestion and loss behavior are media-aware |
| Prompts, session control, media IDs, results | Reliable ordered WebRTC Bridge data channel | Keeps application control beside the existing Bridge Protocol while SSH remains bootstrap and supervisor |

## Not yet proven and required probes

1. Run a tiny **public-API-only** APK on the exact RG-glasses firmware:
   enumerate `CameraManager.cameraIdList` and stream NV21/YUV frames; enumerate
   `AudioManager` input devices and attempt `AudioRecord`; record permissions,
   formats, sample rates, concurrency errors, privacy-toggle behavior, heat,
   sustained FPS, and battery drain. Android defines these APIs but does not
   guarantee this vendor exposes usable devices to this app.
   [Android CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager),
   [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
2. Do not treat the proprietary Glass3 media-service demonstrations as proof
   that Poker's required public-Android-only build can use the same paths, and
   do not assume that documentation for a Glass3 SKU maps exactly to this
   `RG-glasses` firmware without the hardware probe.
   [Rokid Glass SDK media sample](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/30-media/01-%E7%9C%BC%E9%95%9C%E7%AB%AF-SDK-%E6%8B%8D%E7%85%A7%E5%BD%95%E5%83%8F%E5%BD%95%E9%9F%B3%E4%B8%8E-AI.html)
3. Benchmark end-to-end glass-to-result latency separately for JPEG snapshots,
   YOLO frame inference, LocateAnything queries, VLM queries, and ASR. Use a
   bounded latest-frame queue; never let an agent's slow reasoning create an
   unbounded video backlog. Ultralytics exposes frame-stride and latest-frame
   buffering controls for this purpose.
   [Ultralytics stream defaults](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/cfg/default.yaml)
4. Define explicit capture indicators, user initiation/stop, retention limits,
   and per-agent/session authorization before enabling wearable capture.
   Android treats camera and microphone data as sensitive and exposes user
   indicators and kill switches.
   [Android privacy guidance](https://developer.android.com/privacy-and-security/about)

These probes can validate the future feature without changing M1's compound
transport decision: SSH remains the authenticated bootstrap and lifetime
supervisor, the reliable ordered data channel remains the application-control
path, and media tracks are added only after capture and model-ingestion
acceptance tests pass.
