#!/bin/sh
set -eu

if [ -n "${PREFIX:-}" ] && [ -x "${PREFIX}/bin/aapt2" ]; then
    ./gradlew test lint :apps:dealer:verifyEmbeddedTailnetPackaging :apps:dealer:verifySherpaOnnxPackaging "-Pandroid.aapt2FromMavenOverride=${PREFIX}/bin/aapt2"
else
    ./gradlew test lint :apps:dealer:verifyEmbeddedTailnetPackaging :apps:dealer:verifySherpaOnnxPackaging
fi
