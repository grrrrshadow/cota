#!/bin/bash
# Builds the Box64 bundled in app/src/main/assets/box64: upstream v0.4.4 plus the DynaCache fixes in patches/
# (cherry-picked from upstream main, they keep a crash or a stale block from poisoning the cache).
# Needs Ubuntu 24.04 (glibc 2.39, same as the rootfs) with gcc-aarch64-linux-gnu, cmake, git and zstd.
# Usage: tools/box64/build.sh [OUT.tzst]   (WORK=dir keeps the build tree)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(realpath -m "${1:-$HERE/../../app/src/main/assets/box64/box64-0.4.4-dl.tzst}")
WORK=${WORK:-$(mktemp -d)}

[ -d "$WORK/src" ] || git -c advice.detachedHead=false clone -q --depth 1 --branch v0.4.4 https://github.com/ptitSeb/box64.git "$WORK/src"
if ! git -C "$WORK/src" log --oneline | grep -q "DYNACACHE"; then
  git -C "$WORK/src" -c user.name=build -c user.email=build@localhost am -q "$HERE"/patches/*.patch
fi

# Same options as upstream's ANDROID_GLIBC release job. The interpreter and runpath must be set at link time:
# patching them later with patchelf adds a segment below box64's fixed base address and it crashes.
# /data/data/com.winlator is rewritten to /data/data/org.winlator when the app extracts the file.
cmake -S "$WORK/src" -B "$WORK/build" -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_BUILD_TYPE=Release -DNOGIT=1 \
  -DARM64=1 -DWINLATOR_GLIBC=1 -DARM_DYNAREC=1 -DBAD_SIGNAL=1 \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--dynamic-linker=/data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1 -Wl,-rpath,/data/data/com.winlator/files/rootfs/lib" > "$WORK/cmake.log"
make -C "$WORK/build" -j"$(nproc)" box64 > "$WORK/make.log" 2>&1

rm -rf "$WORK/pkg" && mkdir -p "$WORK/pkg/usr/local/bin"
aarch64-linux-gnu-strip --strip-debug -o "$WORK/pkg/usr/local/bin/box64" "$WORK/build/box64"
tar -C "$WORK/pkg" --owner=0 --group=0 -cf - . | zstd -19 -q -f -o "$OUT"
echo "$OUT"
