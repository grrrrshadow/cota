#!/bin/bash
# Compares VirGL hosts from several commits: builds each commit's app/src/main/cpp/virglrenderer with
# build-host.sh and runs run-bench.sh for them in turn, ROUNDS times, so background load hits all alike.
# Run build-guest.sh first. Usage: tools/virgl-harness/compare.sh COMMIT...   (ROUNDS=3 WORK=/tmp/virgl-harness)
# e.g. tools/virgl-harness/compare.sh bebfcc1 HEAD
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
export WORK=${WORK:-/tmp/virgl-harness}
ROUNDS=${ROUNDS:-3}

for c in "$@"; do
  id=$(git -C "$REPO" rev-parse --short "$c")
  rm -rf "$WORK/src-$id" && mkdir -p "$WORK/src-$id"
  git -C "$REPO" archive "$id" app/src/main/cpp/virglrenderer | tar -x -C "$WORK/src-$id"
  "$HERE/build-host.sh" "$WORK/server-$id" "$WORK/src-$id/app/src/main/cpp/virglrenderer" >/dev/null
done

for i in $(seq "$ROUNDS"); do
  for c in "$@"; do
    id=$(git -C "$REPO" rev-parse --short "$c")
    echo "=== $id round $i, load $(cut -d' ' -f1 /proc/loadavg)"
    "$HERE/run-bench.sh" "$WORK/server-$id" "$id-$i" | grep -E "^glxgears|^gl33test: harness|^dump|^two|^readback"
  done
done
