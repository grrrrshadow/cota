package com.winlator.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;

// From Android 12 on, ApplicationExitInfo hands an app the tombstone of its native crash as protobuf
// (system/core/debuggerd/proto/tombstone.proto). This decodes the parts that say where and why it crashed,
// without a protobuf library, and keeps whatever it got when the data ends early.
public class Tombstone {
    public static class Frame {
        public long relPc;
        public String functionName = "";
        public long functionOffset;
        public String fileName = "";
        public String buildId = "";
    }

    public static class Thread {
        public int id;
        public String name = "";
        public final ArrayList<Frame> backtrace = new ArrayList<>();
        public final ArrayList<String> notes = new ArrayList<>();
    }

    public static class LogMessage {
        public String timestamp = "";
        public int tid;
        public int priority;
        public String tag = "";
        public String message = "";
    }

    public static final int MAX_FRAMES = 64;
    public static final int MAX_LOG_LINES = 40;
    private static final String[] ARCH_NAMES = {"arm", "arm64", "x86", "x86_64", "riscv64", "none"};

    public int arch;
    public String buildFingerprint = "";
    public String timestamp = "";
    public int pid;
    public int tid;
    public int uid;
    public final ArrayList<String> commandLine = new ArrayList<>();
    public int processUptime;
    public boolean hasSignal;
    public int signalNumber;
    public String signalName = "";
    public int signalCode;
    public String signalCodeName = "";
    public boolean hasSender;
    public int senderUid;
    public int senderPid;
    public boolean hasFaultAddress;
    public long faultAddress;
    public String abortMessage = "";
    public final ArrayList<String> causes = new ArrayList<>();
    public final LinkedHashMap<Integer, Thread> threads = new LinkedHashMap<>();
    public final ArrayList<LogMessage> logs = new ArrayList<>();
    public int memoryMappingCount;
    public String error;

    private static class Reader {
        private final byte[] data;
        private int pos;
        private final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new EOFException("data ends inside a number");
                byte b = data[pos++];
                result |= (long)(b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("bad number at byte "+pos);
        }

        int length() throws IOException {
            long length = varint();
            if (length < 0 || length > end - pos) throw new EOFException("data ends inside a field");
            return (int)length;
        }

        Reader message() throws IOException {
            int length = length();
            Reader reader = new Reader(data, pos, pos + length);
            pos += length;
            return reader;
        }

        String string() throws IOException {
            int length = length();
            String string = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return string;
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: advance(length()); break;
                case 5: advance(4); break;
                default: throw new IOException("not protobuf (wire type "+wireType+" at byte "+pos+")");
            }
        }

