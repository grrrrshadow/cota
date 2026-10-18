package com.winlator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.winlator.core.FileUtils;
import com.winlator.core.SessionLog;
import com.winlator.widget.LogView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SessionLogTest {
    @Test
    public void keepsThePreviousSessionAndStopsAt4MB() {
        File logFile = SessionLog.getLogFile();
        File previousLogFile = LogView.getPreviousLogFile(logFile);
        assertEquals("session-previous.txt", previousLogFile.getName());

        SessionLog first = new SessionLog();
        first.call("[DL] last line before the crash");

        SessionLog second = new SessionLog();
        assertTrue(FileUtils.readString(previousLogFile).contains("[DL] last line before the crash"));
        assertEquals(0, logFile.length());

        String line = new String(new char[1000]).replace('\0', 'x');
        for (int i = 0; i < 5000; i++) second.call(line);
        String content = FileUtils.readString(logFile);
        assertTrue(content.endsWith("[DL] session log stopped at 4 MB\n"));
        assertTrue(logFile.length() < 4 * 1024 * 1024 + 2048);
    }
}
