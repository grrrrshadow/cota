#!/bin/bash
# Checks that the x86_64 zink from smoke-x86.sh still starts when the Vulkan device looks like Vortek does to
# "mesa zink" (vortek-sim.c: Vulkan 1.3 and Vortek's fixed extension list, taken from the vortekrenderer sources).
# lavapipe stands in for the GPU, so this only catches extensions and versions zink insists on, nothing about Mali.
# Mesa 26.2.5's zink requires VK_KHR_maintenance5 (or Vulkan 1.4), which Vortek does not offer.
# Usage: WORK=dir tools/mesa-zink/vortek-filter-check.sh [VK_KHR_extra,...]   (run smoke-x86.sh with the same WORK first)
#   The optional list exposes those host extensions too, to see what Vortek would have to add.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(realpath "${WORK:?set WORK to the build.sh work dir}")
SRC=$WORK/mesa-26.2.5
LIBGL=$WORK/build-x86/src/gallium/targets/libgl-xlib
[ -f "$LIBGL/libGL.so.1.5.0" ] || { echo "run smoke-x86.sh with the same WORK first"; exit 1; }

SIM=$WORK/vortek-sim
mkdir -p "$SIM"
python3 -I - "$HERE/../../app/src/main/cpp/vortekrenderer" "$SIM/lists.h" <<'EOF'
import re, sys
root, out = sys.argv[1], sys.argv[2]
header = open(root + "/include/vulkan_helper.h").read()
source = open(root + "/src/vulkan_helper.c").read()
exposed = re.search(r'globalExposedDeviceExtensions\[\] = \{([^}]*)\}', header).group(1)
implemented = re.search(r'globalImplementedDeviceExtensions\[\] = \{([^}]*)\}', header).group(1)
disabled = re.search(r'"mesa zink"\) == 0\) \{\s*const char\* disabledDeviceExtensions\[\] = \{([^}]*)\}', source).group(1)
with open(out, "w") as f:
    f.write("static const char *GLOBAL[] = {%s};\n" % exposed)
    f.write("static const char *IMPLEMENTED[] = {%s};\n" % implemented)
    f.write("static const char *DISABLED[] = {%s};\n" % disabled)
EOF
gcc -shared -fPIC -fvisibility=hidden -O1 -Wall -I"$SIM" -I"$SRC/include" -o "$SIM/libvortek_sim.so" "$HERE/vortek-sim.c"
cat > "$SIM/vortek_sim.json" <<EOF
{"file_format_version": "1.2.0", "layer": {"name": "VK_LAYER_TEST_vortek_sim", "type": "GLOBAL",
 "library_path": "$SIM/libvortek_sim.so", "api_version": "1.3.0", "implementation_version": "1",
 "description": "What Vortek exposes to mesa zink"}}
EOF

Xvfb :96 -screen 0 200x200x24 > "$SIM/xvfb.log" 2>&1 &
XVFB=$!
trap 'kill $XVFB 2>/dev/null' EXIT
sleep 2
export DISPLAY=:96 LD_LIBRARY_PATH=$LIBGL GALLIUM_DRIVER=zink LIBGL_ALWAYS_SOFTWARE=1
export VK_DRIVER_FILES=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1)
export VK_ICD_FILENAMES=$VK_DRIVER_FILES VK_LAYER_PATH=$SIM VK_INSTANCE_LAYERS=VK_LAYER_TEST_vortek_sim
export VORTEK_SIM_ADD=${1:-}

glxinfo -B > "$SIM/glxinfo.txt" 2>&1 || true
grep -E "OpenGL renderer|OpenGL core profile version|OpenGL version string|Error" "$SIM/glxinfo.txt" || true
if grep -q "renderer string: zink Vulkan 1.3" "$SIM/glxinfo.txt"; then
  echo "vortek filter check: OK${1:+ (with $1)}"
else
  echo "vortek filter check: FAILED, zink does not start with Vortek's extensions${1:+ plus $1}"
  exit 1
fi
