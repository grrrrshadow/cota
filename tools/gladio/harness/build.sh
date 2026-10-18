#!/bin/bash
# Builds the Gladio shader converter from app/src/main/cpp/gladiorenderer as a Linux program
# (see harness.c). Only the GLES/EGL/JNI headers come from the NDK, the C library is the host's.
#
#   tools/gladio/harness/build.sh [BUILD_DIR]     -> BUILD_DIR/harness (default: /tmp/gladio-harness)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT=${1:-/tmp/gladio-harness}

if [ -z "${ANDROID_NDK:-}" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
    ANDROID_NDK=$(ls -d "${SDK:-/home/user/android-sdk}"/ndk/24.0.8215888 2>/dev/null || ls -d "${SDK:-/home/user/android-sdk}"/ndk/* | tail -1)
fi
SYSROOT_INC="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"

mkdir -p "$OUT/include/android"
for header in GLES2 GLES3 EGL KHR jni.h; do ln -sfn "$SYSROOT_INC/$header" "$OUT/include/$header"; done
cat > "$OUT/include/android/log.h" <<'EOF'
#pragma once
#include <stdio.h>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) fprintf(stderr, __VA_ARGS__)
EOF

# __ANDROID__ selects the server (GL_SERVER) side of the shared headers
gcc -O1 -g -std=gnu11 -D__ANDROID__ -D_GNU_SOURCE -Wno-implicit-function-declaration \
    -I"$OUT/include" -I"$HERE" -I"$CPP/gladiorenderer/include" -I"$CPP/winlator/include" -I"$CPP/vortekrenderer/include" \
    "$HERE/harness.c" "$CPP/gladiorenderer/src/shader_converter.c" "$CPP/winlator/src/arrays.c" \
    -o "$OUT/harness"
echo "$OUT/harness"
