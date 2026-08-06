# Dealer sherpa-onnx runtime notices

The Dealer APK packages the Android ARM64 libraries from `sherpa-onnx`
`1.13.4`. The release AAR is pinned in `versions.env` by URL and SHA-256
digest. That release carries CPU ONNX Runtime `1.27.0`; the version is pinned
there as an explicit compatibility check.

The packaged runtime is distributed under the Apache License, Version 2.0:

<https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/LICENSE>

The bundled CPU ONNX Runtime native library is distributed under the MIT
License:

<https://github.com/microsoft/onnxruntime/blob/v1.27.0/LICENSE>

The instrumentation-only smoke model is test data, never a production APK
asset. Its source archive and digest are pinned in `versions.env`.
