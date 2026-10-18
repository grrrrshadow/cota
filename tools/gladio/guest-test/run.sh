#!/bin/bash
# Runs the guest libGL under qemu-aarch64 against fake_host.c, which speaks the Gladio protocol
# (X11 setup, GLX opcodes, shared memory rings) and logs the GL requests it gets.
#
#   tools/gladio/guest-test/run.sh LIBGL [GLADIO_SRC]
#
# LIBGL is a guest libGL.so.1.7.0 (for example the one in gladio-1.2.tzst), GLADIO_SRC the
# gladio source tree for the GL headers (default: the tree build-guest.sh left in /tmp/gladio-guest).
# Needs gcc, aarch64-linux-gnu-gcc, qemu-aarch64 and the X11 headers.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CPP="$ROOT/app/src/main/cpp"
LIBGL=$(realpath "$1")
GLADIO_SRC=${2:-/tmp/gladio-guest/gladio}
WORK=$(mktemp -d /tmp/gladio-guest-test.XXXXXX)
HOST_PID=
trap '[ -n "$HOST_PID" ] && kill $HOST_PID 2>/dev/null; rm -rf "$WORK"' EXIT

gcc -O1 -Wall -Wno-unused-result -I"$CPP/winlator/include" -I"$CPP/gladiorenderer/include" \
    "$HERE/fake_host.c" "$CPP/winlator/src/ring_buffer.c" -lpthread -o "$WORK/fake_host" || exit 1
mkdir -p "$WORK/xinclude"
ln -s /usr/include/X11 "$WORK/xinclude/X11"
aarch64-linux-gnu-gcc -O1 -Wall -I"$GLADIO_SRC/include" -isystem "$WORK/xinclude" "$HERE/guest_test.c" \
    -L"$(dirname "$LIBGL")" -l:"$(basename "$LIBGL")" -o "$WORK/guest_test" || exit 1

# The library connects to the X server at a fixed path, swap it for one of the same length
ORIGINAL=/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0
SOCKET_DIR="$WORK/x"
SOCKET="$SOCKET_DIR/$(printf '%*s' $((${#ORIGINAL} - ${#SOCKET_DIR} - 1)) '' | tr ' ' 'X')"
[ ${#SOCKET} -eq ${#ORIGINAL} ] || { echo "work dir path too long for the socket"; exit 1; }
mkdir -p "$SOCKET_DIR" "$WORK/lib"
python3 -I -c 'import sys; data = open(sys.argv[1], "rb").read(); assert data.count(sys.argv[2].encode()) == 1, "X11 socket path not found"; open(sys.argv[4], "wb").write(data.replace(sys.argv[2].encode(), sys.argv[3].encode()))' \
    "$LIBGL" "$ORIGINAL" "$SOCKET" "$WORK/lib/libGL.so" || exit 1

# The old library crashes on purpose here, no core files please
ulimit -c 0

failed=0
expect() {
    if grep -qE "$2" "$WORK/$1.host"; then echo "PASS host got: $3"; else echo "FAIL host did not get: $3"; failed=1; fi
}
expect_not() {
    if grep -qE "$2" "$WORK/$1.host"; then echo "FAIL host got: $3"; failed=1; else echo "PASS host did not get: $3"; fi
}

for test in newcontext upload readback copybuffers persistent packbuffer; do
    echo "== $test"
    "$WORK/fake_host" "$SOCKET" > "$WORK/$test.host" 2>&1 &
    HOST_PID=$!
    for i in $(seq 50); do grep -q listening "$WORK/$test.host" 2>/dev/null && break; sleep 0.1; done

    timeout 60 qemu-aarch64 -L /usr/aarch64-linux-gnu -E LD_LIBRARY_PATH="$WORK/lib" "$WORK/guest_test" $test > "$WORK/$test.guest" 2>&1
    status=$?
    cat "$WORK/$test.guest"
    kill $HOST_PID 2>/dev/null
    wait $HOST_PID 2>/dev/null
    HOST_PID=
    if [ $status -ne 0 ] || ! grep -q "^DONE $test" "$WORK/$test.guest"; then
        echo "FAIL guest exited with status $status"
        failed=1
    fi

    case $test in
        upload)
            expect $test "TEX_SUB_IMAGE2D 5x3 format=0x1903 type=0x1401 size=21 data=000102030405060708090a0b0c0d0e0f1011121314$" "5x3 R8 rows padded to 4 bytes (21 bytes)"
            expect $test "TEX_SUB_IMAGE2D 5x3 format=0x1903 type=0x1401 size=15 data=000102030405060708090a0b0c0d0e$" "5x3 R8 with alignment 1 (15 bytes)"
            expect $test "TEX_SUB_IMAGE2D 2x2 format=0x1908 type=0x1401 size=76 " "2x2 RGBA, row length 8, skip 1/1 (76 bytes)"
            expect $test "PIXEL_STOREI pname=0xcf5 param=1" "GL_UNPACK_ALIGNMENT forwarded";;
        copybuffers)
            expect_not $test "BUFFER_STORAGE target=0x8f3[67]" "storage for a copy read/write target"
            expect $test "BUFFER_STORAGE target=0x8892 size=16" "array buffer storage after the copy targets";;
        persistent)
            expect $test "FLUSH_MAPPED_BUFFER_RANGE target=0x8892 offset=0 length=48" "flush of the 3 indexed vertices"
            expect $test "DRAW_ELEMENTS mode=4 count=3" "the indexed draw";;
        packbuffer)
            expect $test "READ_PIXELS 2x1 into pack buffer offset=32" "readback into the pack buffer at offset 32";;
    esac
done

[ $failed -eq 0 ] && echo "all guest tests passed" || echo "some guest tests failed"
exit $failed
