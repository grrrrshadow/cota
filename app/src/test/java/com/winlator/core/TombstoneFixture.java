package com.winlator.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// Writes tombstones in the wire format of system/core/debuggerd/proto/tombstone.proto, field numbers as there.
class TombstoneFixture {
    static class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long value) {
            key(field, 0);
            raw(value);
            return this;
        }

        Proto fixed64(int field, long value) {
            key(field, 1);
            for (int i = 0; i < 8; i++) out.write((int)(value >>> (8 * i)) & 0xff);
            return this;
        }

        Proto string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto message(int field, Proto message) {
            return bytes(field, message.toByteArray());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private Proto bytes(int field, byte[] value) {
            key(field, 2);
            raw(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        private void key(int field, int wireType) {
            raw(((long)field << 3) | wireType);
        }

        private void raw(long value) {
            while ((value & ~0x7fL) != 0) {
                out.write((int)((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            out.write((int)value);
        }
    }

    static Proto frame(long relPc, String function, long offset, String file, String buildId) {
        return new Proto().varint(1, relPc).varint(2, 0x7f00000000L + relPc).varint(3, 0x7ff0000000L)
            .string(4, function).varint(5, offset).string(6, file).varint(7, 0).string(8, buildId);
    }

    static Proto threadEntry(int tid, String name, Proto... frames) {
        Proto thread = new Proto().varint(1, tid).string(2, name)
            .message(3, new Proto().string(1, "x0").varint(2, 0))
            .varint(6, 0);
        for (Proto frame : frames) thread.message(4, frame);
        return new Proto().varint(1, tid).message(2, thread);
    }

    static final String VIRGL_LIB = "/data/app/~~abc==/org.winlator-def==/lib/arm64/libvirglrenderer.so";
    static final String LIBC = "/apex/com.android.runtime/lib64/bionic/libc.so";

    // A SIGSEGV in memcpy called from the VirGL server thread, the way debuggerd writes it on an arm64 phone.
    static byte[] virglSegv() {
        Proto signal = new Proto().varint(1, 11).string(2, "SIGSEGV").varint(3, 1).string(4, "SEGV_MAPERR")
            .varint(8, 1).varint(9, 0x10)
            .message(10, new Proto().string(1, "x1").varint(3, 0));
        Proto log = new Proto().string(1, "main")
            .message(2, new Proto().string(1, "10-25 14:03:11.800").varint(2, 12345).varint(3, 12400).varint(4, 4).string(5, "VirGL").string(6, "context 3 created"))
            .message(2, new Proto().string(1, "10-25 14:03:11.900").varint(2, 12345).varint(3, 12400).varint(4, 6).string(5, "virgl").string(6, "transfer failed\nsecond line"));
        return new Proto()
            .varint(1, 1)
            .string(2, "motorola/fogona/fogona:15/V1UI35H.12-34/abc:user/release-keys")
            .string(3, "0")
            .string(4, "2026-10-25 14:03:12.123456789+0000")
            .varint(5, 12345).varint(6, 12400).varint(7, 10234)
            .string(8, "u:r:untrusted_app:s0")
            .string(9, "org.winlator")
            .message(10, signal)
            .message(15, new Proto().string(1, "null pointer dereference"))
            .message(16, threadEntry(12345, "main", frame(0x9a0f8, "__epoll_pwait", 8, LIBC, "1111")))
            .message(16, threadEntry(12400, "VirGLServer",
                frame(0x4f8b8, "memcpy", 40, LIBC, "1111"),
                frame(0x1234, "vrend_renderer_transfer_iov", 120, VIRGL_LIB, "deadbeef"),
                frame(0x5678, "", 0, VIRGL_LIB, "deadbeef")))
            .message(17, new Proto().varint(1, 0x1000).varint(2, 0x2000).string(7, LIBC))
            .message(17, new Proto().varint(1, 0x3000).varint(2, 0x4000).string(7, VIRGL_LIB))
            .message(18, log)
            .varint(20, 812)
            .varint(22, 4096)
            .fixed64(30, 42)
            .toByteArray();
    }
}
