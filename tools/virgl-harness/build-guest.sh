#!/bin/bash
# Builds the VirGL guest exactly from Winlator's source (brunodev85/mesa3d-custom, virgl-23.1.9: Mesa 23.1.9 with the
# "server" winsys that talks to libvirglrenderer.so), natively for x86_64, with Bruno's meson options (README of
# that repo) minus the Android prefix. Needs gcc, ninja, python3 with venv, flex, bison, libx11-dev, libxext-dev,
# libxfixes-dev, libxcb*-dev, libdrm-dev, libexpat1-dev, zlib1g-dev; meson and mako go into a venv.
# Usage: tools/virgl-harness/build-guest.sh     (WORK=/tmp/virgl-harness)
set -euo pipefail
WORK=${WORK:-/tmp/virgl-harness}
mkdir -p "$WORK"
cd "$WORK"
[ -x venv/bin/meson ] || { python3 -m venv venv && venv/bin/pip install -q "meson==1.4.1" "mako==1.3.5"; }
# The commit the measurements in the commit messages were made with; MESA3D_CUSTOM_REV=origin/HEAD for the newest
REV=${MESA3D_CUSTOM_REV:-fa711f80c79a7fc04e5cc5438542b5c3692ddcba}
if [ ! -d mesa3d-custom/virgl-23.1.9 ]; then
  git clone -q --filter=blob:none --no-checkout https://github.com/brunodev85/mesa3d-custom.git mesa3d-custom
  git -C mesa3d-custom sparse-checkout set virgl-23.1.9
  git -C mesa3d-custom checkout -q "$REV"
fi
echo "mesa3d-custom $(git -C mesa3d-custom rev-parse --short HEAD)"
# -Dgallium-drivers=virgl,swrast: swrast is softpipe in 23.1 ("softpipe" is not a valid choice there)
[ -f guest-build/build.ninja ] || PATH="$WORK/venv/bin:$PATH" venv/bin/meson setup guest-build mesa3d-custom/virgl-23.1.9 \
  -Dbuildtype=release -Dgallium-va=disabled -Dgallium-drivers=virgl,swrast -Ddri3=disabled -Dllvm=disabled \
  -Dshared-llvm=disabled -Dvulkan-drivers= -Dglx=xlib -Dplatforms=x11 -Dlmsensors=disabled -Dgbm=disabled \
  -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=disabled >guest-setup.log
PATH="$WORK/venv/bin:$PATH" ninja -C guest-build -j"$(nproc)" >guest-build.log
ls -l guest-build/src/gallium/targets/libgl-xlib/libGL.so.1
