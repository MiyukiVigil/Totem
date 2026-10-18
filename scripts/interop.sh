#!/usr/bin/env sh
# Runs every test, including the Kotlin <-> Rust Noise interop tests.
# Needs: cargo on PATH, JAVA_HOME pointing at a JDK 17+.
set -eu
cd "$(dirname "$0")/.."

cargo test -p totem-protocol
cargo build -p totem-protocol --example noise_peer
(cd android && ./gradlew :core:test --console=plain)
