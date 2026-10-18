package com.winlator.core;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.winlator.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public abstract class ProcessHelper {
    public enum PState {RUNNING, SLEEPING, WAITING, ZOMBIE, STOPPED, DEAD, OTHER}
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();

    public static class PStat {
        public int pid = 0;
        public String name = "";
        public String shortName = "";
        public PState state = PState.OTHER;
        public int parentPID = 0;
        public boolean guestProcess = false;

        @NonNull
        @Override
        public String toString() {
            return pid+" "+name+" "+state+" "+parentPID+" "+guestProcess;
        }
    }

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGCONT);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGKILL);
    }

    // What terminateGuestProcesses needs from the system, so a test can stand in for it.
    interface ProcessControl {
        void resume(int pid);

        void quit(int pid);

        void terminate(int pid);

        // 0 once the process has ended, also while it is a zombie
        int getThreadCount(int pid);

        long uptimeMillis();

        void sleep(long millis);
    }

    private static final ProcessControl systemProcessControl = new ProcessControl() {
        @Override
        public void resume(int pid) {
            Process.sendSignal(pid, OsConstants.SIGCONT);
        }

        @Override
        public void quit(int pid) {
            Process.sendSignal(pid, OsConstants.SIGQUIT);
        }

        @Override
        public void terminate(int pid) {
            Process.sendSignal(pid, OsConstants.SIGTERM);
        }

        @Override
        public int getThreadCount(int pid) {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/stat"))) {
                String line = reader.readLine();
                return line != null ? parseThreadCount(line) : 0;
            }
            catch (IOException e) {
                return 0;
            }
        }

        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void sleep(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    // Lets Wine end before the app kills whatever is left, which would lose the session's work:
    // on SIGQUIT box64 writes its DynaCache files and then Wine's handler ends that thread, and on SIGTERM
    // wineserver saves the registry and exits, which also ends the Windows processes still running.
    // Returns within about timeoutMs.
    public static void terminateGuestProcesses(int timeoutMs) {
        terminateGuestProcesses(getChildProcesses(), timeoutMs, systemProcessControl);
    }

    static void terminateGuestProcesses(List<PStat> processes, int timeoutMs, ProcessControl control) {
        long startTime = control.uptimeMillis();
        LinkedHashMap<Integer, Integer> threadCounts = new LinkedHashMap<>();
        ArrayList<Integer> wineserverPids = new ArrayList<>();
        for (PStat process : processes) {
            if (!process.guestProcess) continue;
            // A stopped process (the activity was paused) would only see the signal once resumed.
            if (process.state == PState.STOPPED) control.resume(process.pid);
            if (isWineserver(process)) {
                wineserverPids.add(process.pid);
            }
            else threadCounts.put(process.pid, control.getThreadCount(process.pid));
        }

        for (int pid : threadCounts.keySet()) control.quit(pid);
        // Wine's SIGQUIT handler ends the thread it ran on, so one thread fewer means box64 has finished writing.
        long deadline = startTime + timeoutMs * 3L / 4;
        while (true) {
            threadCounts.entrySet().removeIf((entry) -> {
                int threadCount = control.getThreadCount(entry.getKey());
                return threadCount == 0 || threadCount < entry.getValue();
            });
            if (threadCounts.isEmpty() || control.uptimeMillis() >= deadline) break;
            control.sleep(20);
        }

        for (int pid : wineserverPids) control.terminate(pid);
        deadline = startTime + timeoutMs;
        while (true) {
            wineserverPids.removeIf((pid) -> control.getThreadCount(pid) == 0);
            if (wineserverPids.isEmpty() || control.uptimeMillis() >= deadline) break;
            control.sleep(20);
        }
    }

    static boolean isWineserver(PStat process) {
        return process.name.contains("wineserver") || process.shortName.contains("wineserver");
    }

    // Thread count from a /proc/<pid>/stat line, 0 for a zombie. The command name in parentheses may contain
    // spaces and parentheses itself, so the fields are counted from the last ')'.
    static int parseThreadCount(String stat) {
        int end = stat.lastIndexOf(')');
        if (end == -1) return 0;
        String[] fields = stat.substring(end+1).trim().split(" +");
        if (fields.length < 18 || fields[0].equals("Z") || fields[0].equals("X")) return 0;
        try {
            return Integer.parseInt(fields[17]);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, EnvVars envVars) {
        return exec(command, envVars, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir) {
        return exec(command, envVars, workingDir, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        return exec(command, envVars, workingDir, 0, terminationCallback);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, int affinityMask, Callback<Integer> terminationCallback) {
        int pid = -1;
        try {
            ProcessBuilder processBuilder = (new ProcessBuilder(splitCommand(command))).directory(workingDir);
            if (debugCallbacks.isEmpty()) processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);

            Map<String, String> environment = processBuilder.environment();
            for (String name : envVars) environment.put(name, envVars.get(name));

            // A child starts with the CPU affinity of the thread that forks it, and passes it on to its own children.
            int oldAffinityMask = affinityMask != 0 ? setThreadAffinityMask(affinityMask) : 0;
            java.lang.Process process;
            try {
                process = processBuilder.start();
            }
            finally {
                if (oldAffinityMask != 0) setThreadAffinityMask(oldAffinityMask);
            }
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }

            if (terminationCallback != null) createWaitForThread(process, terminationCallback);
        }
        catch (Exception e) {}
        return pid;
    }

    // Adds a line of our own to the log next to the guest's output.
    public static void debugLog(String line) {
        synchronized (debugCallbacks) {
            for (Callback<String> callback : debugCallbacks) callback.call(line);
        }
    }

    // Pinning only speeds things up, so a missing native method must not stop the launch.
    private static int setThreadAffinityMask(int mask) {
        try {
            return ThreadAffinity.setMask(mask);
        }
        catch (LinkageError e) {
            return 0;
        }
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                        else if (MainActivity.DEBUG_MODE) System.out.println(line);
                    }
                }
            }
            catch (IOException e) {}
        });
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int status = process.waitFor();
                terminationCallback.call(status);
            }
            catch (InterruptedException e) {}
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static long getMemoryUsage(int pid) {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/statm"))) {
            byte index = 0;
            long vmSize = 0;
            long resident = 0;

            while (scanner.hasNext() && index < 2) {
                byte column = index++;
                if (column == 0) {
                    vmSize = scanner.nextLong();
                }
                else resident = scanner.nextLong();
            }

            return (resident * Os.sysconf(OsConstants._SC_PAGESIZE));
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static String getProcessName(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder result = new StringBuilder();
            int chr;
            while ((chr = reader.read()) != -1 && chr != 0) {
                result.append((char)chr);
            }
            return result.length() > 0 && result.charAt(0) == '/' ? FileUtils.getName(result.toString()) : result.toString();
        }
        catch (IOException e) {
            return "";
        }
    }

    public static List<String> getProcessCmdLine(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder sb = new StringBuilder();
            ArrayList<String> result = new ArrayList<>();

            int charsRead;
            char[] chars = new char[64];
            while ((charsRead = reader.read(chars)) != -1) {
                for (int i = 0; i < charsRead; i++) {
                    if (chars[i] == '\0') {
                        if (sb.length() == 0) break;
                        result.add(sb.toString());
                        sb = new StringBuilder();
                    }
                    else sb.append(chars[i]);
                }
            }
            return result;
        }
        catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static List<PStat> getChildProcesses() {
        File procFile = new File("/proc");
        String[] pids = procFile.list((file, name) -> (new File(file, name)).isDirectory() && name.matches("[0-9]+"));
        if (pids == null) return Collections.emptyList();
        ArrayList<PStat> result = new ArrayList<>();
        int parentPID = Os.getpid();

        for (String pid : pids) {
            try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/stat"))) {
                PStat pstat = new PStat();
                int index = 0;

                while (scanner.hasNext() && index < 4) {
                    switch (index++) {
                        case 0:
                            pstat.pid = scanner.nextInt();
                            break;
                        case 1:
                            Pattern oldDelimiter = scanner.delimiter();
                            scanner.useDelimiter("\\)");
                            pstat.shortName = scanner.hasNext() ? scanner.next().substring(2) : "";
                            scanner.useDelimiter(oldDelimiter);
                            if (scanner.hasNext()) scanner.next();
                            break;
                        case 2: {
                            switch (scanner.next()) {
                                case "R":
                                    pstat.state = PState.RUNNING;
                                    break;
                                case "S":
                                    pstat.state = PState.SLEEPING;
                                    break;
                                case "D":
                                    pstat.state = PState.WAITING;
                                    break;
                                case "Z":
                                    pstat.state = PState.ZOMBIE;
                                    break;
                                case "T":
                                    pstat.state = PState.STOPPED;
                                    break;
                                case "X":
                                    pstat.state = PState.DEAD;
                                    break;
                            }
                            break;
                        }
                        case 3:
                            pstat.parentPID = scanner.nextInt();
                            break;
                    }
                }

                if (pstat.parentPID == parentPID || pstat.pid > parentPID) {
                    pstat.name = getProcessName(pstat.pid);
                    if (pstat.name.isEmpty()) pstat.name = pstat.shortName;
                    pstat.guestProcess = pstat.name.contains("wine") || pstat.name.contains(".exe");
                    result.add(pstat);
                }
            }
            // A process that ended while the list was read: skip it, the others are still there.
            catch (Exception e) {}
        }

        return result;
    }
}
