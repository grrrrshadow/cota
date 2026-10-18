#!/bin/bash
# Runs pixelstore_test.c, then converts the OpenTTD shaders and every shaders/NAME.vert + NAME.frag
# pair with the Gladio shader converter and checks each result with glslc as GLSL ES 3.20 (what the
# GLES driver gets).
# NAME.outname, when present, names the output bound to location 0 with glBindFragDataLocation.
#
#   tools/gladio/harness/validate.sh [BUILD_DIR]      exit status 0 only when every shader passes
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${1:-/tmp/gladio-harness}

HARNESS=$("$HERE/build.sh" "$OUT" 2> "$OUT.build.log" | tail -1) || { cat "$OUT.build.log"; exit 1; }

if [ -z "${ANDROID_NDK:-}" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
    ANDROID_NDK=$(ls -d "${SDK:-/home/user/android-sdk}"/ndk/24.0.8215888 2>/dev/null || ls -d "${SDK:-/home/user/android-sdk}"/ndk/* | tail -1)
fi
GLSLC="$ANDROID_NDK/shader-tools/linux-x86_64/glslc"

# Pixel store size helpers shared by host and guest
gcc -Wall -Wno-unused-function -I"$ROOT/app/src/main/cpp/gladiorenderer/include" -I"$ROOT/app/src/main/cpp/winlator/include" \
    "$HERE/pixelstore_test.c" -o "$OUT/pixelstore_test" && "$OUT/pixelstore_test" || { echo "pixel store helpers failed"; exit 1; }

CONVERTED="$OUT/converted"
rm -rf "$CONVERTED"
mkdir -p "$CONVERTED"

"$HARNESS" "$CONVERTED" --openttd 2>> "$OUT/converter.log" || { echo "converter failed on the OpenTTD shaders"; exit 1; }
for vs in "$HERE"/shaders/*.vert; do
    name=${vs%.vert}
    args=()
    [ -f "$name.outname" ] && args=(-o "$(cat "$name.outname")")
    "$HARNESS" "$CONVERTED" "${args[@]}" "$vs" "$name.frag" 2>> "$OUT/converter.log" || { echo "converter failed on $(basename "$name")"; exit 1; }
done

failed=0
total=0
for shader in "$CONVERTED"/*; do
    total=$((total + 1))
    stage=frag
    [[ $shader == *.vert ]] && stage=vert
    if head -1 "$shader" | grep -qx '#version 320 es' && \
       errors=$("$GLSLC" --target-env=opengl -fauto-map-locations -fauto-bind-uniforms -fshader-stage=$stage "$shader" -o /dev/null 2>&1); then
        echo "PASS $(basename "$shader")"
    else
        failed=$((failed + 1))
        echo "FAIL $(basename "$shader")"
        echo "${errors:-first line is not #version 320 es}" | sed 's/^/    /'
    fi
done

echo "$((total - failed))/$total converted shaders pass glslc (#version 320 es), output in $CONVERTED"
[ $failed -eq 0 ]
