#!/bin/bash
# Builds TotalCommanderPortable.exe = PortableApps.com Launcher without the
# "did not close properly" message (no-crash-message.patch).
# Needs: git, makensis (Debian/Ubuntu package "nsis"), python3.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$(mktemp -d)}
UPSTREAM_COMMIT=5470210

git clone https://github.com/PortableApps/launcher "$WORK/launcher"
git -C "$WORK/launcher" checkout -q "$UPSTREAM_COMMIT"
git -C "$WORK/launcher" apply "$HERE/no-crash-message.patch"

# Linux NSIS lacks some language files the launcher needs; add them from the bundled NSIS (cp1252 -> UTF-8 BOM).
NSISDIR_MERGED="$WORK/nsis"
cp -a /usr/share/nsis/. "$NSISDIR_MERGED/"
cp --update=none "$WORK/launcher/App/NSIS/Contrib/Language files/"* "$NSISDIR_MERGED/Contrib/Language files/"
cp -r --update=none "$WORK/launcher/App/NSIS/Include/." "$NSISDIR_MERGED/Include/"
cp --update=none "$WORK/launcher/App/NSIS/Plugins/x86-unicode/"* "$NSISDIR_MERGED/Plugins/x86-unicode/"
python3 - "$NSISDIR_MERGED/Contrib/Language files" <<'PY'
import os, sys
d = sys.argv[1]
for f in os.listdir(d):
    p = os.path.join(d, f); b = open(p, 'rb').read()
    if b[:3] == b'\xef\xbb\xbf' or b[:2] in (b'\xff\xfe', b'\xfe\xff'): continue
    try: b.decode('utf-8')
    except UnicodeDecodeError: open(p, 'wb').write(b'\xef\xbb\xbf' + b.decode('cp1252', errors='replace').encode('utf-8'))
PY

PKG="$WORK/pkg"
mkdir -p "$PKG/App/AppInfo"
cp "$WORK/launcher/App/AppInfo/appicon.ico" "$PKG/App/AppInfo/appicon.ico"
cd "$WORK/launcher/Other/Source"
NSISDIR="$NSISDIR_MERGED" makensis -V2 -X"!addplugindir $WORK/launcher/Other/Source/Plugins" \
  -DPACKAGE="$PKG" -DNamePortable="Total Commander Portable" -DAppID="TotalCommanderPortable" \
  -DVersion="1.0.0.0" -DXML_ENABLED PortableApps.comLauncher.nsi
echo "Built: $PKG/TotalCommanderPortable.exe"
