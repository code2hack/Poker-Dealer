#!/bin/sh
set -eu

if [ -n "${PREFIX:-}" ] && [ -x "${PREFIX}/bin/aapt2" ]; then
    ./gradlew test lint "-Pandroid.aapt2FromMavenOverride=${PREFIX}/bin/aapt2"
else
    ./gradlew test lint
fi
if command -v cargo >/dev/null 2>&1; then
    cargo fmt --check --manifest-path bridge/Cargo.toml
    cargo clippy --manifest-path bridge/Cargo.toml --all-targets -- -D warnings
    cargo test --manifest-path bridge/Cargo.toml
else
    printf '%s\n' "Rust is not installed; skipped bridge gates." >&2
    exit 1
fi
