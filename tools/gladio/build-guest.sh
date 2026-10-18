#!/bin/bash
# Builds the Gladio guest libGL (brunodev85/gladio, LGPL-2.1, at the commit brunodev85/winlator pins)
# for the aarch64 glibc rootfs, with the patches in tools/gladio/guest/patches, and packs it as
# app/src/main/assets/graphics_driver/gladio-$VERSION.tzst (./usr/lib/libGL.so.1.7.0).
#
#   tools/gladio/build-guest.sh [WORK_DIR]
#
# Needs: git, cmake, aarch64-linux-gnu-gcc (Ubuntu gcc-aarch64-linux-gnu, glibc 2.39 sysroot),
# the X11 headers (libx11-dev, only Xlib.h types are used, nothing is linked), zstd.
# NO_PATCHES=1 builds the pristine sources (should match the shipped gladio-1.1 exports).
# The shipped gladio-1.2.tzst comes out byte for byte with Ubuntu 24.04's aarch64-linux-gnu-gcc
# 13.3.0-6ubuntu2~24.04.1 and zstd 1.5.5, other versions give an equivalent library.
set -euo pipefail

VERSION=1.2
GLADIO_URL=https://github.com/brunodev85/gladio
GLADIO_COMMIT=116c0d14dedbea3bd057f98f1db138bb1efe225e
ROOTFS=/data/data/com.winlator/files/rootfs

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
HOST_INCLUDE="$ROOT/app/src/main/cpp/gladiorenderer/include"
ASSETS="$ROOT/app/src/main/assets/graphics_driver"
WORK=${1:-/tmp/gladio-guest}
SRC="$WORK/gladio"

[ -f /usr/include/X11/Xlib.h ] || { echo "missing /usr/include/X11/Xlib.h (apt install libx11-dev)"; exit 1; }
command -v aarch64-linux-gnu-gcc > /dev/null || { echo "missing aarch64-linux-gnu-gcc"; exit 1; }

mkdir -p "$WORK"
if [ ! -d "$WORK/gladio.git" ]; then
    git clone --bare "$GLADIO_URL" "$WORK/gladio.git"
fi
rm -rf "$SRC"
mkdir -p "$SRC"
git --git-dir="$WORK/gladio.git" archive "$GLADIO_COMMIT" | tar -x -C "$SRC"

# Upstream uses CRLF, the patches are made against LF sources
find "$SRC/src" "$SRC/include" -type f \( -name '*.c' -o -name '*.h' \) -exec sed -i 's/\r$//' {} +

if [ "${NO_PATCHES:-0}" != 1 ]; then
    for patch in "$HERE"/guest/patches/*.patch; do
        echo "applying $(basename "$patch")"
        patch -d "$SRC" -p1 --no-backup-if-mismatch < "$patch"
    done

    # The headers below are shared with the host renderer and must stay identical, apart from
    # the data dir that DataDirPatchOutputStream rewrites when the asset is extracted
    for header in gladio.h texture_utils.h gl_vao.h gl_client_state.h request_codes.h gl_enums.h; do
        if ! diff -u <(sed 's|/data/data/com\.winlator/|/data/data/org.winlator/|' "$SRC/include/$header") "$HOST_INCLUDE/$header"; then
            echo "include/$header differs from the host copy"
            exit 1
        fi
    done
fi

# Only the X11 headers are needed, keep the host's other headers out of the cross build
mkdir -p "$WORK/xinclude"
ln -sfn /usr/include/X11 "$WORK/xinclude/X11"

cat > "$WORK/aarch64.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)
EOF

# Same flags as upstream build.sh, the rpath points into the rootfs
rm -rf "$WORK/build"
cmake -S "$SRC" -B "$WORK/build" -DCMAKE_TOOLCHAIN_FILE="$WORK/aarch64.cmake" -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_FLAGS="-isystem $WORK/xinclude -ffile-prefix-map=$SRC/=" \
      -DCMAKE_C_FLAGS_RELEASE="-O2 -Wl,-rpath=$ROOTFS/lib" > "$WORK/cmake.log"
make -C "$WORK/build" -j"$(nproc)" > "$WORK/make.log" 2>&1 || { tail -40 "$WORK/make.log"; exit 1; }
grep -E "warning:|error:" "$WORK/make.log" || true

LIB="$WORK/build/libGL.so"

# Checks: same exports as the shipped 1.1 library (guest/exports.txt), only libc/ld.so needed,
# glibc <= 2.39, every embedded data dir path in the com.winlator form so the extraction rewrite applies
aarch64-linux-gnu-nm -D --defined-only "$LIB" | awk '{print $3}' | LC_ALL=C sort > "$WORK/exports.txt"
if ! diff -u "$HERE/guest/exports.txt" "$WORK/exports.txt"; then
    echo "exported symbols differ from gladio-1.1"
    exit 1
fi
echo "exports: $(wc -l < "$WORK/exports.txt") symbols, same as gladio-1.1"

aarch64-linux-gnu-readelf -d "$LIB" | grep -E "NEEDED|SONAME|RUNPATH"
NEEDED=$(aarch64-linux-gnu-readelf -d "$LIB" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
[ "$NEEDED" = "ld-linux-aarch64.so.1 libc.so.6 " ] || { echo "unexpected NEEDED: $NEEDED"; exit 1; }
MAX_GLIBC=$(aarch64-linux-gnu-objdump -T "$LIB" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)
echo "newest glibc symbol version: $MAX_GLIBC"
[ "$(printf '%s\n' "$MAX_GLIBC" GLIBC_2.39 | sort -V | tail -1)" = GLIBC_2.39 ] || { echo "needs a newer glibc than the rootfs"; exit 1; }
strings -a "$LIB" | grep "/data/data/" | tee "$WORK/paths.txt"
if grep -v "^/data/data/com\.winlator/" "$WORK/paths.txt"; then
    echo "data dir paths not in the com.winlator form"
    exit 1
fi
if strings -a "$LIB" | grep -q "$WORK"; then
    echo "build path leaked into the library"
    exit 1
fi

if [ "${NO_PATCHES:-0}" = 1 ]; then
    echo "pristine build in $LIB (not packed)"
    exit 0
fi

PACKAGE="$WORK/package"
rm -rf "$PACKAGE"
mkdir -p "$PACKAGE/usr/lib"
install -m 755 "$LIB" "$PACKAGE/usr/lib/libGL.so.1.7.0"
tar -C "$PACKAGE" --sort=name --owner=root:0 --group=root:0 --mtime='2026-10-17 00:00:00 UTC' -cf - . | zstd -19 -q -f -o "$ASSETS/gladio-$VERSION.tzst"
tar -I zstd -tvf "$ASSETS/gladio-$VERSION.tzst"
sha256sum "$PACKAGE/usr/lib/libGL.so.1.7.0" "$ASSETS/gladio-$VERSION.tzst"
