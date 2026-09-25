package com.winlator.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// Rewrites the original app's data dir inside extracted files; both paths have the same length so ELF offsets stay valid.
public class DataDirPatchOutputStream extends OutputStream {
    private static final byte[] FROM = AppUtils.ORIGINAL_DATA_DIR.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TO = AppUtils.DATA_DIR.getBytes(StandardCharsets.US_ASCII);
    private final OutputStream out;
    private byte[] buffer = new byte[0];
    private int pending = 0;

    public DataDirPatchOutputStream(OutputStream out) {
        if (FROM.length != TO.length) throw new IllegalStateException("Package name length must match the original");
        this.out = out;
    }

    public static String patch(String path) {
        return path != null ? path.replace(AppUtils.ORIGINAL_DATA_DIR, AppUtils.DATA_DIR) : null;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int total = pending + len;
        if (buffer.length < total) {
            byte[] newBuffer = new byte[Math.max(total, 65536)];
            System.arraycopy(buffer, 0, newBuffer, 0, pending);
            buffer = newBuffer;
        }
        System.arraycopy(b, off, buffer, pending, len);

        int i = 0;
        int lastStart = total - FROM.length;
        while (i <= lastStart) {
            if (buffer[i] == FROM[0] && matchesAt(i)) {
                System.arraycopy(TO, 0, buffer, i, TO.length);
                i += FROM.length;
            }
            else i++;
        }

        int flushLength = Math.max(i, total - (FROM.length - 1));
        flushLength = Math.min(flushLength, total);
        out.write(buffer, 0, flushLength);
        pending = total - flushLength;
        System.arraycopy(buffer, flushLength, buffer, 0, pending);
    }

    private boolean matchesAt(int index) {
        for (int j = 1; j < FROM.length; j++) {
            if (buffer[index + j] != FROM[j]) return false;
        }
        return true;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            if (pending > 0) out.write(buffer, 0, pending);
            pending = 0;
        }
        finally {
            out.close();
        }
    }
}
