package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExitReasonsTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long T = 1792936992000L; // 2026-10-25 14:03:12 UTC
    private Context context;
    private ShadowActivityManager activityManager;
    private SharedPreferences preferences;
    private File crashFile;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        activityManager = Shadows.shadowOf(context.getSystemService(ActivityManager.class));
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        crashFile = CrashLog.getCrashFile();
        crashFile.delete();
    }

    private void addExit(long timestamp, int reason, int status, String description, String state, byte[] trace) {
        ShadowActivityManager.ApplicationExitInfoBuilder builder = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setTimestamp(timestamp).setPid((int)(timestamp % 30000)).setProcessName("org.winlator")
            .setReason(reason).setStatus(status).setImportance(100).setPss(800 * 1024).setRss(1200 * 1024);
        if (description != null) builder.setDescription(description);
        if (state != null) builder.setProcessStateSummary(state.getBytes(StandardCharsets.UTF_8));
        if (trace != null) builder.setTraceInputStream(new ByteArrayInputStream(trace));
        activityManager.addApplicationExitInfo(builder.build());
    }

    @Test
    public void reportsNewDeathsOnceWithTheDecodedTombstone() {
        preferences.edit().putLong("exit_reasons_last_timestamp", T - 1000).commit();
        addExit(T - 2000, ApplicationExitInfo.REASON_CRASH, 0, "already reported", null, null);
        addExit(T, ApplicationExitInfo.REASON_EXIT_SELF, 0, null, "restart (build 35)", null);
        addExit(T + 60_000, ApplicationExitInfo.REASON_LOW_MEMORY, 9, "lmkd", "session OpenGL=vortek Vulkan=vortek (build 35)", null);
        addExit(T + 120_000, ApplicationExitInfo.REASON_CRASH_NATIVE, 11, "crash", "session OpenGL=virgl Vulkan=vortek (build 35)", TombstoneFixture.virglSegv());

        String line = ExitReasons.report(context, UTC);

        String crash = FileUtils.readString(crashFile);
        assertFalse(crash, crash.contains("already reported"));
        assertFalse(crash, crash.contains("EXIT_SELF"));
        int lowMemory = crash.indexOf("=== 2026-10-25 14:04:12  process ended: LOW_MEMORY, signal 9 (SIGKILL) ===\n");
        int nativeCrash = crash.indexOf("=== 2026-10-25 14:05:12  process ended: CRASH_NATIVE, signal 11 (SIGSEGV) ===\n");
        assertTrue(crash, lowMemory >= 0 && nativeCrash > lowMemory);
        assertTrue(crash, crash.contains("Process: org.winlator, pid "+((T + 120_000) % 30000)+", foreground (100), PSS 800 MB, RSS 1200 MB\n"));
        assertTrue(crash, crash.contains("State: session OpenGL=virgl Vulkan=vortek (build 35)\n"));
        assertTrue(crash, crash.contains("Trace: tombstone, "+TombstoneFixture.virglSegv().length+" bytes\n"));
        assertTrue(crash, crash.contains("#01 pc 0000000000001234  "+TombstoneFixture.VIRGL_LIB+" (vrend_renderer_transfer_iov+120)"));

        assertEquals("[DL] previous run ended: CRASH_NATIVE 'crash', signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010, "+
                "thread 12400 'VirGLServer' in libc.so (memcpy+40) called from libvirglrenderer.so (vrend_renderer_transfer_iov+120), "+
                "cause: null pointer dereference, at 2026-10-25 14:05:12, foreground (100), PSS 800 MB, RSS 1200 MB, "+
                "state 'session OpenGL=virgl Vulkan=vortek (build 35)'; 1 earlier exit since the last report; details in crash.txt", line);
        assertEquals(T + 120_000, preferences.getLong("exit_reasons_last_timestamp", 0));

        // the next start has nothing new to say
        assertNull(ExitReasons.report(context, UTC));
        assertEquals(crash, FileUtils.readString(crashFile));
    }

    @Test
    public void ourOwnRestartOnlyGoesToTheSessionLog() {
        addExit(T, ApplicationExitInfo.REASON_EXIT_SELF, 0, null, "restart (build 35)", null);
        assertEquals("[DL] previous run ended: EXIT_SELF, exit status 0, at 2026-10-25 14:03:12 (Winlator DL restart)", ExitReasons.report(context, UTC));
        assertFalse(crashFile.exists());
        assertEquals(T, preferences.getLong("exit_reasons_last_timestamp", 0));
    }

    @Test
    public void firstStartReportsTheWholeHistory() {
        addExit(T, ApplicationExitInfo.REASON_SIGNALED, 9, null, "task removed by the user (build 35)", null);
        addExit(T + 1000, ApplicationExitInfo.REASON_ANR, 0, "user request after error: Input dispatching timed out", null,
            "----- pid 1 at 2026-10-25 -----\nSubject: Input dispatching timed out\n\"main\" prio=5 tid=1 Native\n".getBytes(StandardCharsets.UTF_8));
        String line = ExitReasons.report(context, UTC);
        assertTrue(line, line.startsWith("[DL] previous run ended: ANR 'user request after error: Input dispatching timed out', 'Input dispatching timed out', at 2026-10-25 14:03:13"));
        assertTrue(line, line.endsWith("; 1 earlier exit since the last report; details in crash.txt"));
        String crash = FileUtils.readString(crashFile);
        assertTrue(crash, crash.contains("process ended: SIGNALED, signal 9 (SIGKILL) ===\n"));
        assertTrue(crash, crash.contains("State: task removed by the user (build 35)\n"));
        assertTrue(crash, crash.contains("Trace: ANR traces, "));
        assertTrue(crash, crash.contains("\"main\" prio=5 tid=1 Native\n"));
    }

    @Test
    public void storesTheStringsOfATraceThatIsNoTombstone() {
        byte[] garbage = "\u0007\u0003libGLES_mali.so ended badly\u0000".getBytes(StandardCharsets.ISO_8859_1);
        ExitReasons.Trace trace = ExitReasons.decodeTrace(garbage, garbage.length, false, ApplicationExitInfo.REASON_CRASH_NATIVE);
        assertTrue(trace.header, trace.header.startsWith("tombstone, "+garbage.length+" bytes, not decoded ("));
        assertEquals("libGLES_mali.so ended badly", trace.text);
        assertNull(trace.summary);

        // a tombstone cut short in the threads still tells the signal
        byte[] tombstone = TombstoneFixture.virglSegv();
        int end = new String(tombstone, StandardCharsets.ISO_8859_1).indexOf("VirGLServer");
        ExitReasons.Trace truncated = ExitReasons.decodeTrace(tombstone, end, true, ApplicationExitInfo.REASON_CRASH_NATIVE);
        assertEquals("tombstone, "+end+" bytes (only the first 4 MB read)", truncated.header);
        // the main thread came through, the crashing VirGLServer thread did not: no thread is blamed
        assertEquals("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010, cause: null pointer dereference", truncated.summary);
        assertTrue(truncated.text, truncated.text.contains("(decoding stopped: "));
    }
}
