package com.winlator.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// How a process death that Android recorded (ApplicationExitInfo, Android 11+) is chosen and written up for
// crash.txt and the session log. Free of Android classes so the JVM tests can check it directly.
public final class ExitReport {
    // The values of ApplicationExitInfo.REASON_*, including ones newer than our compile SDK might know.
    public static final int REASON_UNKNOWN = 0;
    public static final int REASON_EXIT_SELF = 1;
    public static final int REASON_SIGNALED = 2;
    public static final int REASON_LOW_MEMORY = 3;
    public static final int REASON_CRASH = 4;
    public static final int REASON_CRASH_NATIVE = 5;
    public static final int REASON_ANR = 6;
    public static final int REASON_INITIALIZATION_FAILURE = 7;
    public static final int REASON_PERMISSION_CHANGE = 8;
    public static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
    public static final int REASON_USER_REQUESTED = 10;
    public static final int REASON_USER_STOPPED = 11;
    public static final int REASON_DEPENDENCY_DIED = 12;
    public static final int REASON_OTHER = 13;
    public static final int REASON_FREEZER = 14;
    public static final int REASON_PACKAGE_STATE_CHANGE = 15;
    public static final int REASON_PACKAGE_UPDATED = 16;

    private static final String[] REASON_NAMES = {
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR", "INITIALIZATION_FAILURE",
        "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED", "OTHER",
        "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"
    };

    private static final String[] SIGNAL_NAMES = {
        null, "SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGABRT", "SIGBUS", "SIGFPE", "SIGKILL", "SIGUSR1",
        "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP", "SIGTSTP",
        "SIGTTIN", "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH", "SIGIO", "SIGPWR", "SIGSYS"
    };

    // What the process said it was doing (ActivityManager.setProcessStateSummary) when our own restart path exits it.
    public static final String STATE_RESTART = "restart";

    public static class Entry {
        public long timestamp;
        public int pid;
        public String processName;
        public int reason;
        public int status;
        public int importance;
        public long pssKB;
        public long rssKB;
        public String description;
        public String state;
    }

    private ExitReport() {}

