#!/bin/bash
# Builds app/src/main/assets/graphics_driver/zink-25.1.9.tzst: Mesa 25.1.9 libGL with xlib GLX and zink (+ softpipe),
# for the aarch64 glibc 2.39 rootfs. Like Winlator's zink-22.2.5 it presents by reading each frame back and
# XShmPutImage-ing it (patches/), because Vortek only offers VK_KHR_xlib_surface.
# 25.1 is the newest Mesa whose zink starts on the phone's Mali through Vortek (Vulkan 1.3, Vortek's extension list,
# no VK_EXT_robustness2): 25.2+ require robustness2's nullDescriptor and 26.2+ VK_KHR_maintenance5 (or Vulkan 1.4).
# vortek-filter-check.sh shows it.
# Needs Ubuntu 24.04 x86_64 with gcc-aarch64-linux-gnu, g++-aarch64-linux-gnu, python3-venv, bison, flex, ninja-build,
# pkg-config, dpkg-dev (dpkg-deb), curl and zstd.
# Usage: tools/mesa-zink/build.sh [OUT.tzst]   (WORK=dir keeps the download, sysroot and build tree; 5-7 min on 4 cores)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
VERSION=25.1.9
# Signed by Eric Engestrom (5755 1DE1 5B96 8F63 41C2 48F6 8D8E 31AF C324 28A6, mesa-25.1.9.tar.xz.sig).
SHA256=412df33a1bb3c785ed698555a3972118a37c458e7accf6ae53f4bb87b3db454a
OUT=$(realpath -m "${1:-$HERE/../../app/src/main/assets/graphics_driver/zink-$VERSION.tzst}")
WORK=$(realpath -m "${WORK:-$(mktemp -d)}")
mkdir -p "$WORK"

TARBALL=$WORK/mesa-$VERSION.tar.xz
if ! echo "$SHA256  $TARBALL" | sha256sum -c --status 2>/dev/null; then
  curl -fsSL -o "$TARBALL" "https://archive.mesa3d.org/mesa-$VERSION.tar.xz"
  echo "$SHA256  $TARBALL" | sha256sum -c --quiet
fi
SRC=$WORK/mesa-$VERSION
if [ ! -f "$SRC/.patched" ]; then
  rm -rf "$SRC" && tar -C "$WORK" -xf "$TARBALL"
  for p in "$HERE"/patches/*.patch; do patch -s -p1 -d "$SRC" < "$p"; done
  touch "$SRC/.patched"
fi

# Headers and link libraries for X11, libdrm, zstd and expat (glibc comes with the cross compiler).
SYSROOT=$WORK/sysroot
if [ ! -f "$SYSROOT/.done" ]; then
  python3 -I "$HERE/fetch-debs.py" "$WORK/debs" libx11-dev libxext-dev libxcb1-dev libdrm-dev zlib1g-dev libexpat1-dev \
    libzstd-dev libxfixes-dev libxcb-shm0-dev libxcb-dri3-dev libxcb-present-dev libxcb-sync-dev libxcb-xfixes0-dev \
    libxcb-glx0-dev libxcb-randr0-dev libxcb-dri2-0-dev libxshmfence-dev libxxf86vm-dev libxrandr-dev libx11-xcb-dev > "$WORK/debs.log"
  rm -rf "$SYSROOT" && mkdir -p "$SYSROOT"
  for deb in "$WORK"/debs/*.deb; do dpkg-deb -x "$deb" "$SYSROOT"; done
  touch "$SYSROOT/.done"
fi
sed "s|@SYSROOT@|$SYSROOT|g" "$HERE/aarch64-linux-gnu.cross.in" > "$WORK/aarch64-linux-gnu.cross"

# A pinned meson (Ubuntu 24.04 ships 1.3.2) and the Python modules Mesa generates code with.
if [ ! -x "$WORK/venv/bin/meson" ]; then
  python3 -m venv "$WORK/venv"
  "$WORK/venv/bin/pip" install -q meson==1.12.1 mako==1.4.3 pyyaml==6.0.3 packaging==26.3
fi
export PATH=$WORK/venv/bin:$PATH

BUILD=$WORK/build
if [ ! -f "$BUILD/build.ninja" ]; then
  meson setup "$BUILD" "$SRC" --cross-file "$WORK/aarch64-linux-gnu.cross" --buildtype=release \
    -Dprefix=/usr -Dlibdir=lib -Dplatforms=x11 -Dglx=xlib -Degl=disabled -Dgbm=disabled -Dglvnd=disabled \
    -Dgles1=disabled -Dgles2=disabled -Dgallium-drivers=softpipe,zink -Dvulkan-drivers= -Dllvm=disabled \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=enabled -Dexpat=enabled -Dtools= -Dbuild-tests=false \
    -Dvideo-codecs= > "$WORK/meson-setup.log"
fi
ninja -C "$BUILD" > "$WORK/ninja.log"
# meson install drops the build-time RUNPATH (the sysroot) from the library.
rm -rf "$WORK/install" && meson install -C "$BUILD" --destdir "$WORK/install" --quiet > "$WORK/install.log"

# Installed under the name the rootfs symlink libGL.so.1 points to (same as the other OpenGL drivers).
rm -rf "$WORK/pkg" && mkdir -p "$WORK/pkg/usr/lib"
aarch64-linux-gnu-strip --strip-unneeded -o "$WORK/pkg/usr/lib/libGL.so.1.7.0" "$WORK/install/usr/lib/libGL.so.1.5.0"
tar -C "$WORK/pkg" --owner=0 --group=0 --numeric-owner --mtime=@0 --sort=name -cf - . | zstd -19 -q -f -o "$OUT"
bash "$HERE/check.sh" "$WORK/pkg/usr/lib/libGL.so.1.7.0"
echo "$OUT"
