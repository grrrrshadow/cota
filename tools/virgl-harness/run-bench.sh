#!/bin/bash
# Runs the guest (Mesa 23.1.9 virpipe from build-guest.sh) against a harness server (build-host.sh) under Xvfb:
# glxinfo -B, glxgears, the GL 3.3 core test, a frame dump for comparing hosts, and two clients at once.
# The server prints fps, its own thread's CPU time per 1000 frames and per-frame call counts.
# Order: build-guest.sh, build-host.sh OUT, run-bench.sh OUT (compare.sh does the last two for several commits).
# Usage: tools/virgl-harness/run-bench.sh SERVER_BINARY [LABEL]
#   GEARS_SECS=20 GEARS_ARGS="-geometry 1280x720" GL33_FRAMES=3000 DISPLAY_NUM=91 WORK=/tmp/virgl-harness
#   ONLY_GEARS=1 skips everything after glxgears
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/virgl-harness}
SERVER=$(realpath "$1")
LABEL=${2:-$(basename "$SERVER")}
OUT=$WORK/results/$LABEL
GEARS_SECS=${GEARS_SECS:-20}
GL33_FRAMES=${GL33_FRAMES:-3000}
DISPLAY_NUM=${DISPLAY_NUM:-91}
GUEST_LIB=$WORK/guest-build/src/gallium/targets/libgl-xlib
SOCK=/tmp/.virgl-harness-$LABEL.sock   # sun_path is 108 bytes, WORK may be deeper than that
mkdir -p "$OUT"
export DISPLAY=:$DISPLAY_NUM

if [ ! -S "/tmp/.X11-unix/X$DISPLAY_NUM" ]; then
  Xvfb ":$DISPLAY_NUM" -screen 0 1280x1024x24 -nolisten tcp >"$WORK/xvfb.log" 2>&1 &
  for _ in $(seq 50); do [ -S "/tmp/.X11-unix/X$DISPLAY_NUM" ] && break; sleep 0.1; done
fi
[ "$WORK/gl33test" -nt "$HERE/gl33test.c" ] || gcc -O2 -I"$WORK/mesa3d-custom/virgl-23.1.9/include" "$HERE/gl33test.c" -o "$WORK/gl33test" \
  -L"$GUEST_LIB" -lGL -lX11 -lm

# Same variables the app sets for VirGL (VirGL 4.3 core in the settings dialog)
guest() {
  env LD_LIBRARY_PATH="$GUEST_LIB" GALLIUM_DRIVER=virpipe VIRGL_SERVER_PATH="$SOCK" VIRGL_NO_READBACK=true \
      MESA_GL_VERSION_OVERRIDE=4.3FC "$@"
}

start_server() {
  env EGL_PLATFORM=surfaceless VIRGL_SERVER_PATH="$SOCK" HARNESS_EXIT_IDLE=1 HARNESS_QUIET=1 "${@:2}" \
      "$SERVER" >"$OUT/$1.server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 50); do [ -S "$SOCK" ] && break; sleep 0.1; done
}

stop_server() {
  for _ in $(seq 50); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 0.1; done
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  rm -f "$SOCK"
}

start_server glxinfo
guest glxinfo -B >"$OUT/glxinfo.txt" 2>&1 || true
stop_server
grep -E "renderer string|core profile version" "$OUT/glxinfo.txt"

start_server glxgears HARNESS_STATS_EVERY=2000
guest timeout -s INT "$GEARS_SECS" stdbuf -oL glxgears ${GEARS_ARGS:-} >"$OUT/glxgears.txt" 2>&1 || true
stop_server
echo "glxgears: $(grep 'done:' "$OUT/glxgears.server.log")"
[ -z "${ONLY_GEARS:-}" ] || exit 0

start_server gl33test HARNESS_STATS_EVERY=1000
guest "$WORK/gl33test" "$GL33_FRAMES" >"$OUT/gl33test.txt" 2>&1 || true
stop_server
echo "gl33test: $(tail -1 "$OUT/gl33test.txt")"
echo "gl33test: $(grep 'done:' "$OUT/gl33test.server.log")"

# Frame 200 of the GL 3.3 test, to compare the presented image between hosts
start_server dump HARNESS_DUMP="200:$OUT/frame200.ppm"
guest "$WORK/gl33test" 250 >/dev/null 2>&1 || true
stop_server
echo "dump: $(grep 'fnv1a' "$OUT/dump.server.log" | sed 's/.*fnv1a/fnv1a/')"

# Two clients on the one server thread, the second one leaving early
start_server two
guest timeout -s INT 8 glxgears >/dev/null 2>&1 &
GEARS=$!
guest "$WORK/gl33test" 600 >"$OUT/two-gl33test.txt" 2>&1 || true
wait "$GEARS" || true
stop_server
echo "two clients: $(tail -1 "$OUT/two-gl33test.txt")"
grep 'done:' "$OUT/two.server.log" | sed 's/^/  /'
