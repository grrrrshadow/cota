package com.winlator.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TimeZone;

// CrashLog only sees Java exceptions. When the native side dies (a signal in the X server, VirGL or Vortek
// threads, lmkd, an ANR kill) the app just vanishes back to the container list. Android 11+ keeps a record of
// every death of our process; at the next start the new ones go into crash.txt, with the tombstone of a native
// crash decoded, and a one-line summary goes into the next session log.
public final class ExitReasons {
    private static final String PREF_LAST_TIMESTAMP = "exit_reasons_last_timestamp";
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_TRACE_BYTES = 4 * 1024 * 1024;
    static final int MAX_TRACE_CHARS = 16 * 1024;
    private static final int MAX_STATE_BYTES = 128;

    private static final Object lock = new Object();
    private static boolean started = false;
    private static boolean finished = false;
    private static String previousRunLine;
    private static final ArrayList<Callback<String>> waiting = new ArrayList<>();

    private ExitReasons() {}

    // Once per process, from the first activity that starts; the binder call and the tombstone read stay off the UI thread.
    public static void reportPreviousExits(Context context) {
        synchronized (lock) {
            if (started) return;
            started = true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finish(null);
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            String line;
            try {
                line = report(appContext, TimeZone.getDefault());
            }
            catch (Throwable e) {
                line = "[DL] could not read why the previous run ended: "+e;
            }
            finish(line);
        }, "ExitReasons").start();
    }

    // Hands the "[DL] previous run ended" line to the session log, now or as soon as the report is done.
    public static void logPreviousRun(Callback<String> callback) {
        String line;
        synchronized (lock) {
            if (!finished) {
                waiting.add(callback);
                return;
            }
            line = previousRunLine;
        }
        if (line != null) callback.call(line);
    }

    private static void finish(String line) {
        ArrayList<Callback<String>> callbacks;
        synchronized (lock) {
            previousRunLine = line;
            finished = true;
            callbacks = new ArrayList<>(waiting);
            waiting.clear();
        }
        if (line != null) {
            for (Callback<String> callback : callbacks) callback.call(line);
        }
    }

    // Recorded with this process's death, so the report can tell our own restart from a crash and say what was running.
    public static void setState(Context context, String state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            byte[] bytes = (state+" (build "+AppUtils.getVersionCode(context)+")").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STATE_BYTES) bytes = Arrays.copyOf(bytes, MAX_STATE_BYTES);
            context.getSystemService(ActivityManager.class).setProcessStateSummary(bytes);
        }
        catch (Throwable e) {}
    }

    // Writes the deaths not reported yet to crash.txt and returns the line for the session log (null when there is nothing new).
    @RequiresApi(Build.VERSION_CODES.R)
    static String report(Context context, TimeZone timeZone) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> infos = activityManager.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_ENTRIES);
        if (infos == null || infos.isEmpty()) return null;

        IdentityHashMap<ExitReport.Entry, ApplicationExitInfo> infoByEntry = new IdentityHashMap<>();
        ArrayList<ExitReport.Entry> entries = new ArrayList<>();
        for (ApplicationExitInfo info : infos) {
            ExitReport.Entry entry = toEntry(info);
            entries.add(entry);
            infoByEntry.put(entry, info);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        List<ExitReport.Entry> newEntries = ExitReport.selectNew(entries, preferences.getLong(PREF_LAST_TIMESTAMP, 0));
        if (newEntries.isEmpty()) return null;

        String reporter = "Winlator DL "+CrashLog.getAppVersion(context)+", "+CrashLog.getDeviceInfo();
        ExitReport.Entry newest = newEntries.get(newEntries.size() - 1);
        StringBuilder text = new StringBuilder();
        String newestDetail = null;
        int otherReported = 0;
        for (ExitReport.Entry entry : newEntries) {
            if (ExitReport.isOwnRestart(entry)) continue;
            Trace trace = readTrace(infoByEntry.get(entry), entry.reason);
            text.append(ExitReport.format(entry, trace != null ? trace.header : null, trace != null ? trace.text : null, reporter, timeZone));
            if (entry == newest) newestDetail = trace != null ? trace.summary : null;
            else otherReported++;
        }

        boolean saved = true;
        if (text.length() > 0) {
            try {
                CrashLog.append(text.toString());
            }
            catch (IOException e) {
                saved = false; // no storage permission yet: try again at the next start
            }
        }
        String line = ExitReport.sessionLine(newest, newestDetail, otherReported, timeZone);
        if (saved) preferences.edit().putLong(PREF_LAST_TIMESTAMP, newest.timestamp).commit();
        else line = line.replace("; details in crash.txt", "; crash.txt could not be written, it will be tried at the next start");
        return line;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static ExitReport.Entry toEntry(ApplicationExitInfo info) {
        ExitReport.Entry entry = new ExitReport.Entry();
        entry.timestamp = info.getTimestamp();
        entry.pid = info.getPid();
        entry.processName = info.getProcessName();
        entry.reason = info.getReason();
        entry.status = info.getStatus();
        entry.importance = info.getImportance();
        entry.pssKB = info.getPss();
        entry.rssKB = info.getRss();
        entry.description = info.getDescription();
        byte[] state = info.getProcessStateSummary();
        entry.state = state != null ? new String(state, StandardCharsets.UTF_8) : null;
        return entry;
    }

    static class Trace {
        String header;
        String text;
        String summary;
    }

    // The ANR traces (Android 11+) and the tombstone of a native crash (protobuf, Android 12+).
    @RequiresApi(Build.VERSION_CODES.R)
    private static Trace readTrace(ApplicationExitInfo info, int reason) {
        byte[] data;
        int length;
        boolean truncated;
        try (InputStream inputStream = info.getTraceInputStream()) {
            if (inputStream == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int count;
            while (buffer.size() <= MAX_TRACE_BYTES && (count = inputStream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            data = buffer.toByteArray();
            truncated = data.length > MAX_TRACE_BYTES;
            length = Math.min(data.length, MAX_TRACE_BYTES);
        }
        catch (IOException | RuntimeException e) {
            Trace trace = new Trace();
            trace.header = "could not be read: "+e;
            return trace;
        }
        return decodeTrace(data, length, truncated, reason);
    }

    static Trace decodeTrace(byte[] data, int length, boolean truncated, int reason) {
        Trace trace = new Trace();
        String size = length+" bytes"+(truncated ? " (only the first "+(MAX_TRACE_BYTES >> 20)+" MB read)" : "");
        if (reason == ExitReport.REASON_CRASH_NATIVE) {
            Tombstone tombstone = Tombstone.parse(data, length);
            if (tombstone.isUsable()) {
                trace.header = "tombstone, "+size;
                trace.text = tombstone.toText(MAX_TRACE_CHARS);
                trace.summary = tombstone.summary();
            }
            else {
                trace.header = "tombstone, "+size+", not decoded ("+tombstone.error+"), readable strings:";
                trace.text = Tombstone.readableStrings(data, length, 6, MAX_TRACE_CHARS);
            }
        }
        else {
            String text = new String(data, 0, Math.min(length, 4 * MAX_TRACE_CHARS), StandardCharsets.UTF_8);
            trace.header = (reason == ExitReport.REASON_ANR ? "ANR traces, " : "traces, ")+size;
            trace.text = Tombstone.cutText(text, MAX_TRACE_CHARS);
            String subject = ExitReport.anrSubject(text);
            if (subject != null) trace.summary = "'"+subject+"'";
        }
        return trace;
    }
}
