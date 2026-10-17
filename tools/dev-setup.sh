#!/bin/bash
# Sets up a Linux machine (e.g. a fresh cloud container) to build and unit-test Winlator DL.
# Usage: tools/dev-setup.sh [SDK_DIR]   (default /home/user/android-sdk)
set -euo pipefail
SDK=${1:-/home/user/android-sdk}
REPO=$(cd "$(dirname "$0")/.." && pwd)

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sS -L -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;34.0.0" "ndk;24.0.8215888" "cmake;3.22.1" > /dev/null

echo "sdk.dir=$SDK" > "$REPO/local.properties"
echo "Ready. Unit tests: tools/run-tests.sh   APK: ANDROID_HOME=$SDK bash gradlew --no-daemon assembleDebug"
