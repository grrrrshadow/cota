#!/bin/bash
# Checks that a guest libGL works in the app's rootfs: every NEEDED library is in rootfs.tzst, glibc symbol
# versions stay <= 2.39, every undefined symbol resolves against the rootfs libraries, no build-host paths.
# Usage: tools/mesa-zink/check.sh path/to/libGL.so.1.7.0 [rootfs.tzst]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
LIB=$(realpath "$1")
ROOTFS=$(realpath "${2:-$HERE/../../app/src/main/assets/rootfs.tzst}")
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
fail=0

needed=$(aarch64-linux-gnu-readelf -d "$LIB" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
zstd -dc "$ROOTFS" | tar -t > "$TMP/list"
mkdir "$TMP/root"
for so in $needed; do
  if ! grep -qx "\./usr/lib/$so" "$TMP/list"; then echo "NEEDED $so: not in rootfs"; fail=1; fi
done
# Unpack the needed libraries (and what their symlinks point to) to resolve symbols against them.
patterns=()
for so in $needed; do patterns+=("./usr/lib/$so*"); done
zstd -dc "$ROOTFS" | tar -x -C "$TMP/root" --wildcards "${patterns[@]}" 2>/dev/null || true

max=$(aarch64-linux-gnu-objdump -T "$LIB" | grep -o "GLIBC_2\.[0-9]*" | sort -uV | tail -1)
echo "NEEDED: $(echo $needed)"
echo "highest glibc symbol version: $max"
if [ "$(printf '%s\nGLIBC_2.39\n' "$max" | sort -V | tail -1)" != "GLIBC_2.39" ]; then echo "needs newer glibc than 2.39"; fail=1; fi

for f in "$TMP"/root/usr/lib/*; do
  [ -L "$f" ] || aarch64-linux-gnu-nm -D --defined-only --with-symbol-versions "$f" 2>/dev/null | awk '{print $3}'
done | sed 's/@@/@/' | sort -u > "$TMP/defined"
aarch64-linux-gnu-nm -D --undefined-only --with-symbol-versions "$LIB" | awk '$1 == "U" {print $2}' | sort -u > "$TMP/undefined"
missing=0
while read -r sym; do
  if [[ $sym == *@* ]]; then grep -qxF "$sym" "$TMP/defined" && continue
  else grep -q "^$sym\(@\|$\)" "$TMP/defined" && continue; fi
  echo "unresolved: $sym"; missing=$((missing + 1))
done < "$TMP/undefined"
echo "undefined symbols: $(wc -l < "$TMP/undefined"), unresolved: $missing"
[ "$missing" = 0 ] || fail=1

if strings -a "$LIB" | grep -E "/home/|/tmp/claude|scratchpad|/root/|/build/"; then echo "build-host paths inside"; fail=1; fi

[ "$fail" = 0 ] && echo "rootfs check: OK" || { echo "rootfs check: FAILED"; exit 1; }
