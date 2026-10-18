package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class GuestProcessTerminationTest {
    // Processes on a fake clock: a signal can change a process's thread count after a delay.
    private static class FakeProcesses implements ProcessHelper.ProcessControl {
        long now = 1000;
        final ArrayList<String> signals = new ArrayList<>();
        final HashMap<Integer, Integer> threadCounts = new HashMap<>();
        private final ArrayList<Object[]> reactions = new ArrayList<>(); // pid, signal, delay, thread count
        private final ArrayList<long[]> pending = new ArrayList<>();       // pid, time, thread count

        void react(int pid, String signal, long delay, int threadCount) {
            reactions.add(new Object[]{pid, signal, delay, threadCount});
        }

        private void signal(int pid, String signal) {
            signals.add(signal+" "+pid+" @"+(now-1000));
            for (Object[] r : reactions) {
                if ((int)r[0] == pid && r[1].equals(signal)) pending.add(new long[]{pid, now+(long)r[2], (int)r[3]});
            }
        }

        @Override
        public void resume(int pid) {
            signal(pid, "CONT");
        }

        @Override
        public void quit(int pid) {
            signal(pid, "QUIT");
        }

        @Override
        public void terminate(int pid) {
            signal(pid, "TERM");
        }

        @Override
        public int getThreadCount(int pid) {
            pending.removeIf((p) -> {
                if (p[1] > now) return false;
                threadCounts.put((int)p[0], (int)p[2]);
                return true;
            });
            Integer threadCount = threadCounts.get(pid);
            return threadCount != null ? threadCount : 0;
        }

        @Override
        public long uptimeMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            now += millis;
        }

        long elapsed() {
            return now-1000;
        }
    }

    // Guest processes as getChildProcesses() names them on the phone.
    private static ProcessHelper.PStat process(int pid, String name, ProcessHelper.PState state) {
        ProcessHelper.PStat pstat = new ProcessHelper.PStat();
        pstat.pid = pid;
        pstat.name = name;
        pstat.shortName = name.length() > 15 ? name.substring(name.length()-15) : name;
        pstat.state = state;
        pstat.guestProcess = name.contains("wine") || name.contains(".exe");
        return pstat;
    }

    private FakeProcesses fake;
    private List<ProcessHelper.PStat> processes;

    @Before
    public void setUp() {
        fake = new FakeProcesses();
        processes = Arrays.asList(
            process(101, "wineserver", ProcessHelper.PState.SLEEPING),
            process(102, "C:\\windows\\system32\\explorer.exe", ProcessHelper.PState.SLEEPING),
            process(103, "C:\\windows\\system32\\services.exe", ProcessHelper.PState.SLEEPING),
            process(104, "C:\\Games\\OpenTTD\\openttd.exe", ProcessHelper.PState.STOPPED),
            process(105, "pulseaudio", ProcessHelper.PState.SLEEPING)
        );
        fake.threadCounts.put(101, 1);
        fake.threadCounts.put(102, 6);
        fake.threadCounts.put(103, 9);
        fake.threadCounts.put(104, 20);
        fake.threadCounts.put(105, 3);
    }

    @Test
    public void wineProcessesGetSigquitThenWineserverGetsSigterm() {
        // Wine's SIGQUIT handler ends one thread once box64 has written its cache; wineserver exits on SIGTERM.
        fake.react(102, "QUIT", 30, 5);
        fake.react(103, "QUIT", 50, 8);
        fake.react(104, "QUIT", 200, 19);
        fake.react(101, "TERM", 40, 0);

        ProcessHelper.terminateGuestProcesses(processes, 2000, fake);

        assertEquals(Arrays.asList("CONT 104 @0", "QUIT 102 @0", "QUIT 103 @0", "QUIT 104 @0", "TERM 101 @200"), fake.signals);
        // Nothing waits for the deadline once every process has done its part.
        assertEquals(240, fake.elapsed());
    }

    @Test
    public void wineserverIsNotWaitedForBeforeWineProcessesHaveSaved() {
        // explorer.exe never handles the signal: wineserver gets SIGTERM at 3/4 of the time.
        fake.react(103, "QUIT", 50, 8);
        fake.react(104, "QUIT", 50, 0);
        fake.react(101, "TERM", 40, 0);

        ProcessHelper.terminateGuestProcesses(processes, 2000, fake);

        assertEquals("TERM 101 @1500", fake.signals.get(fake.signals.size()-1));
        assertEquals(1540, fake.elapsed());
    }

    @Test
    public void returnsWithinTheTimeoutWhenNothingEnds() {
        ProcessHelper.terminateGuestProcesses(processes, 2000, fake);

        assertEquals("TERM 101 @1500", fake.signals.get(fake.signals.size()-1));
        assertTrue(fake.elapsed() >= 2000 && fake.elapsed() < 2020);
    }

    @Test
    public void processesThatAreNotWineAreLeftAlone() {
        ProcessHelper.terminateGuestProcesses(processes, 2000, fake);

        for (String signal : fake.signals) assertFalse(signal, signal.contains(" 105 "));
    }

    @Test
    public void nothingToEndReturnsAtOnce() {
        ProcessHelper.terminateGuestProcesses(Arrays.asList(process(105, "pulseaudio", ProcessHelper.PState.SLEEPING)), 2000, fake);

        assertTrue(fake.signals.isEmpty());
        assertEquals(0, fake.elapsed());
    }

    @Test
    public void threadCountFromProcStat() {
        assertEquals(23, ProcessHelper.parseThreadCount("4321 (wineserver) S 1 4321 4321 0 -1 4194560 1180 0 0 0 12 30 0 0 20 0 23 0 52341 2134016 900 18446744073709551615"));
        // the command name can contain spaces and parentheses
        assertEquals(7, ProcessHelper.parseThreadCount("4322 (a) b (x.exe) R 1 4321 4321 0 -1 4194560 1180 0 0 0 12 30 0 0 20 0 7 0 52341 2134016 900 1"));
        assertEquals(0, ProcessHelper.parseThreadCount("4323 (openttd.exe) Z 1 4321 4321 0 -1 4194560 1180 0 0 0 12 30 0 0 20 0 1 0 52341 0 0 1"));
        assertEquals(0, ProcessHelper.parseThreadCount("4324 (truncated) S 1 4321"));
        assertEquals(0, ProcessHelper.parseThreadCount(""));
    }

    @Test
    public void wineserverIsFoundByName() {
        assertTrue(ProcessHelper.isWineserver(process(1, "wineserver", ProcessHelper.PState.SLEEPING)));
        assertFalse(ProcessHelper.isWineserver(process(2, "C:\\windows\\system32\\winedevice.exe", ProcessHelper.PState.SLEEPING)));
        assertFalse(ProcessHelper.isWineserver(process(3, "wine", ProcessHelper.PState.SLEEPING)));
    }
}