    // The deaths not reported yet, oldest first.
    public static List<Entry> selectNew(List<Entry> entries, long lastReportedTimestamp) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.timestamp > lastReportedTimestamp) result.add(entry);
        }
        Collections.sort(result, Comparator.comparingLong(entry -> entry.timestamp));
        return result;
    }

    // Leaving a session restarts the app with System.exit(0); that is the normal end of every session, not news.
    // Without a recorded state (it is only kept from Android 11 on anyway) a clean exit(0) can only have been ours.
    public static boolean isOwnRestart(Entry entry) {
        if (entry.reason != REASON_EXIT_SELF || entry.status != 0) return false;
        return entry.state == null || entry.state.isEmpty() || entry.state.startsWith(STATE_RESTART);
    }

    public static String reasonName(int reason) {
        return reason >= 0 && reason < REASON_NAMES.length ? REASON_NAMES[reason] : "REASON_"+reason;
    }

    public static String signalName(int signal) {
        return signal > 0 && signal < SIGNAL_NAMES.length ? SIGNAL_NAMES[signal] : "signal "+signal;
    }

    // ActivityManager.RunningAppProcessInfo.IMPORTANCE_*
    public static String importanceName(int importance) {
        String name;
        switch (importance) {
            case 100: name = "foreground"; break;
            case 125: name = "foreground service"; break;
            case 150: case 325: name = "top sleeping"; break;
            case 200: name = "visible"; break;
            case 230: name = "perceptible"; break;
            case 300: name = "service"; break;
            case 350: name = "can't save state"; break;
            case 400: name = "cached"; break;
            case 1000: name = "gone"; break;
            default: name = "importance"; break;
        }
        return name+" ("+importance+")";
    }

    // getStatus() is the exit code for EXIT_SELF and the signal for SIGNALED; native crashes and lmkd kills carry the signal too.
    public static String statusText(Entry entry) {
        if (entry.reason == REASON_EXIT_SELF) return "exit status "+entry.status;
        boolean signal = entry.reason == REASON_SIGNALED || entry.reason == REASON_CRASH_NATIVE || entry.reason == REASON_LOW_MEMORY;
        if (signal && entry.status > 0 && entry.status < 65) return "signal "+entry.status+" ("+signalName(entry.status)+")";
        return entry.status != 0 ? "status "+entry.status : "";
    }

    public static String headline(Entry entry) {
        String status = statusText(entry);
        return reasonName(entry.reason)+(status.isEmpty() ? "" : ", "+status);
    }

    private static String memoryText(Entry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.pssKB > 0) sb.append("PSS ").append(entry.pssKB / 1024).append(" MB");
        if (entry.rssKB > 0) sb.append(sb.length() > 0 ? ", " : "").append("RSS ").append(entry.rssKB / 1024).append(" MB");
        return sb.toString();
    }

    public static String formatTime(long timestamp, TimeZone timeZone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        format.setTimeZone(timeZone);
        return format.format(new Date(timestamp));
    }

    // One block of crash.txt. traceHeader/trace may be null; reporter says which build and device wrote it,
    // since that is the build running now, not necessarily the one that died.
    public static String format(Entry entry, String traceHeader, String trace, String reporter, TimeZone timeZone) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(formatTime(entry.timestamp, timeZone)).append("  process ended: ").append(headline(entry)).append(" ===\n");
        sb.append("Process: ").append(entry.processName != null ? entry.processName : "?").append(", pid ").append(entry.pid)
          .append(", ").append(importanceName(entry.importance));
        String memory = memoryText(entry);
        if (!memory.isEmpty()) sb.append(", ").append(memory);
        sb.append("\n");
        if (entry.description != null && !entry.description.isEmpty()) sb.append("Description: ").append(entry.description).append("\n");
        if (entry.state != null && !entry.state.isEmpty()) sb.append("State: ").append(entry.state).append("\n");
        if (entry.reason == REASON_CRASH) sb.append("Java stack trace: in the block CrashLog wrote when it happened\n");
        if (reporter != null) sb.append("Reported by: ").append(reporter).append("\n");
        if (traceHeader != null) sb.append("Trace: ").append(traceHeader).append("\n");
        if (trace != null && !trace.isEmpty()) sb.append(trace).append(trace.endsWith("\n") ? "" : "\n");
        sb.append("\n");
        return sb.toString();
    }

    // The [DL] line at the top of the next session log. detail is the one-line tombstone/ANR summary, if any;
    // otherReported counts the other new deaths that went into crash.txt.
    public static String sessionLine(Entry entry, String detail, int otherReported, TimeZone timeZone) {
        StringBuilder sb = new StringBuilder("[DL] previous run ended: ");
        // The tombstone summary names the signal itself.
        sb.append(entry.reason == REASON_CRASH_NATIVE && detail != null ? reasonName(entry.reason) : headline(entry));
        if (entry.description != null && !entry.description.isEmpty()) sb.append(" '").append(entry.description).append("'");
        if (detail != null && !detail.isEmpty()) sb.append(", ").append(detail);
        sb.append(", at ").append(formatTime(entry.timestamp, timeZone));
        boolean ownRestart = isOwnRestart(entry);
        if (ownRestart) sb.append(" (Winlator DL restart)");
        else {
            sb.append(", ").append(importanceName(entry.importance));
            String memory = memoryText(entry);
            if (!memory.isEmpty()) sb.append(", ").append(memory);
            if (entry.state != null && !entry.state.isEmpty()) sb.append(", state '").append(entry.state).append("'");
        }
        if (otherReported > 0) sb.append("; ").append(otherReported).append(" earlier exit").append(otherReported > 1 ? "s" : "").append(" since the last report");
        if (!ownRestart || otherReported > 0) sb.append("; details in crash.txt");
        return sb.toString();
    }

    // An ANR trace starts with a "Subject:" line that says what timed out.
    public static String anrSubject(String trace) {
        if (trace == null) return null;
        for (String line : trace.split("\n", 20)) {
            if (line.startsWith("Subject:")) return line.substring(8).trim();
        }
        return null;
    }
}
