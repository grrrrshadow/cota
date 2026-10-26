package com.winlator.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.winlator.widget.LogView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Winlator's own crashes never reach logs.txt, which only gets the output of Wine and Box64.
public abstract class CrashLog {
    private static final long MAX_FILE_SIZE = 256 * 1024;
    private static boolean installed = false;

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;

        final String appVersion = getAppVersion(context);
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(thread, throwable, appVersion);
                ProcessHelper.debugLog("[DL] Winlator crashed: "+throwable+" (details in "+getCrashFile().getPath()+")");
            }
            catch (Throwable e) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, throwable);
        });
    }

    public static File getCrashFile() {
        return new File(LogView.getLogFile().getParentFile(), "crash.txt");
    }

    public static String getAppVersion(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName+" (build "+pInfo.versionCode+")";
        }
        catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static String getDeviceInfo() {
        return "Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+"), "+Build.MANUFACTURER+" "+Build.MODEL;
    }

    // Shared with ExitReasons; past the size cap the file starts over.
    public static synchronized void append(String text) throws IOException {
        File crashFile = getCrashFile();
        boolean append = crashFile.length() < MAX_FILE_SIZE;
        try (FileWriter writer = new FileWriter(crashFile, append)) {
            writer.write(text);
        }
    }

    private static void write(Thread thread, Throwable throwable, String appVersion) throws IOException {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date());
            writer.println("=== "+time+"  Winlator DL "+appVersion+"  "+getDeviceInfo()+" ===");
            writer.println("Thread: "+thread.getName());
            throwable.printStackTrace(writer);
            writer.println();
        }
        append(text.toString());
    }
}
