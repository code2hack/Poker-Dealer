# Choose the glasses microphone and ASR path

Type: prototype
Status: open
Blocked by: 10

## Question

On the exact RG-glasses firmware, can Poker capture microphone PCM through
public Android `AudioRecord`, invoke an installed recognizer through Android
`SpeechRecognizer`, or both; and which public-Android provider priority should
the MVP implement?

The answer must identify the visible microphone/recognizer source, failure and
fallback behavior, lifecycle constraints, and the path that can meet the
no-audio-retention requirement. An unavailable public API capability is a
reported limitation and does not authorize adding CXR or another proprietary
SDK.

## Comments
