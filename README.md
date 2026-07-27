# Poker–Dealer

Poker–Dealer is a private wearable client for selected tmux panes. `SPEC.md` is the normative implementation contract.

The repository is currently at **M0 complete / M1 started**:

- `apps/dealer` is a native Compose mock companion app.
- `apps/poker` is a mock-flavored Compose HUD app with no proprietary SDK dependency.
- `shared/protocol` owns protocol envelopes, frame-safe UTF-8 chunking, and the `PokerTransport` interface.
- `shared:bridge-client` is the planned reusable Bridge session client used by
  Dealer and M1 integration tests; its final platform boundary depends on the
  M1 WebRTC implementation decision.
- `shared/domain` owns cards, conversations, policies, revision ordering, and lossless card splitting.
- `shared/testing` supplies the loopback transport and a 20,000+ character fixture card.
- `bridge` is the Rust bridge workspace. Its non-mutating `doctor` command is
  the first M1 slice; the next transport slice is a forced
  `poker-dealer-bridge rtc-bootstrap` command invoked and supervised by
  OpenSSH. Bridge Protocol traffic then uses a reliable ordered WebRTC data
  channel. M1 configures no STUN/TURN: Termux stays on-device and Spark uses
  its approved Tailscale route.

No CXR/Rokid transport artifacts, credentials, or invented vendor APIs are
present. The production Dealer↔Poker path is ordinary authenticated Android
TLS/TCP over the Fold6 hotspot, with Dealer initiating a connection to Poker.

## Build and test

Requirements:

- JDK 21 (the project emits Java 17-compatible shared bytecode)
- Android SDK platform 35
- stable Rust 1.85 or newer with `rustfmt` and `clippy`
- tmux 3.2 or newer for bridge diagnostics

Run every local gate:

```sh
./tooling/check.sh
```

The equivalent portable commands are:

```sh
./gradlew test lint
cargo fmt --check --manifest-path bridge/Cargo.toml
cargo clippy --manifest-path bridge/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path bridge/Cargo.toml
```

On ARM64 Termux, install the native `aapt2` package. `tooling/check.sh` automatically supplies its path to Gradle because Google's Maven AAPT2 binary targets desktop Linux x86-64.

Build the two developer APKs with:

```sh
./gradlew :apps:dealer:assembleDebug :apps:poker:assembleMockDebug
```

The Poker app opens directly into the long-card reader. Its logical lines are virtualized by Compose's lazy list and the complete fixture remains in the shared card model.

## Bridge diagnostics

The initial read-only M1 diagnostic invokes tmux directly with an argument vector:

```sh
cargo run --manifest-path bridge/Cargo.toml -- doctor
```

It validates the tmux version and prints a JSON report. It never modifies a
pane. `rtc-bootstrap` and `list-servers` fail closed until their M1
implementations are complete.

## Device transport

Dealer and Poker MUST build without CXR-M, CXR-L, CXR-S, or a proprietary
companion data channel. The validated topology, security boundary, reliability
requirements, and hardware acceptance matrix are normative in `SPEC.md`
revision 5. Raw prototype evidence is preserved on
`prototype/android-hotspot-transport` at commit `9d36ed1`.
