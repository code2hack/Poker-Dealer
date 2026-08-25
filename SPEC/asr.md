# Dealer-Local ASR Specification

**Status:** Normative MVP architecture; detailed model qualification deferred  
**Owner:** Dealer Android client  
**Relationship:** Used by both Dealer-recorded and Poker-recorded speech

## 1. Decision

Poker-Dealer MVP performs automatic speech recognition locally in Dealer.

Dealer uses:

- an ONNX runtime;
- a supported locally deployed ASR model;
- one project-owned recognition pipeline shared by Dealer and Poker audio sources.

Raw voice/audio is not uploaded to Codex app-server in MVP. Recognized text becomes an editable draft and is submitted through ordinary text Send or Steer semantics.

## 2. Authority and boundaries

Dealer is authoritative for:

- recording-session identity for Dealer microphone capture;
- receiving-session identity for Poker audio;
- temporary audio buffering;
- ASR model/runtime selection;
- partial/final transcript state;
- insertion of reviewable transcript text into the draft.

Codex app-server receives only the resulting text unless another future specification explicitly adds an audio-input feature.

Poker may capture audio, but Poker is not the authoritative recognizer. Poker sends identified audio data to Dealer over a future qualified CXR path.

ASR implementation MUST NOT import concrete CXR SDK types. CXR transport MUST expose a project-owned audio-transfer boundary.

## 3. Supported sources

### 3.1 Dealer source

Dealer records from the Android client's microphone after normal platform permission and user initiation.

### 3.2 Poker source

Poker captures audio after explicit user initiation and transfers it to Dealer.

The exact CXR framing and lifecycle are deferred to `SPEC/cxr.md`, but the resulting Dealer input must identify:

- source = Poker;
- recording/session ID;
- ordered audio chunks or complete payload;
- completion/cancel state;
- encoding/sample metadata required by the selected model;
- transfer/retry identity.

Both sources enter the same recognition pipeline after normalization.

## 4. ASR session state

ASR session state is local and orthogonal to Codex `ThreadStatus`.

A session uses these conceptual states:

```text
IDLE
  → CAPTURING or RECEIVING
  → FINALIZING
  → RECOGNIZING
  → REVIEWABLE_TEXT
  → COMMITTED_TO_DRAFT
  → IDLE
```

Terminal alternatives are:

- `CANCELED`
- `ERROR`

Rules:

- ASR state never changes Codex thread runtime.
- Recording/transcription never auto-sends.
- One completed transcript is committed to one exact draft revision.
- Canceling recognition does not mutate the Codex thread.
- A stale/reconnecting Codex connection does not prevent local recognition or draft editing.

## 5. Recognition output

- Partial transcripts may be displayed during recognition.
- Only reviewable transcript text enters the durable draft.
- The user may edit, append, or discard the transcript.
- Transcript insertion preserves the current cursor/selection behavior defined by the Dealer or Poker input surface.
- Sending uses ordinary `turn/start` or `turn/steer` after all normal authority checks.
- A blocking request may prevent Steer but does not prevent local recording, recognition, or draft editing.

## 6. No Codex audio upload

MVP MUST NOT encode recorded speech as:

- `UserInput.audio`;
- `UserInput.localAudio`;
- arbitrary document/file upload;
- a remote audio URL.

The voice composer is an ASR text-entry surface only.

Future direct audio input requires a separate accepted specification and must not be inferred from current app-server support.

## 7. Model/runtime contract

Dealer uses a locally deployed supported model through ONNX.

Before recognition, Dealer must establish:

- model present and readable;
- model/version supported by the application;
- runtime initialization success;
- required language/audio parameters;
- adequate local storage and memory;
- no conflicting model update.

Dealer must surface model-unavailable and initialization failures without losing the recording-session identity or inserting fabricated text.

Exact choices for:

- model family;
- model package format;
- model download/update UI;
- language packs;
- quantization;
- hardware acceleration;
- partial-decoding cadence;
- punctuation/normalization;
- diarization;

remain deferred to a later ASR qualification/specification pass.

## 8. Temporary audio and privacy

- Raw audio may be buffered only as needed for active capture, transfer, recognition, bounded retry, and diagnostics explicitly enabled by the user.
- Normal successful completion or cancellation MUST release temporary raw audio.
- Raw audio MUST NOT be included in ordinary application logs.
- Secret or sensitive transcript fields requested by Codex remain governed by the structured-request UI and are not copied into diagnostics.
- Durable state stores the resulting draft text, not the raw recording.

## 9. Failure and recovery

### Dealer capture failure

- stop capture;
- retain any already committed draft text;
- show a local error;
- allow retry from a new ASR session.

### Recognition failure

- do not insert fabricated or empty success text;
- allow retry while the bounded temporary audio remains available;
- allow discard;
- keep Codex mutation controls independent.

### Poker transfer failure

- identify the failed recording/session;
- do not concatenate chunks from different recordings;
- do not recognize an incomplete payload as complete unless the model explicitly supports streaming partial recognition and the UI labels it partial;
- reconcile transfer identity before retry;
- never send partial raw audio directly to Codex.

### Process recreation

- durable draft text survives;
- active raw-audio sessions may fail closed and require a new recording unless a later implementation explicitly qualifies resumable temporary storage;
- no recovered session auto-sends.

## 10. CXR requirements

A qualified Poker-audio CXR path must prove:

- deterministic start/end/cancel events;
- ordered chunk or complete-payload delivery;
- duplicate/loss behavior;
- recording identity;
- encoding/sample metadata;
- backpressure;
- bounded buffering;
- process-death and link-loss behavior;
- privacy/security;
- error/retry semantics;
- acceptable latency and battery/thermal behavior.

CXR qualification must not weaken Dealer's no-blind-replay or exact-identity rules.

## 11. UI relationship

`SPEC/dealer-ui.md` governs the Dealer voice composer.

Poker's future interaction specification governs Poker recording controls.

Both surfaces produce the same semantic outcome:

> editable text in the current draft, never an automatic Codex mutation.

## 12. Acceptance criteria

- Dealer and Poker audio sources use one Dealer-owned ONNX recognition pipeline.
- ASR state is separate from Codex `ThreadStatus`.
- Raw audio is never submitted to Codex in MVP.
- Recognition never auto-sends.
- Final text is editable before Send/Steer.
- Temporary audio is released after normal completion/cancel.
- Poker transfer preserves exact recording identity.
- ASR failures do not clear unrelated drafts or create Codex operations.
- Dealer ASR works with Poker disconnected for local microphone capture.
