#!/usr/bin/env bash
set -euo pipefail
# Build nav_graph_core Rust library for Android aarch64 and generate Kotlin bindings
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CARGO_DIR="$SCRIPT_DIR"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
JAVA_SRC_DIR="$PROJECT_DIR/app/src/main/java"

# 1. Determine Android NDK path
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Try common locations
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$HOME/Android/Sdk/ndk/"*/ 2>/dev/null | head -1)
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set. Install Android NDK via Android Studio SDK Manager."
    exit 1
fi

# 2. Set up cargo config for the NDK toolchain
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android21-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android21-clang"

# 3. Build the Rust library for aarch64-linux-android
echo "Building nav_graph_core for aarch64-linux-android..."
cd "$CARGO_DIR"
cargo build --release --target aarch64-linux-android

# 4. Copy the .so to jniLibs
mkdir -p "$JNILIBS_DIR"
cp "$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so" "$JNILIBS_DIR/"
echo "Copied libnav_graph_core.so to $JNILIBS_DIR"

# 5. Generate Kotlin bindings using uniffi-bindgen
# Generate Kotlin bindings using uniffi-bindgen
echo "Generating Kotlin bindings..."
cargo run --bin uniffi-bindgen generate --library \
    "$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so" \
    --language kotlin --out-dir "$JAVA_SRC_DIR" 2>/dev/null || \
cargo run --features "uniffi/cli" --bin uniffi-bindgen generate --library \
    "$CARGO_DIR/target/aarch64-linux-android/release/libnav_graph_core.so" \
    --language kotlin --out-dir "$JAVA_SRC_DIR"

echo "Done. .so in $JNILIBS_DIR, bindings in $JAVA_SRC_DIR/uniffi/"