        private void advance(int count) throws IOException {
            if (count > end - pos) throw new EOFException("data ends inside a field");
            pos += count;
        }
    }

    public static Tombstone parse(byte[] data, int length) {
        Tombstone tombstone = new Tombstone();
        Reader reader = new Reader(data, 0, Math.min(length, data.length));
        try {
            while (reader.hasMore()) {
                long key = reader.varint();
                int field = (int)(key >>> 3);
                int wireType = (int)(key & 7);
                if (wireType == 0) {
                    long value = reader.varint();
                    switch (field) {
                        case 1: tombstone.arch = (int)value; break;
                        case 5: tombstone.pid = (int)value; break;
                        case 6: tombstone.tid = (int)value; break;
                        case 7: tombstone.uid = (int)value; break;
                        case 20: tombstone.processUptime = (int)value; break;
                    }
                }
                else if (wireType == 2) {
                    switch (field) {
                        case 2: tombstone.buildFingerprint = reader.string(); break;
                        case 4: tombstone.timestamp = reader.string(); break;
                        case 9: tombstone.commandLine.add(reader.string()); break;
                        case 10: tombstone.parseSignal(reader.message()); break;
                        case 14: tombstone.abortMessage = reader.string(); break;
                        case 15: tombstone.parseCause(reader.message()); break;
                        case 16: tombstone.parseThreadEntry(reader.message()); break;
                        case 17: reader.skip(wireType); tombstone.memoryMappingCount++; break;
                        case 18: tombstone.parseLogBuffer(reader.message()); break;
                        default: reader.skip(wireType); break;
                    }
                }
                else reader.skip(wireType);
            }
        }
        catch (IOException | RuntimeException e) {
            tombstone.error = e.getMessage();
        }
        if (tombstone.error == null && !tombstone.hasSignal && tombstone.threads.isEmpty()) tombstone.error = "no signal and no threads";
        return tombstone;
    }

    // Something worth showing came out of it, even if the data ended early.
    public boolean isUsable() {
        return hasSignal || !threads.isEmpty();
    }

    private void parseSignal(Reader reader) throws IOException {
        hasSignal = true;
        while (reader.hasMore()) {
            long key = reader.varint();
            int field = (int)(key >>> 3);
            int wireType = (int)(key & 7);
            if (wireType == 0) {
                long value = reader.varint();
                switch (field) {
                    case 1: signalNumber = (int)value; break;
                    case 3: signalCode = (int)value; break;
                    case 5: hasSender = value != 0; break;
                    case 6: senderUid = (int)value; break;
                    case 7: senderPid = (int)value; break;
                    case 8: hasFaultAddress = value != 0; break;
                    case 9: faultAddress = value; break;
                }
            }
            else if (wireType == 2 && field == 2) signalName = reader.string();
            else if (wireType == 2 && field == 4) signalCodeName = reader.string();
            else reader.skip(wireType);
        }
    }

    private void parseCause(Reader reader) throws IOException {
        while (reader.hasMore()) {
            long key = reader.varint();
            if (key == ((1 << 3) | 2)) causes.add(reader.string());
            else reader.skip((int)(key & 7));
        }
    }

    // map<uint32, Thread> goes over the wire as repeated {1: key, 2: value}.
    private void parseThreadEntry(Reader reader) throws IOException {
        Thread thread = null;
        int threadKey = 0;
        while (reader.hasMore()) {
            long key = reader.varint();
            int field = (int)(key >>> 3);
            int wireType = (int)(key & 7);
            if (field == 1 && wireType == 0) threadKey = (int)reader.varint();
            else if (field == 2 && wireType == 2) thread = parseThread(reader.message());
            else reader.skip(wireType);
        }
        if (thread != null) threads.put(thread.id != 0 ? thread.id : threadKey, thread);
    }

    private static Thread parseThread(Reader reader) throws IOException {
        Thread thread = new Thread();
        while (reader.hasMore()) {
            long key = reader.varint();
            int field = (int)(key >>> 3);
            int wireType = (int)(key & 7);
            if (field == 1 && wireType == 0) thread.id = (int)reader.varint();
            else if (field == 2 && wireType == 2) thread.name = reader.string();
            else if (field == 4 && wireType == 2) {
                Reader frameReader = reader.message();
                if (thread.backtrace.size() < MAX_FRAMES) thread.backtrace.add(parseFrame(frameReader));
            }
            else if (field == 7 && wireType == 2) thread.notes.add(reader.string());
            else reader.skip(wireType);
        }
        return thread;
    }

    private static Frame parseFrame(Reader reader) throws IOException {
        Frame frame = new Frame();
        while (reader.hasMore()) {
            long key = reader.varint();
            int field = (int)(key >>> 3);
            int wireType = (int)(key & 7);
            if (wireType == 0) {
                long value = reader.varint();
                if (field == 1) frame.relPc = value;
                else if (field == 5) frame.functionOffset = value;
            }
            else if (wireType == 2 && field == 4) frame.functionName = reader.string();
            else if (wireType == 2 && field == 6) frame.fileName = reader.string();
            else if (wireType == 2 && field == 8) frame.buildId = reader.string();
            else reader.skip(wireType);
        }
        return frame;
    }

    private void parseLogBuffer(Reader reader) throws IOException {
        while (reader.hasMore()) {
            long key = reader.varint();
            if (key == ((2 << 3) | 2)) {
                logs.add(parseLogMessage(reader.message()));
                if (logs.size() > 4 * MAX_LOG_LINES) logs.subList(0, logs.size() - MAX_LOG_LINES).clear();
            }
            else reader.skip((int)(key & 7));
        }
    }

    private static LogMessage parseLogMessage(Reader reader) throws IOException {
        LogMessage message = new LogMessage();
        while (reader.hasMore()) {
            long key = reader.varint();
            int field = (int)(key >>> 3);
            int wireType = (int)(key & 7);
            if (wireType == 0) {
                long value = reader.varint();
                if (field == 3) message.tid = (int)value;
                else if (field == 4) message.priority = (int)value;
            }
            else if (wireType == 2 && field == 1) message.timestamp = reader.string();
            else if (wireType == 2 && field == 5) message.tag = reader.string();
            else if (wireType == 2 && field == 6) message.message = reader.string();
            else reader.skip(wireType);
        }
        return message;
    }

    public Thread getCrashingThread() {
        Thread thread = threads.get(tid);
        if (thread == null && !threads.isEmpty()) thread = threads.values().iterator().next();
        return thread;
    }

    private boolean is64Bit() {
        return arch == 1 || arch == 3 || arch == 4;
    }

    private String hex(long value) {
        return String.format(Locale.ENGLISH, is64Bit() ? "%016x" : "%08x", value);
    }

    private String signalText() {
        String text = "signal "+signalNumber+" ("+(signalName.isEmpty() ? "?" : signalName)+"), code "+signalCode+" ("+(signalCodeName.isEmpty() ? "?" : signalCodeName)+")";
        if (hasSender) text += " from pid "+senderPid+", uid "+senderUid;
        return text + ", fault addr "+(hasFaultAddress ? "0x"+hex(faultAddress) : "--------");
    }

    public static String getLibraryName(Frame frame) {
        String fileName = frame.fileName.isEmpty() ? "<unknown>" : frame.fileName;
        return fileName.substring(fileName.lastIndexOf('/') + 1);
    }

    private static String functionText(Frame frame) {
        return frame.functionName.isEmpty() ? "" : " ("+frame.functionName+"+"+frame.functionOffset+")";
    }

    private static String shortFrameText(Frame frame) {
        return getLibraryName(frame) + functionText(frame);
    }

    // Where it crashed in one line, for the session log: the signal, then the top frame and, when that is
    // inside libc (abort, memcpy...), the first frame outside it, which is usually the one to blame.
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (hasSignal) sb.append(signalText());
        Thread thread = getCrashingThread();
        if (thread != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("thread ").append(thread.id).append(" '").append(thread.name).append("'");
            if (!thread.backtrace.isEmpty()) {
                Frame top = thread.backtrace.get(0);
                sb.append(" in ").append(shortFrameText(top));
                if (getLibraryName(top).equals("libc.so")) {
                    for (Frame frame : thread.backtrace) {
                        if (!getLibraryName(frame).equals("libc.so")) {
                            sb.append(" called from ").append(shortFrameText(frame));
                            break;
                        }
                    }
                }
            }
        }
        if (!abortMessage.isEmpty()) sb.append(", abort: '").append(cut(abortMessage.replace('\n', ' '), 200)).append("'");
        if (!causes.isEmpty()) sb.append(", cause: ").append(causes.get(0));
        return sb.toString();
    }

    // Laid out like the tombstone text in /data/tombstones, cut to maxChars:
    // the crashing thread first, then the app's last log lines, then the top frame of every other thread.
    public String toText(int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (!buildFingerprint.isEmpty()) sb.append("Build fingerprint: '").append(buildFingerprint).append("'\n");
        sb.append("ABI: '").append(arch >= 0 && arch < ARCH_NAMES.length ? ARCH_NAMES[arch] : String.valueOf(arch)).append("'\n");
        if (!timestamp.isEmpty()) sb.append("Timestamp: ").append(timestamp).append("\n");
        if (processUptime > 0) sb.append("Process uptime: ").append(processUptime).append("s\n");
        if (!commandLine.isEmpty()) sb.append("Cmdline: ").append(String.join(" ", commandLine)).append("\n");

        Thread crashingThread = getCrashingThread();
        sb.append("pid: ").append(pid).append(", tid: ").append(tid).append(", name: ").append(crashingThread != null ? crashingThread.name : "?");
        if (!commandLine.isEmpty()) sb.append("  >>> ").append(commandLine.get(0)).append(" <<<");
        sb.append("\n");
        if (uid != 0) sb.append("uid: ").append(uid).append("\n");
        if (hasSignal) sb.append(signalText()).append("\n");
        for (String cause : causes) sb.append("Cause: ").append(cause).append("\n");
        if (!abortMessage.isEmpty()) sb.append("Abort message: '").append(cut(abortMessage, 2000)).append("'\n");

        if (crashingThread != null) {
            for (String note : crashingThread.notes) sb.append("  ").append(note).append("\n");
            sb.append("backtrace:\n");
            appendBacktrace(sb, crashingThread);
        }

        if (!logs.isEmpty()) {
            // One list per log buffer (main, system...); "MM-DD HH:MM:SS.mmm" sorts as text.
            ArrayList<LogMessage> sortedLogs = new ArrayList<>(logs);
            Collections.sort(sortedLogs, (a, b) -> a.timestamp.compareTo(b.timestamp));
            int first = Math.max(0, sortedLogs.size() - MAX_LOG_LINES);
            sb.append("log (last ").append(sortedLogs.size() - first).append(" lines of this process):\n");
            for (int i = first; i < sortedLogs.size(); i++) {
                LogMessage log = sortedLogs.get(i);
                sb.append("  ").append(log.timestamp).append(' ').append(log.tid).append(' ').append(priorityLetter(log.priority))
                  .append(' ').append(log.tag).append(": ").append(cut(log.message.replace('\n', ' '), 500)).append("\n");
            }
        }

        if (threads.size() > 1) {
            sb.append("other threads (").append(threads.size() - 1).append("):\n");
            for (Thread thread : threads.values()) {
                if (thread == crashingThread) continue;
                sb.append("  ").append(thread.id).append(" '").append(thread.name).append("'");
                if (!thread.backtrace.isEmpty()) sb.append(": ").append(shortFrameText(thread.backtrace.get(0)));
                sb.append("\n");
                if (sb.length() > maxChars) break;
            }
        }
        if (memoryMappingCount > 0) sb.append("memory mappings: ").append(memoryMappingCount).append(" (not shown)\n");
        if (error != null) sb.append("(decoding stopped: ").append(error).append(")\n");
        return cutText(sb.toString(), maxChars);
    }

    private void appendBacktrace(StringBuilder sb, Thread thread) {
        for (int i = 0; i < thread.backtrace.size(); i++) {
            Frame frame = thread.backtrace.get(i);
            sb.append(String.format(Locale.ENGLISH, "      #%02d pc ", i)).append(hex(frame.relPc)).append("  ")
              .append(frame.fileName.isEmpty() ? "<unknown>" : frame.fileName).append(functionText(frame));
            // The build ID tells which build of our own libraries to symbolize against; system ones need no help.
            if (!frame.buildId.isEmpty() && frame.fileName.startsWith("/data/")) sb.append(" (BuildId: ").append(frame.buildId).append(")");
            sb.append("\n");
        }
        if (thread.backtrace.size() >= MAX_FRAMES) sb.append("      ... (only the first ").append(MAX_FRAMES).append(" frames)\n");
    }

    private static char priorityLetter(int priority) {
        String letters = "??VDIWEFS";
        return priority >= 0 && priority < letters.length() ? letters.charAt(priority) : '?';
    }

    private static String cut(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars)+"..." : text;
    }

    public static String cutText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int end = text.lastIndexOf('\n', maxChars);
        if (end < maxChars / 2) end = maxChars;
        return text.substring(0, end)+"\n... (cut at "+(maxChars >= 1024 ? (maxChars / 1024)+" KB" : maxChars+" characters")+")\n";
    }

    // When the data is not a tombstone after all: the runs of readable text in it, which still name libraries and messages.
    public static String readableStrings(byte[] data, int length, int minLength, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int start = -1;
        int end = Math.min(length, data.length);
        for (int i = 0; i <= end; i++) {
            boolean printable = i < end && data[i] >= 0x20 && data[i] < 0x7f;
            if (printable) {
                if (start < 0) start = i;
            }
            else if (start >= 0) {
                if (i - start >= minLength) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(new String(data, start, i - start, StandardCharsets.US_ASCII));
                    if (sb.length() >= maxChars) break;
                }
                start = -1;
            }
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars)+"..." : sb.toString();
    }
}
