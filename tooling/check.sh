#!/bin/sh
set -eu

if [ -n "${PREFIX:-}" ] && [ -x "${PREFIX}/bin/aapt2" ]; then
    ./gradlew test lint "-Pandroid.aapt2FromMavenOverride=${PREFIX}/bin/aapt2"
else
    ./gradlew test lint
fi
