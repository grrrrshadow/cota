#!/bin/bash
# Checks that the x86_64 zink from smoke-x86.sh starts when the Vulkan device looks the way the phone's Mali does to
# "mesa zink" through Vortek (vortek-sim.c: Vulkan 1.3.128, Vortek's fixed extension list from the vortekrenderer
# sources, and Mali's extensions, features, limits and formats from PROFILE, a vortek-device.py trim of a
# vulkan.gpuinfo.org report; default: the Motorola Edge 60 Fusion's Mali-G615 MC2). lavapipe stands in for the GPU,
# so this shows what zink requires and reports, not how Mali renders. Prints the GL versions zink reports, what
# keeps it from the next GL version, and which core contexts (and MESA_GL_VERSION_OVERRIDE values) work.
# Usage: WORK=dir tools/mesa-zink/vortek-filter-check.sh [VK_KHR_extra,...]   (run smoke-x86.sh with the same WORK)
#   VORTEK_SIM_DEVICE=lavapipe keeps lavapipe's features (only the extension list and version are Vortek's).
#   The optional list exposes those host extensions too, to see what Vortek would have to add.
#   VERSION=x.y.z checks another Mesa build than build.sh's; PROFILE=file another device.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(realpath "${WORK:?set WORK to the build.sh work dir}")
VERSION=${VERSION:-$(sed -n 's/^VERSION=//p' "$HERE/build.sh")}
PROFILE=$(realpath "${PROFILE:-$HERE/mali-g615-edge60fusion.json}")
SRC=$WORK/mesa-$VERSION
LIBGL=$WORK/build-x86/src/gallium/targets/libgl-xlib
[ -f "$LIBGL/libGL.so.1.5.0" ] || { echo "run smoke-x86.sh with the same WORK first"; exit 1; }
VORTEK=$HERE/../../app/src/main/cpp/vortekrenderer

SIM=$WORK/vortek-sim
mkdir -p "$SIM"
python3 -I "$HERE/vortek-device.py" lists "$VORTEK" > "$SIM/lists.h"
python3 -I "$HERE/vortek-device.py" header "$PROFILE" "$SRC/src/vulkan/registry/vk.xml" \
  "$SRC/include/vulkan/vulkan_core.h" "$VORTEK" > "$SIM/device.h"
gcc -shared -fPIC -fvisibility=hidden -O1 -Wall -Wno-unused-function -I"$SIM" -I"$SRC/include" \
  -o "$SIM/libvortek_sim.so" "$HERE/vortek-sim.c"
cat > "$SIM/vortek_sim.json" <<EOF
{"file_format_version": "1.2.0", "layer": {"name": "VK_LAYER_TEST_vortek_sim", "type": "GLOBAL",
 "library_path": "$SIM/libvortek_sim.so", "api_version": "1.3.0", "implementation_version": "1",
 "description": "What Vortek exposes to mesa zink"}}
EOF
gcc -O1 -rdynamic -o "$SIM/glcore" "$HERE/glcore.c" -I"$SRC/include" "$LIBGL/libGL.so.1.5.0" -lX11 -ldl

Xvfb :96 -screen 0 200x200x24 > "$SIM/xvfb.log" 2>&1 &
XVFB=$!
trap 'kill $XVFB 2>/dev/null' EXIT
sleep 2
export DISPLAY=:96 LD_LIBRARY_PATH=$LIBGL GALLIUM_DRIVER=zink
# The simulated Mali is an integrated GPU; zink only takes plain lavapipe (a CPU device) when software is asked for.
if [ "${VORTEK_SIM_DEVICE:-}" = lavapipe ]; then export LIBGL_ALWAYS_SOFTWARE=1; fi
export VK_DRIVER_FILES=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1)
export VK_ICD_FILENAMES=$VK_DRIVER_FILES VK_LAYER_PATH=$SIM VK_INSTANCE_LAYERS=VK_LAYER_TEST_vortek_sim
export VORTEK_SIM_ADD=${1:-}
device=${VORTEK_SIM_DEVICE:-$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["source"])' "$PROFILE")}
echo "Mesa $VERSION through Vortek's list, device: $device"

VORTEK_SIM_VERBOSE=1 glxinfo > "$SIM/glxinfo.txt" 2> "$SIM/glxinfo.err" || true
grep -E "OpenGL renderer|OpenGL core profile version|OpenGL version string|Error" "$SIM/glxinfo.txt" || true
grep -iE "^(mesa|zink|warning)" "$SIM/glxinfo.err" | sort -u | head -20 || true
grep "drops faked" "$SIM/glxinfo.err" | sort -u || true
if ! grep -q "renderer string: zink" "$SIM/glxinfo.txt"; then
  echo "vortek filter check: FAILED, zink does not start on this device${1:+ plus $1}"
  exit 1
fi

# What keeps each profile from the next GL version: the extension terms of Mesa's desktop compute_version
# (version.c) that zink does not advertise (checked against the compatibility profile's list, the longer one);
# "limits" when no extension is missing.
python3 -I - "$SRC/src/mesa/main/version.c" "$SIM/glxinfo.txt" <<'EOF'
import re, sys
version_c, glxinfo = open(sys.argv[1]).read(), open(sys.argv[2]).read()
desktop = re.search(r"\ncompute_version\(.*?\n}\n", version_c, re.S).group(0)
have = set(re.findall(r"GL_\w+", re.search(r"\nOpenGL extensions:\n(.*?)\n\n", glxinfo, re.S).group(1)))
steps = re.findall(r"const bool ver_(\d)_(\d) = \((.*?)\);", desktop, re.S)
m = re.search(r"OpenGL core profile version string: (\d)\.(\d)", glxinfo)
for major, minor, body in steps:
    if m and (major, minor) > m.groups():
        missing = sorted(set("GL_" + e for e in re.findall(r"extensions->(\w+)", body)) - have)
        print("core %s.%s, for %s.%s also: %s" % (m.group(1), m.group(2), major, minor,
                                                 " ".join(missing) or "limits (no extension missing)"))
EOF

for v in 3.2 3.3 4.0 4.4; do
  printf "core %s context: %s\n" "$v" "$("$SIM/glcore" "$v" 2>&1 | grep -E "GL_VERSION|context failed" | tr '\n' ' ')"
done
for v in 3.3 4.0; do
  printf "MESA_GL_VERSION_OVERRIDE=%s: %s\n" "$v" "$(MESA_GL_VERSION_OVERRIDE=$v glxinfo -B 2>&1 | \
    sed -n 's/^OpenGL \(core profile version\|version\) string: /\1 /p' | tr '\n' ';')"
  printf "MESA_GL_VERSION_OVERRIDE=%s, core %s context: %s\n" "$v" "$v" \
    "$(MESA_GL_VERSION_OVERRIDE=$v "$SIM/glcore" "$v" 2>&1 | grep -E "GL_VERSION|context failed|present:|map:" | tr '\n' ' ')"
done
echo "vortek filter check: OK${1:+ (with $1)}"
