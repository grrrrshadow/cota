#!/bin/bash
# Runs the packaged aarch64 libGL under qemu-aarch64 with the rootfs's own glibc, X11 libraries and Vulkan loader,
# on Ubuntu's arm64 lavapipe instead of Vortek: core contexts, a persistent coherent buffer and the present path
# (glcore.c), then a core 3.3 context through vortek-sim (the phone's Mali as Vortek shows it to zink). XShm is off
# (XLIB_NO_SHM=1) because the rootfs glibc hands SysV shm to the app, which is not running here; Xvfb listens on TCP
# because the rootfs libxcb looks for the X socket under /data/data/com.winlator. Needs qemu-user, xvfb and what
# build.sh needs (run build.sh with the same WORK first).
# Usage: WORK=dir tools/mesa-zink/smoke-aarch64.sh [zink.tzst]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(realpath "${WORK:?set WORK to the build.sh work dir}")
ROOTFS=$HERE/../../app/src/main/assets/rootfs.tzst
VERSION=${VERSION:-$(sed -n 's/^VERSION=//p' "$HERE/build.sh")}
TZST=$(realpath "${1:-$HERE/../../app/src/main/assets/graphics_driver/zink-$VERSION.tzst}")
SRC=$WORK/mesa-$VERSION
[ -f "$SRC/.patched" ] || { echo "run build.sh with the same WORK first"; exit 1; }

ROOT=$WORK/qemu-root
rm -rf "$ROOT" && mkdir -p "$ROOT"
zstd -dc "$ROOTFS" | tar -x -C "$ROOT" --wildcards './usr/lib/*.so*' './lib'
zstd -dc "$TZST" | tar -x -C "$ROOT"

LVP=$WORK/lvp-root
if [ ! -f "$LVP/.done" ]; then
  python3 -I "$HERE/fetch-debs.py" --runtime "$WORK/lvp-debs" mesa-vulkan-drivers > "$WORK/lvp-debs.log"
  rm -rf "$LVP" && mkdir -p "$LVP"
  for deb in "$WORK"/lvp-debs/*.deb; do dpkg-deb -x "$deb" "$LVP"; done
  touch "$LVP/.done"
fi
sed "s|/usr/lib/aarch64-linux-gnu/|$LVP/usr/lib/aarch64-linux-gnu/|" "$LVP/usr/share/vulkan/icd.d/lvp_icd.aarch64.json" > "$WORK/lvp_icd.json"

aarch64-linux-gnu-gcc -O1 -rdynamic -o "$WORK/glcore" "$HERE/glcore.c" -I"$SRC/include" -I"$WORK/sysroot/usr/include" \
  "$ROOT/usr/lib/libGL.so.1.7.0" "$ROOT/usr/lib/libX11.so.6" -ldl -Wl,--allow-shlib-undefined

Xvfb :92 -listen tcp -ac -screen 0 200x200x24 > "$WORK/xvfb-aarch64.log" 2>&1 &
XVFB=$!
trap 'kill $XVFB 2>/dev/null' EXIT
for i in $(seq 50); do [ -e /tmp/.X11-unix/X92 ] && break; sleep 0.2; done

fail=0
for version in 3.3 4.5 4.6; do
  for thread in 1 0; do
    echo "== core $version, GALLIUM_THREAD=$thread"
    DISPLAY=127.0.0.1:92 XLIB_NO_SHM=1 GALLIUM_THREAD=$thread GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=1 \
      VK_DRIVER_FILES=$WORK/lvp_icd.json timeout 900 \
      qemu-aarch64 "$ROOT/usr/lib/ld-linux-aarch64.so.1" --library-path "$ROOT/usr/lib:$LVP/usr/lib/aarch64-linux-gnu" \
      "$WORK/glcore" "$version" || fail=1
  done
done

# The same package on the phone's Mali the way Vortek shows it to zink (vortek-sim.c built for aarch64, loaded by
# the rootfs's Vulkan loader; see vortek-filter-check.sh). With the app's default MESA_GL_VERSION_OVERRIDE=3.3 under
# Vortek a core 3.3 context has to work; without it zink reports only 3.1 and refuses it (shown, not a failure).
VORTEK=$HERE/../../app/src/main/cpp/vortekrenderer
SIM=$WORK/vortek-sim-aarch64
mkdir -p "$SIM"
python3 -I "$HERE/vortek-device.py" lists "$VORTEK" > "$SIM/lists.h"
python3 -I "$HERE/vortek-device.py" header "$HERE/mali-g615-edge60fusion.json" "$SRC/src/vulkan/registry/vk.xml" \
  "$SRC/include/vulkan/vulkan_core.h" "$VORTEK" > "$SIM/device.h"
aarch64-linux-gnu-gcc -shared -fPIC -fvisibility=hidden -O1 -Wall -Wno-unused-function -I"$SIM" -I"$SRC/include" \
  -o "$SIM/libvortek_sim.so" "$HERE/vortek-sim.c"
cat > "$SIM/vortek_sim.json" <<EOF
{"file_format_version": "1.2.0", "layer": {"name": "VK_LAYER_TEST_vortek_sim", "type": "GLOBAL",
 "library_path": "$SIM/libvortek_sim.so", "api_version": "1.3.0", "implementation_version": "1",
 "description": "What Vortek exposes to mesa zink"}}
EOF
vortek_glcore() {
  env "$@" DISPLAY=127.0.0.1:92 XLIB_NO_SHM=1 GALLIUM_DRIVER=zink VK_DRIVER_FILES=$WORK/lvp_icd.json \
    VK_LAYER_PATH=$SIM VK_INSTANCE_LAYERS=VK_LAYER_TEST_vortek_sim timeout 900 \
    qemu-aarch64 "$ROOT/usr/lib/ld-linux-aarch64.so.1" --library-path "$ROOT/usr/lib:$LVP/usr/lib/aarch64-linux-gnu" \
    "$WORK/glcore" 3.3 2>&1
}
echo "== Vortek + Mali (simulated), core 3.3, MESA_GL_VERSION_OVERRIDE=3.3"
out=$(vortek_glcore MESA_GL_VERSION_OVERRIDE=3.3) || true
echo "$out"
echo "$out" | grep -q "GL_RENDERER: zink Vulkan 1.3(Vortek (Mali" && echo "$out" | grep -q "present: OK" &&
  echo "$out" | grep -q "persistent coherent map: OK" || fail=1
echo "== Vortek + Mali (simulated), core 3.3, no override"
vortek_glcore || true
[ "$fail" = 0 ] && echo "aarch64 smoke test: OK" || { echo "aarch64 smoke test: FAILED"; exit 1; }
