#!/bin/sh
set -eu

if [ -n "${PREFIX:-}" ] && [ -x "${PREFIX}/bin/aapt2" ]; then
    ./gradlew \
        :prototypes:android-hotspot:dealer:assembleDebug \
        :prototypes:android-hotspot:poker:assembleDebug \
        "-Pandroid.aapt2FromMavenOverride=${PREFIX}/bin/aapt2"
else
    ./gradlew \
        :prototypes:android-hotspot:dealer:assembleDebug \
        :prototypes:android-hotspot:poker:assembleDebug
fi
