package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class ExitReportTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long T = 1792936992000L; // 2026-10-25 14:03:12 UTC

    private static ExitReport.Entry entry(long timestamp, int reason, int status, String state) {
        ExitReport.Entry entry = new ExitReport.Entry();
        entry.timestamp = timestamp;
        entry.pid = 12345;
        entry.processName = "org.winlator";
        entry.reason = reason;
        entry.status = status;
        entry.importance = 100;
        entry.state = state;
        return entry;
    }

    @Test
    public void selectsOnlyUnreportedDeathsOldestFirst() {
        ExitReport.Entry old = entry(T - 5000, ExitReport.REASON_CRASH, 0, null);
        ExitReport.Entry reported = entry(T - 1000, ExitReport.REASON_CRASH_NATIVE, 11, null);
        ExitReport.Entry newer = entry(T + 2000, ExitReport.REASON_LOW_MEMORY, 9, null);
        ExitReport.Entry newest = entry(T + 3000, ExitReport.REASON_SIGNALED, 9, null);
        // getHistoricalProcessExitReasons lists the newest first
        List<ExitReport.Entry> selected = ExitReport.selectNew(Arrays.asList(newest, newer, reported, old), T - 1000);
        assertEquals(Arrays.asList(newer, newest), selected);
        assertTrue(ExitReport.selectNew(Arrays.asList(newest, newer), T + 3000).isEmpty());
        assertEquals(4, ExitReport.selectNew(Arrays.asList(newest, newer, reported, old), 0).size());
    }

    @Test
    public void onlyOurCleanRestartIsSkipped() {
        assertTrue(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_EXIT_SELF, 0, "restart (build 35)")));
        assertTrue(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_EXIT_SELF, 0, null)));
        assertTrue(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_EXIT_SELF, 0, "")));
        // exit(0) from native code in the middle of a session, or a non-zero exit, is news
        assertFalse(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_EXIT_SELF, 0, "session OpenGL=virgl Vulkan=vortek (build 35)")));
        assertFalse(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_EXIT_SELF, 1, "restart (build 35)")));
        assertFalse(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_SIGNALED, 9, "task removed by the user (build 35)")));
        assertFalse(ExitReport.isOwnRestart(entry(T, ExitReport.REASON_LOW_MEMORY, 9, "restart (build 35)")));
    }

    @Test
    public void namesReasonsSignalsAndImportance() {
        assertEquals("CRASH_NATIVE", ExitReport.reasonName(ExitReport.REASON_CRASH_NATIVE));
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ExitReport.reasonName(9));
        assertEquals("PACKAGE_UPDATED", ExitReport.reasonName(16));
        assertEquals("REASON_42", ExitReport.reasonName(42));
        assertEquals("SIGSEGV", ExitReport.signalName(11));
        assertEquals("SIGABRT", ExitReport.signalName(6));
        assertEquals("SIGSYS", ExitReport.signalName(31));
        assertEquals("foreground (100)", ExitReport.importanceName(100));
        assertEquals("cached (400)", ExitReport.importanceName(400));
        assertEquals("importance (77)", ExitReport.importanceName(77));

        assertEquals("SIGNALED, signal 9 (SIGKILL)", ExitReport.headline(entry(T, ExitReport.REASON_SIGNALED, 9, null)));
        assertEquals("EXIT_SELF, exit status 0", ExitReport.headline(entry(T, ExitReport.REASON_EXIT_SELF, 0, null)));
        assertEquals("ANR", ExitReport.headline(entry(T, ExitReport.REASON_ANR, 0, null)));
        assertEquals("USER_REQUESTED, status 3", ExitReport.headline(entry(T, ExitReport.REASON_USER_REQUESTED, 3, null)));
    }

    @Test
    public void formatsACrashTxtBlock() {
        ExitReport.Entry lowMemory = entry(T, ExitReport.REASON_LOW_MEMORY, 9, "session OpenGL=virgl Vulkan=vortek (build 35)");
        lowMemory.importance = 125;
        lowMemory.pssKB = 2_000_000;
        lowMemory.rssKB = 3_000_000;
        lowMemory.description = "lmkd";
        assertEquals(
            "=== 2026-10-25 14:03:12  process ended: LOW_MEMORY, signal 9 (SIGKILL) ===\n"+
            "Process: org.winlator, pid 12345, foreground service (125), PSS 1953 MB, RSS 2929 MB\n"+
            "Description: lmkd\n"+
            "State: session OpenGL=virgl Vulkan=vortek (build 35)\n"+
            "Reported by: Winlator DL 11.2-dl (build 36)\n"+
            "\n",
            ExitReport.format(lowMemory, null, null, "Winlator DL 11.2-dl (build 36)", UTC));

        ExitReport.Entry anr = entry(T, ExitReport.REASON_ANR, 0, null);
        anr.pssKB = 0;
        String block = ExitReport.format(anr, "ANR traces, 10 bytes", "main thread", null, UTC);
        assertTrue(block, block.startsWith("=== 2026-10-25 14:03:12  process ended: ANR ===\nProcess: org.winlator, pid 12345, foreground (100)\n"));
        assertTrue(block, block.endsWith("Trace: ANR traces, 10 bytes\nmain thread\n\n"));
        assertFalse(block.contains("PSS"));
    }

    @Test
    public void sessionLineSaysHowThePreviousRunEnded() {
        ExitReport.Entry nativeCrash = entry(T, ExitReport.REASON_CRASH_NATIVE, 11, "session OpenGL=virgl Vulkan=vortek (build 35)");
        nativeCrash.description = "crash";
        nativeCrash.pssKB = 800 * 1024;
        assertEquals("[DL] previous run ended: CRASH_NATIVE 'crash', signal 11 (SIGSEGV), thread 1 'x' in libfoo.so, at 2026-10-25 14:03:12, "+
                "foreground (100), PSS 800 MB, state 'session OpenGL=virgl Vulkan=vortek (build 35)'; 2 earlier exits since the last report; details in crash.txt",
            ExitReport.sessionLine(nativeCrash, "signal 11 (SIGSEGV), thread 1 'x' in libfoo.so", 2, UTC));
        // without a tombstone (Android 11) the status still names the signal
        assertTrue(ExitReport.sessionLine(nativeCrash, null, 0, UTC).startsWith("[DL] previous run ended: CRASH_NATIVE, signal 11 (SIGSEGV) 'crash', at "));

        ExitReport.Entry restart = entry(T, ExitReport.REASON_EXIT_SELF, 0, "restart (build 35)");
        assertEquals("[DL] previous run ended: EXIT_SELF, exit status 0, at 2026-10-25 14:03:12 (Winlator DL restart)",
            ExitReport.sessionLine(restart, null, 0, UTC));
        assertEquals("[DL] previous run ended: EXIT_SELF, exit status 0, at 2026-10-25 14:03:12 (Winlator DL restart); 1 earlier exit since the last report; details in crash.txt",
            ExitReport.sessionLine(restart, null, 1, UTC));
    }

    @Test
    public void decodesATombstone() {
        Tombstone tombstone = Tombstone.parse(TombstoneFixture.virglSegv(), TombstoneFixture.virglSegv().length);
        assertNull(tombstone.error);
        assertTrue(tombstone.isUsable());
        assertEquals(12400, tombstone.tid);
        assertEquals(2, tombstone.threads.size());
        assertEquals(2, tombstone.memoryMappingCount);
        assertEquals("VirGLServer", tombstone.getCrashingThread().name);

        assertEquals("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010, thread 12400 'VirGLServer' in libc.so (memcpy+40)"+
                " called from libvirglrenderer.so (vrend_renderer_transfer_iov+120), cause: null pointer dereference",
            tombstone.summary());

        String text = tombstone.toText(16 * 1024);
        assertTrue(text, text.contains("ABI: 'arm64'\n"));
        assertTrue(text, text.contains("Process uptime: 812s\n"));
        assertTrue(text, text.contains("pid: 12345, tid: 12400, name: VirGLServer  >>> org.winlator <<<\n"));
        assertTrue(text, text.contains("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010\n"));
        assertTrue(text, text.contains("Cause: null pointer dereference\n"));
        assertTrue(text, text.contains("backtrace:\n"+
            "      #00 pc 000000000004f8b8  "+TombstoneFixture.LIBC+" (memcpy+40)\n"+
            "      #01 pc 0000000000001234  "+TombstoneFixture.VIRGL_LIB+" (vrend_renderer_transfer_iov+120) (BuildId: deadbeef)\n"+
            "      #02 pc 0000000000005678  "+TombstoneFixture.VIRGL_LIB+" (BuildId: deadbeef)\n"));
        assertTrue(text, text.contains("  10-25 14:03:11.900 12400 E virgl: transfer failed second line\n"));
        assertTrue(text, text.contains("other threads (1):\n  12345 'main': libc.so (__epoll_pwait+8)\n"));
        assertTrue(text, text.contains("memory mappings: 2 (not shown)\n"));
        assertFalse(text, text.contains("decoding stopped"));

        String cut = tombstone.toText(300);
        assertTrue(cut, cut.length() < 400);
        assertTrue(cut, cut.endsWith("... (cut at 300 characters)\n"));
    }

    @Test
    public void keepsWhatItGotFromATruncatedTombstone() {
        byte[] data = TombstoneFixture.virglSegv();
        // the data stops inside the threads: signal and cause are complete, the rest is gone
        int end = new String(data, StandardCharsets.ISO_8859_1).indexOf("VirGLServer");
        Tombstone tombstone = Tombstone.parse(data, end);
        assertNotNull(tombstone.error);
        assertTrue(tombstone.isUsable());
        assertEquals(11, tombstone.signalNumber);
        assertTrue(tombstone.summary(), tombstone.summary().startsWith("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"));
        assertTrue(tombstone.toText(16 * 1024).contains("(decoding stopped: "));
    }

    @Test
    public void fallsBackToReadableStringsForSomethingElse() {
        byte[] data = "this is not a tombstone\u0000\u0007libGLES_mali.so\u0001x".getBytes(StandardCharsets.ISO_8859_1);
        Tombstone tombstone = Tombstone.parse(data, data.length);
        assertFalse(tombstone.isUsable());
        assertNotNull(tombstone.error);
        assertEquals("this is not a tombstone | libGLES_mali.so", Tombstone.readableStrings(data, data.length, 6, 1000));
        assertEquals("this is...", Tombstone.readableStrings(data, data.length, 6, 7));
    }

    @Test
    public void findsTheAnrSubject() {
        assertEquals("Input dispatching timed out (Waiting to send key event)",
            ExitReport.anrSubject("\n----- pid 1 -----\nSubject: Input dispatching timed out (Waiting to send key event)\n\"main\" prio=5"));
        assertNull(ExitReport.anrSubject("\"main\" prio=5 tid=1 Native\n"));
        assertNull(ExitReport.anrSubject(null));
    }
}
