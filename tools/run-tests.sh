#!/bin/bash
# Runs the JVM unit tests (Robolectric) without building the APK.
# Usage: tools/run-tests.sh [extra gradle args, e.g. --tests '*VirGL*']
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
SDK=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
export ANDROID_HOME=${ANDROID_HOME:-${SDK:-/home/user/android-sdk}}
# Maven Central sometimes answers 429 on a cold cache; retry a few times.
for i in 1 2 3; do
  if bash gradlew --no-daemon --max-workers=2 testDebugUnitTest -i "$@" > build/test-run.log 2>&1; then
    grep -E "STANDARD_OUT|^    |Tests? |PASSED|FAILED|BUILD" build/test-run.log | grep -v "^\s*$" | tail -60
    exit 0
  fi
  grep -q " 429" build/test-run.log || break
  sleep $((10 * i))
done
grep -E "FAILED|Exception|expected|AssertionError|error:|BUILD" build/test-run.log | head -60
exit 1
