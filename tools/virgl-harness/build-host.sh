#!/bin/bash
# Builds the VirGL host (app/src/main/cpp/virglrenderer, the same sources as its CMakeLists.txt) for x86_64 Linux
# against Mesa's EGL/GLES (llvmpipe), linked with harness_server.c which stands in for the Java side.
# Needs gcc, libx11-dev and the libEGL.so.1/libGLESv2.so.2 runtime (libegl1, libgles2, libegl-mesa0); the Khronos
# headers come from the Mesa tree that build-guest.sh fetches (MESA_SRC).
# Usage: tools/virgl-harness/build-host.sh [OUT] [VIRGLRENDERER_DIR]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/virgl-harness}
OUT=$(realpath -m "${1:-$WORK/virgl_harness_server}")
SRC=$(realpath "${2:-$HERE/../../app/src/main/cpp/virglrenderer}")
MESA_SRC=${MESA_SRC:-$WORK/mesa3d-custom/virgl-23.1.9}
[ -f "$MESA_SRC/include/EGL/egl.h" ] || { echo "MESA_SRC=$MESA_SRC has no include/EGL/egl.h, run build-guest.sh first" >&2; exit 1; }

# Source list straight from CMakeLists.txt so the harness builds what the app builds
mapfile -t SOURCES < <(sed -n '/add_library(virglrenderer/,/)/p' "$SRC/CMakeLists.txt" | grep -oE '(src|server)/[^ )]+\.c')

OBJ=$(mktemp -d)
trap 'rm -rf "$OBJ"' EXIT
CFLAGS=(-O2 -g -fPIC -Wno-unused-function -Wimplicit-function-declaration -Wno-int-conversion -Wno-incompatible-pointer-types
        -DGL_GLEXT_PROTOTYPES -DEGL_NO_X11
        -I"$HERE/stub" -I"$MESA_SRC/include"
        -I"$SRC/src" -I"$SRC/src/gallium/include" -I"$SRC/src/gallium/auxiliary" -I"$SRC/src/gallium/auxiliary/util" -I"$SRC/server")
pids=()
for s in "${SOURCES[@]}"; do
  gcc "${CFLAGS[@]}" -c "$SRC/$s" -o "$OBJ/$(echo "$s" | tr / _).o" 2>>"$OBJ/warnings.log" &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
gcc -O2 -g -DEGL_NO_X11 -I"$HERE/stub" -I"$MESA_SRC/include" -c "$HERE/harness_server.c" -o "$OBJ/harness_server.o"
gcc -o "$OUT" "$OBJ"/*.o -Wl,--wrap=eglMakeCurrent -Wl,--wrap=glClientWaitSync \
    -l:libEGL.so.1 -l:libGLESv2.so.2 -lX11 -lm -lpthread
grep -c "warning:" "$OBJ/warnings.log" | xargs -I{} echo "{} compiler warnings (same code as the NDK build)" >&2
echo "$OUT"
