package com.winlator.core;

import com.winlator.widget.LogView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Always on, so a crash nobody saw coming still leaves its last lines: our [DL] lines,
// Mesa's GL errors, Wine's errors and crash messages. It stays small because these only appear when something goes wrong.
public class SessionLog implements Callback<String> {
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ENGLISH);
    private PrintStream printStream;
    private long size = 0;

    public SessionLog() {
        File logFile = getLogFile();
        File previousLogFile = LogView.getPreviousLogFile(logFile);
        FileUtils.delete(previousLogFile);
        if (!logFile.renameTo(previousLogFile)) FileUtils.delete(logFile);

        try {
            printStream = new PrintStream(new FileOutputStream(logFile));
        }
        catch (IOException e) {
            printStream = null;
        }
    }

    public static File getLogFile() {
        return new File(LogView.getLogFile().getParentFile(), "session.txt");
    }

    @Override
    public synchronized void call(String line) {
        if (printStream == null || size > MAX_FILE_SIZE) return;
        String text = "["+timeFormat.format(new Date())+"] "+line+"\n";
        size += text.length();
        if (size > MAX_FILE_SIZE) text += "[DL] session log stopped at 4 MB\n";
        printStream.print(text);
        printStream.flush();
    }
}
