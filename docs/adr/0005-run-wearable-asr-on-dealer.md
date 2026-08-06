---
status: accepted
date: 2026-08-04
last_amended: 2026-08-06
---

# Run wearable ASR locally on Dealer

## Context

Rokid's public material does not establish a supported glasses-local free-form
ASR model on the target hardware. Poker also has less compute, memory, battery,
and HUD space than the Fold6. Poker–Dealer needs private, review-before-submit
dictation without adding a Rokid companion channel, cloud speech dependency, or
Codex-host audio path.

## Decision

The glasses microphone is the default. When selected, Poker captures ordered
16 kHz mono PCM16 and sends it over the existing authenticated Dealer↔Poker
connection. Dealer may instead select a phone-local source for a future session
and capture it directly. The source is snapshotted at session start and never
hot-switched. Its owning endpoint provides the monotonic sample counter for
commit/delete fences. Dealer recognizes locally, owns the provisional slice,
and projects it to Poker. This is local input preprocessing; Codex app-server
remains the only product backend.

Dealer bundles the pinned Android ARM64 `sherpa-onnx` runtime and an atomically
refreshable catalog, but no large model pack. Catalog entries are trusted only
as data for runtime-supported sherpa adapters and identify immutable artifacts,
revision, digest, installed size, languages, backend, licenses, and a versioned
editable-profile schema. Downloads may use the canonical Hugging Face source or
a user-configured HTTPS mirror. They stage and verify atomically; no downloaded
native code or arbitrary model URL is accepted.

The first successfully installed candidate becomes the default. Later installs
and newer revisions never replace or activate it automatically. Revisions
install side by side and start with their catalog-supplied default profile.
Every installed pack has a strict Dealer-owned JSON profile whose settings are
limited to that pack's schema; artifacts, model family, audio contract,
transport, and Poker operations are immutable. One complete profile and audio
source are snapshotted for each ASR session.

The M4 baseline candidates are Parakeet Unified INT8 streaming at the 560 ms
artifact profile and Moonshine v2 tiny quantized for offline recognition. The
catalog must expose every currently available model supported by the bundled
sherpa runtime and Dealer adapter; unavailable entries are excluded. CPU ONNX
Runtime is the initial backend.

Streaming raw audio and provisional results remain in bounded process memory.
An offline model may rotate active audio into temporary, device-encrypted,
backup-excluded files solely for the current slice; those files are deleted on
commit, discard, exit, failure, or next startup and are never recovered,
uploaded, logged, or retained for tuning. Committed text remains reviewable and
no speech pause submits or commits it.

## Consequences

- Dealer bears model storage and inference load; Poker remains a lightweight
  capture and transcript surface.
- Android `SpeechRecognizer`, Rokid online speech, Poker-local inference,
  host-side inference, and cloud recognition are not automatic fallbacks.
- Model installation, profile validation, audio fencing, failure isolation, and
  real Fold6/Poker qualification are part of the expanded M4.
- M4 does not add thermal-protection policy; that may be designed only after
  measured need.
