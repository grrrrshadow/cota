#!/bin/bash
# Smoke test of patches/ on the build machine: builds the same libGL natively (x86_64) and runs glxinfo and glxgears
# on Xvfb with zink over lavapipe. Shows that the xlib present path (readback + XShmPutImage) works, nothing about
# Vortek or Mali. Needs what build.sh needs plus libx11-dev libxext-dev libx11-xcb-dev libxcb-*-dev libxshmfence-dev
# libxxf86vm-dev libxrandr-dev libdrm-dev libzstd-dev libexpat1-dev zlib1g-dev mesa-vulkan-drivers xvfb mesa-utils
# x11-apps imagemagick.
# Usage: WORK=dir tools/mesa-zink/smoke-x86.sh   (WORK = the one build.sh used: reuses its patched source and venv)
#   VERSION=x.y.z builds another Mesa than build.sh's (its patched source mesa-x.y.z/ with .patched must be in WORK).
set -euo pipefail
WORK=$(realpath "${WORK:?set WORK to the build.sh work dir}")
VERSION=${VERSION:-$(sed -n 's/^VERSION=//p' "$(dirname "$0")/build.sh")}
SRC=$WORK/mesa-$VERSION
[ -f "$SRC/.patched" ] || { echo "run build.sh with the same WORK first"; exit 1; }
export PATH=$WORK/venv/bin:$PATH

BUILD=$WORK/build-x86
if [ ! -f "$BUILD/build.ninja" ]; then
  meson setup "$BUILD" "$SRC" --buildtype=release \
    -Dprefix=/usr -Dlibdir=lib -Dplatforms=x11 -Dglx=xlib -Degl=disabled -Dgbm=disabled -Dglvnd=disabled \
    -Dgles1=disabled -Dgles2=disabled -Dgallium-drivers=softpipe,zink -Dvulkan-drivers= -Dllvm=disabled \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=enabled -Dexpat=enabled -Dtools= -Dbuild-tests=false \
    -Dvideo-codecs= > "$WORK/meson-setup-x86.log"
fi
ninja -C "$BUILD" src/gallium/targets/libgl-xlib/libGL.so.1.5.0 > "$WORK/ninja-x86.log"

OUT=$WORK/smoke
mkdir -p "$OUT"
Xvfb :97 -screen 0 400x400x24 > "$OUT/xvfb.log" 2>&1 &
XVFB=$!
trap 'kill $XVFB 2>/dev/null' EXIT
sleep 2
export DISPLAY=:97 LD_LIBRARY_PATH=$BUILD/src/gallium/targets/libgl-xlib GALLIUM_DRIVER=zink
# zink only takes a CPU Vulkan device (lavapipe) when software rendering is asked for.
export LIBGL_ALWAYS_SOFTWARE=1
export VK_DRIVER_FILES=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1)
export VK_ICD_FILENAMES=$VK_DRIVER_FILES

glxinfo -B > "$OUT/glxinfo.txt" 2>&1
grep -E "OpenGL renderer|OpenGL core profile version|OpenGL version string" "$OUT/glxinfo.txt"
grep -q "renderer string: zink" "$OUT/glxinfo.txt"

# Swap path (no context passed to flush_frontbuffer), with and without the threaded context.
for gt in 1 0; do
  GALLIUM_THREAD=$gt timeout 6 glxgears -geometry 300x300+0+0 > "$OUT/gears-$gt.log" 2>&1 || true
  echo "GALLIUM_THREAD=$gt: $(tail -1 "$OUT/gears-$gt.log")"
done
GALLIUM_THREAD=1 glxgears -geometry 300x300+0+0 > /dev/null 2>&1 &
GEARS=$!
sleep 3
xwd -root -silent | convert xwd:- -crop 300x300+0+0 "$OUT/gears.png"
kill $GEARS
colors=$(convert "$OUT/gears.png" -format %k info:)
echo "glxgears screenshot: $OUT/gears.png, $colors colors"
[ "$colors" -gt 16 ] || { echo "smoke test: FAILED (nothing presented)"; exit 1; }

gcc -O1 -rdynamic -o "$OUT/glcore" "$(dirname "$0")/glcore.c" -I"$SRC/include" "$BUILD/src/gallium/targets/libgl-xlib/libGL.so.1.5.0" -lX11 -ldl
for version in 3.3 4.6; do
  echo "== core $version"
  "$OUT/glcore" "$version"
done
echo "smoke test: OK"
