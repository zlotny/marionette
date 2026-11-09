#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
REV="${PHONON_REV:-32c1554a8ce442d672de90fc82700bbdcf08d918}"
SRC="build/xn-ptts"
[ -d "$SRC" ] || git clone -q https://github.com/gradium-ai/xn-ptts "$SRC"
git -C "$SRC" fetch -q origin && git -C "$SRC" checkout -q "$REV"
source scripts/env.sh
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384 -C target-cpu=generic -C target-feature=+dotprod,+fp16"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
rustup target add aarch64-linux-android >/dev/null
(cd "$SRC" && rm -rf android/jniLibs && cargo ndk -t arm64-v8a -o android/jniLibs build --release -p ptts-ffi)
mkdir -p app/src/main/jniLibs/arm64-v8a
cp "$SRC/android/jniLibs/arm64-v8a/libptts_ffi.so" app/src/main/jniLibs/arm64-v8a/
ls -la app/src/main/jniLibs/arm64-v8a/libptts_ffi.so
